package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptbase.util.isFalse
import club.xiaojiawei.hsscriptbase.util.isTrue
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.bean.safeRun
import club.xiaojiawei.hsscriptcardsdk.data.COIN_CARD_ID
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.TimelineEvent

/**
 * 将抽象 [club.xiaojiawei.hsscriptstrategysdk.DeckStrategy] 决策转换为真实游戏操作。
 *
 * 该对象位于“模型/策略”和“鼠标/UI”之间：PhaseStrategy 在适当时机调用它，它先检查
 * 策略开关、玩家有效性和投降请求，再委托当前策略，最后通过 GameUtil 完成确认、取消、
 * 结束回合等兜底动作。策略异常不应让游戏永久卡在选择界面，因此关键流程使用 `finally`
 * 恢复 UI 状态。
 *
 * 调用方法通常包含动画等待和鼠标操作，不能在 JavaFX Application Thread 上执行。
 *
 * @author 肖嘉威
 * @date 2022/11/29 17:29
 */
object DeckStrategyActuator {

    private val war = WAR

    /** 重置当前策略的局内状态，并立即消费策略可能提出的投降请求。 */
    fun reset() {
        DeckStrategyManager.currentDeckStrategy?.reset()
        checkSurrender()
    }

    /** 按概率发送问候/感谢表情；仅在策略执行条件满足时生效。 */
    fun randEmoji() {
        if (!canExec()) return

        val random = RandomUtil.RANDOM
        (random.nextInt() and 1 == 1).isTrue {
            (random.nextInt() and 1 == 1).isTrue {
                GameUtil.sendThankEmoji()
            }.isFalse {
                GameUtil.sendGreetEmoji()
            }
        }
    }

    /**
     * 非本人回合随机做点事情
     */
    fun randomDoSomething() {
        if (!canExec()) return

        val random = RandomUtil.RANDOM
        if (random.nextInt() and 1 == 1) {
            log.info { "随机做点事情" }
            Thread.sleep(2000)
            val minTime = 5000
            val maxTime = 12000
            while (!PauseStatus.isPause && !war.isMyTurn && !Thread.interrupted() && Mode.currMode === ModeEnum.GAMEPLAY) {
                var toList = war.rival.playArea.cards.toList()
                for (card in toList) {
                    if (random.nextInt() and 1 == 1) {
                        card.action.lClick()
                        log.info { "点击敌方战场卡牌：${card}" }
                    }
                    SystemUtil.delay(minTime, maxTime)
                }
                SystemUtil.delay(minTime, maxTime)
                if (random.nextInt() and 1 == 1) {
                    war.rival.playArea.hero?.action?.lClick()
                    log.info { "点击敌方英雄" }
                }
                SystemUtil.delay(minTime, maxTime)
                if (random.nextInt() and 1 == 1) {
                    war.rival.playArea.power?.action?.lClick()
                    log.info { "点击敌方英雄技能" }
                }
                SystemUtil.delay(minTime, maxTime)
                toList = war.me.playArea.cards.toList()
                for (card in toList) {
                    if (random.nextInt() and 1 == 1) {
                        card.action.lClick()
                        log.info { "点击我方战场卡牌：${card}" }
                    }
                    SystemUtil.delay(minTime, maxTime)
                }
                SystemUtil.delay(minTime, maxTime)
            }
        }
    }

    /**
     * 执行起手换牌。
     *
     * 传给策略的是手牌集合副本，策略通过“从集合删除”表达需要换掉的卡。硬币永远不参与
     * 换牌。最终的多次确认点击用于容忍动画、丢帧或第一次点击未被客户端接收。
     */
    fun changeCard() {
        if (!canExec()) return

//        等待动画结束，畸变模式会导致开局动画增加
        SystemUtil.delay(20000 + (if (ConfigUtil.getBoolean(ConfigEnum.DISTORTION)) 4500 else 0))
        if (PauseStatus.isPause) return
        log.info { "执行换牌策略" }
        war.run {
            log.info { "1号玩家牌库数量：" + player1.deckArea.cards.size }
            log.info { "2号玩家牌库数量：" + player2.deckArea.cards.size }
        }

        val me = war.me
        try {
            val copyHandCards = HashSet(me.handArea.cards)
            copyHandCards.removeIf { it.cardId == COIN_CARD_ID }

            DeckStrategyManager.currentDeckStrategy?.executeChangeCard(copyHandCards)
            for (i in me.handArea.cards.indices) {
                val card = me.handArea.cards[i]
                if (card.cardId == COIN_CARD_ID) continue
                if (!copyHandCards.contains(card)) {
                    log.info { "换掉起始卡牌：【entityId:" + card.entityId + "，entityName:" + card.entityName + "，cardId:" + card.cardId + "】" }
                    GameUtil.chooseDiscoverCard(i, me.handArea.cardSize())
                    SystemUtil.delayShortMedium()
                }
            }
            log.info { "执行换牌策略完毕" }
            checkSurrender()
        } finally {
            for (i in 0..2) {
                GameUtil.CONFIRM_RECT.lClick(false)
                SystemUtil.delayShort()
            }
            GameUtil.CENTER_RECT.lClick(false)
        }
    }

    /**
     * 执行我方回合策略并负责回合收尾。
     *
     * 在进入策略前应用配置型回合投降上限；策略完成或抛出异常后都会取消悬挂动作并反复
     * 尝试点击结束回合，防止目标选择框、发现遮罩等 UI 状态阻塞后续日志阶段。
     */
    fun outCard() {
        if (!canExec()) return


        if (Mode.currMode !== ModeEnum.GAMEPLAY) {
            log.warn { "没有处于${ModeEnum.GAMEPLAY.comment}，但试图执行出牌方法，如脚本运行不正常请提交issue并附带游戏日志【${PowerLogListener.logFile?.path()}】" }
        }

        val surrenderNumber = ConfigUtil.getInt(ConfigEnum.OVER_TURN_SURRENDER)

        if (surrenderNumber >= 0 && war.me.turn >= surrenderNumber) {
            log.info { "到达投降回合-[${surrenderNumber}]" }
            GameUtil.surrender()
            return
        }

        // 等待动画结束
        SystemUtil.delay(5000)
        if (!war.isMyTurn || PauseStatus.isPause) return

        log.info { "执行出牌策略" }

        try {
            war.me.safeRun {
                log.info { "回合开始可用水晶数：" + it.usableResource }
            }
            DeckStrategyManager.currentDeckStrategy?.executeOutCard()
            log.info { "执行出牌策略完毕" }
            checkSurrender()
        } finally {
            GameUtil.cancelAction()
            for (i in 0 until 20) {
                if (!war.isMyTurn) break
                if (i > 3) {
                    GameUtil.getThreeDiscoverCardRect(0).lClick()
                    SystemUtil.delayShortMedium()
                }
                GameUtil.lClickTurnOver(false)
                SystemUtil.delayShortMedium()
            }
        }
    }

    /**
     * 执行发现选择。策略返回值会被限制到有效下标；异常或未给出选择时默认第一张。
     * 选择后再次检查投降请求，使策略可在发现决策中终止本局。
     */
    fun discoverChooseCard(cards: List<Card>) {
        if (!canExec()) return

        log.info { "执行发现选牌策略" }

        SystemUtil.delayMedium()
        var index = -1
        try {
            index = (DeckStrategyManager.currentDeckStrategy?.executeDiscoverChooseCard(*cards.toTypedArray())
                ?: 0).coerceIn(0, cards.size - 1)
        } catch (e: Exception) {
            log.error(e) { "执行发现选择策略异常" }
        } finally {
            if (index == -1) {
                index = 0
                GameUtil.chooseDiscoverCard(index, cards.size)
            }
        }
        val card = cards[index]
        war.me.let {
            GameUtil.chooseDiscoverCard(index, cards.size)
            SystemUtil.delayShort()
        }
        log.info { "执行发现选牌策略完毕，选择第${index + 1}张，${card}" }

        checkSurrender()
    }

    /** 执行时间线二选一；策略默认保持时间线，只有显式调用 `rewind()` 才回溯。 */
    fun chooseTimeLine(timeLineEvent: TimelineEvent) {
        if (!canExec()) return

        log.info { "执行时间线选择" }

        SystemUtil.delayMedium()
        try {
            DeckStrategyManager.currentDeckStrategy?.execChooseTimeLine(timeLineEvent)
        } catch (e: Exception) {
            log.error(e) { "执行时间线选择异常" }
        }

        if (timeLineEvent.isKeepTime()) GameUtil.keepTimeline() else {
            GameUtil.rewindTimeline()
            SystemUtil.delayHuge()
        }

        log.info { "执行时间线选择完毕，选择${timeLineEvent.chooseEventCard}" }

        checkSurrender()
    }

    /** 所有真实策略动作的统一门禁；检查本身可能消费一次投降请求。 */
    private fun canExec(): Boolean {
        return ConfigUtil.getBoolean(ConfigEnum.STRATEGY) && validPlayer() && !checkSurrender()
    }

    private fun validPlayer(): Boolean {
        if (!war.rival.isValid() && war.me.isValid()) {
            log.warn { "玩家无效" }
            return false
        }
        return true
    }

    /**
     * 原子式消费策略的 `needSurrender` 标记并异步点击投降。
     * 返回 `true` 表示本次调用已转为投降流程，调用方不应继续执行其他动作。
     */
    private fun checkSurrender(): Boolean {
        DeckStrategyManager.currentDeckStrategy?.let {
            if (it.needSurrender) {
                go {
                    log.info { "策略请求投降" }
                    GameUtil.surrender()
                }
                it.needSurrender = false
                return true
            }
        }
        return false
    }

}
