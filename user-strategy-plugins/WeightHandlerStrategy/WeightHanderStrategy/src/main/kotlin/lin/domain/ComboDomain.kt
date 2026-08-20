package lin.domain


import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import lin.bean.ComboCard
import lin.domain.combo.*
import lin.domain.context.*
import lin.lifecycle.LifecycleRegister
import lin.lifecycle.LifecycleRegisterImpl
import lin.myLog
import lin.utils.serviceLoader.JarClassLoader
import lin.warExt.base.getNowCost
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.*


/**
 * 权重策略的一回合出牌编排器。
 *
 * 本类位于“组合搜索”和“真实出牌”之间：先用 [WeightHandlerDomain] 从当前 [War]
 * 快照中选择高权重组合，再按前置策略、普通卡牌和延后卡牌的顺序交给 [MyWarManage]
 * 执行。每次动作后都会检测战局是否发生变化；一旦变化便重新加载快照并重新搜索，
 * 避免继续执行基于旧费用、旧手牌或旧目标计算出的组合。
 *
 * 插件中的规则和数据库组件通过 Koin/SPI 动态加载，因此所有入口都必须临时切换线程
 * 上下文类加载器。调用结束后会恢复原类加载器，防止插件类加载器泄漏到宿主线程。
 */
class ComboDomain(war: War) {

    /** 在插件类加载器和临时 Koin 环境初始化完成后赋值，负责战局快照与真实动作。 */
    private lateinit var warManage: MyWarManage

    /** 根据当前快照计算可用卡牌组合、换牌权重和发现选择。 */
    private lateinit var weightHandlerDomain: WeightHandlerDomain

    /** 汇总规则生命周期，使一局/一次执行所需的规则状态按统一时机初始化。 */
    private val lifecycleRegisterImpl = LifecycleRegisterImpl()

    /**
     * 策略 jar 的类加载器。ServiceLoader 与 Koin 都依赖线程上下文类加载器发现插件实现，
     * 获取失败时回退到当前类的加载器，确保内置规则仍有机会运行。
     */
    private val classLoader = JarClassLoader(parent = javaClass.classLoader).classLoader() ?: run {
        myLog.warn { "没有获取到类加载器" }
        javaClass.classLoader
    }

    /** 最优组合之外仍可能在剩余费用阶段尝试的卡牌，按 [ComboCard] 的自然顺序排列。 */
    private var unAbleUseCards: TreeSet<ComboCard>? = null

    /** 在前置/后置策略之间传递本次动作结果，并在发现选择结束后释放等待状态。 */
    private val useStrategyUtils = UseStrategyUtils()

    // 只在构造阶段短暂启动 Koin：解析并构造领域对象后立即停止，避免污染宿主全局容器。
    init {
        myLog.info {
            "ComboDao初始化"
        }
        threadContext {

            startKoin {
                modules(DBModules, ParseCardWeightInfoModule)
                modules(module { single { lifecycleRegisterImpl } bind LifecycleRegister::class })
            }
            Thread.currentThread().contextClassLoader = classLoader
            warManage = MyWarManage(war)
            weightHandlerDomain = WeightHandlerDomain(warManage = warManage)
            stopKoin()
        }
    }

    /** 在插件类加载器上下文执行代码，并无条件恢复调用线程原来的上下文类加载器。 */
    private inline fun threadContext(runnable: () -> Unit) {
        val threadClassLoader = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = classLoader
            runnable()
        } catch (t: Throwable) {
            myLog.error(t) { "全局错误捕获" }
            throw t
        } finally {
            Thread.currentThread().contextClassLoader = threadClassLoader
        }
    }

    /**
     * 建立一次组合计算所需的规则生命周期和战局执行环境。
     * 规则级生命周期每次执行都会启动；对局级生命周期仅在战局已经开始时启动。
     */
    private inline fun executeEnvironment(runnable: () -> Unit) {
        threadContext {
            lifecycleRegisterImpl.startAllRuleLifecycles()
            val isStart = warManage.isStart()
            if (isStart) {
                lifecycleRegisterImpl.startAllGameLifecycles()
            }
            warManage.executeEnvironment {
                runnable()
            }
        }

    }

    /**
     * 最优组合执行后尝试利用剩余费用。
     *
     * 英雄技能被包装成临时 [ComboCard] 与候选卡一起按权重比较；只有“费用权重 + 动作权重”
     * 没有跌破 [NotWeight] 且费用足够时才会尝试。返回 true 表示战局发生变化并已触发重算。
     */
    private fun processLessCost(): Boolean {
        myLog.info { "处理剩余费用" }
        val costWeight = CostWeight * warManage.getNowCost()

        //todo-future 可能报纸不打零费牌
        if (costWeight == NotWeight) return false
        val skillComboCard: ComboCard? = warManage.war.me.playArea.power?.let {
            if (costWeight + UseSkillWeight < NotWeight) return@let null
            val skill = ComboCard(null, it)
            skill.addWeight(UseSkillWeight)
            skill
        }
        unAbleUseCards?.let { unAbleUseCards ->
            skillComboCard?.also {
                unAbleUseCards.add(it)
            }
            for (unAbleUseCard in unAbleUseCards) {
                if (costWeight + unAbleUseCard.powerWeight < NotWeight) return false
                if (unAbleUseCard.cost() <= warManage.getNowCost()) {
                    if (useCardAndIsReload(unAbleUseCard)) return true
                }
            }
        } ?: run {
            skillComboCard?.also {
                return useCardAndIsReload(it)
            }
        }

        return false
    }
    /** 同一轮因战局变化触发的重算深度，用于阻断动作异常导致的无限递归。 */
    private var stackNum = 0

    /**
     * 执行当前回合的出牌策略入口。
     * 先寻找并执行最优组合，再用 [processLessCost] 尝试消耗仍可利用的费用。
     */
    fun outCardStrategy() {
        myLog.info { "执行出牌策略" }
        stackNum = 0
        executeEnvironment {
            findAndUse()
            processLessCost()
        }
    }

    /** 查询最优组合，并仅在搜索得到完整 [EndWeightResult] 时进入真实动作阶段。 */
    private fun findAndUse() {
        if (stackNum == MaxStackNum) {
            log.warn { "栈过深" }
            return
        } else
            stackNum++
        val weightResult = weightHandlerDomain.findCombination()
        if (weightResult is EndWeightResult) {
            myLog.info { "找到需要使用的卡牌:${weightResult.bestCombination}" }
            unAbleUseCards = weightResult.unUseCards
            executeUseCard(weightResult)
        }
    }



    /**
     * 按组合约束执行搜索结果。
     *
     * 普通卡按权重从高到低执行；带 lastUse 约束的卡延迟到最后，并按 comboWeight 排序。
     * 如果实际费用下降少于预期，说明组合中有动作未成功，再尝试搜索结果提供的补偿候选。
     */
    private fun executeUseCard(weightResult: EndWeightResult) {
        val bestCombination = weightResult.bestCombination
        // 5. 执行找到的最佳出牌组合
        if (bestCombination.isNotEmpty()) {

            //只有一个处理
            if (bestCombination.size == 1) {
                useCardAndIsReload(bestCombination.first())
                return
            }


            val needCost = bestCombination.sumOf { it.cost() }

            //todo-future  打出优先级处理 combo情况处理
            val bestCombinationCombo = bestCombination.sortedByDescending {
                it.powerWeight
            }
            myLog.info {
                val finalWeight = bestCombinationCombo.sumOf { it.powerWeight }
                val msg =
                    "找到最优出牌组合 (总费用: $needCost, 总权重: $finalWeight): $bestCombinationCombo"
                msg
            }


            val expectCost = warManage.getNowCost() - needCost

            //todo-future 这里使用策略有问题,要扩展要改源码
            var lastUse: SortedSet<ComboCard>? = null
            for (card in bestCombinationCombo) {
                card.lastUse?.let {
                    lastUse?.run {
                        add(card)
                    } ?: run {
                        lastUse = sortedSetOf(
                            compareByDescending<ComboCard> { it.lastUse!!.comboWeight }.thenBy {
                                it.card.entityId
                            }
                        )
                        lastUse.add(card)
                    }
                    myLog.info { "id:${card.cardId()},name:${card.card.entityName}添加到最后打出" }
                } ?: run {
                    if (useCardAndIsReload(card)) return
                }
            }
            if (lastUse != null) {
                for (lastUseCard in lastUse) {
                    if (useCardAndIsReload(lastUseCard)) return
                }
            }

            if (warManage.getNowCost() > expectCost) {//说明有些牌没打出去,通过补偿
                val moreTryCard = weightResult.lessAbleUseCards()
                if (moreTryCard.isNotEmpty()) {
                    for (moreCard in moreTryCard) {
                        if (useCardAndIsReload(moreCard)) return
                    }
                }
            }


        }
    }

    /** 按声明顺序执行卡牌的前置扩展动作。 */
    fun List<UseBeforeStrategy>.executeAction(card: ComboCard, useStrategyUtils: UseStrategyUtils) {
        this.forEach {
            it.extAction(card, useStrategyUtils)
        }
    }

    /** 按声明顺序执行卡牌的后置扩展动作，并共享本次真实出牌结果。 */
    fun List<UseAfterStrategy>.executeAfterAction(card: ComboCard, useStrategyUtils: UseStrategyUtils) {
        this.forEach {
            it.afterExtAction(card, useStrategyUtils)
        }
    }
    /**
     * 尝试执行一张卡或英雄技能，并在战局变化后立即刷新和重算。
     *
     * 返回值表示动作前后快照是否发生变化，而不只是底层点击是否成功。成功动作后等待动画，
     * 是为了让日志解析和画面状态追上真实客户端，再进行下一次组合搜索。
     */
    fun useCardAndIsReload(card: ComboCard): Boolean {
        val changeResult = warManage.isChange {
            card.useBeforeStrategy?.executeAction(card, useStrategyUtils)
            val useResult = warManage.tryUseCard(card)
            myLog.info { "打出$card,使用结果:$useResult" }
            useStrategyUtils.useResult = useResult
            card.useAfterStrategy?.executeAfterAction(card, useStrategyUtils)
            if (useResult) {
                myLog.info { "打出等待动画" }
                Thread.sleep(AwaitAnimationTime)
                card
            } else null
        }
        if (changeResult) {
            myLog.info { "有变化,重新查询combo" }
            warManage.reLoad()
            findAndUse()
        }
        return changeResult
    }

    /**
     * 计算起手换牌集合。启用换牌权重时由规则系统修改集合；关闭时使用“保留费用不高于 2”
     * 的简单回退策略。传入集合会被原地修改，调用方应直接读取执行后的集合。
     */
    fun executeChangeCard(cards: HashSet<Card>) {
        threadContext {
            if (BaseData.enableChangeWeight) {
                val changeWeightResult = ChangeWeightResult(cards, warManage.parseComboCards(cards.toList()))
                changeWeightResult.processChangeCard()

            } else {
                cards.removeIf { card -> card.cost > 2 }
            }
        }

    }

    /**
     * 对发现候选执行权重选择并返回候选下标。
     * finally 中无条件释放 [useStrategyUtils] 的等待状态，避免规则异常使后续动作永久阻塞。
     */
    fun executeDiscoverChooseCard(vararg cards: Card): Int {
        try {
            var index = 0
            threadContext {
                index = weightHandlerDomain.executeDiscoverChooseCard(*cards)
            }
            return index
        } finally {
            useStrategyUtils.down()
        }

    }

}




