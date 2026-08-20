package club.xiaojiawei.hsscript.bean.single

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.utils.getBoolean
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptbase.util.isTrue
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.area.*
import club.xiaojiawei.hsscriptcardsdk.bean.safeRun
import club.xiaojiawei.hsscriptcardsdk.cardparser.ParsedCardActionFactory
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import javafx.beans.property.BooleanProperty
import javafx.beans.property.IntegerProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import kotlin.math.min

/**
 * [WAR] 的应用层生命周期与统计扩展。
 *
 * SDK 中的 War 只描述一局战斗的可模拟状态；本对象额外负责真实对局的开始/结束、
 * JavaFX 统计属性、经验估算、胜负连胜，以及需要随对局清理的应用级缓存回调。
 * 所有改变对局边界的方法都使用 [Synchronized]，避免日志线程和统计/UI 线程同时重置模型。
 *
 * `reset -> startWar -> endWar` 是推荐调用顺序。结束后会清理动态生成的 CardAction 工厂缓存，
 * 防止上一局按 cardId 生成的动作持有旧 Card/War 引用。
 *
 * @author 肖嘉威
 * @date 2024/10/11 14:41
 */
object WarEx {

    /** 需要在 WAR 重建后重新初始化自身状态的扩展回调。 */
    private val resetCallbackList: MutableList<Runnable> = ArrayList()

    /** 需要在胜负、时长和经验统计完成后执行的扩展回调。 */
    private val endCallbackList: MutableList<Runnable> = ArrayList()

    val war = WAR

    /**
     * 已挂游戏局数
     */
    val warCountProperty: IntegerProperty = SimpleIntegerProperty(0)

    var warCount
        get() = warCountProperty.get()
        set(value) {
            warCountProperty.set(value)
            if (value > 0) {
                log.info {
                    "已完成第 $value 把游戏"
                }
            }
        }

    val inWarProperty: BooleanProperty = SimpleBooleanProperty(false)

    var inWar
        get() = inWarProperty.get()
        set(value) {
            inWarProperty.set(value)
        }

    /**
     * 本局是否胜利
     */
    var isWin = false

    /**
     * 本局获得经验
     */
    var aEXP = 0L

    /**
     * 已挂胜场
     */
    val winCountProperty: IntegerProperty = SimpleIntegerProperty(0)

    var winCount
        get() = winCountProperty.get()
        set(value) = winCountProperty.set(value)

    /**
     * 当前连胜
     */
    val winStreakProperty: IntegerProperty = SimpleIntegerProperty(0)

    var winStreak
        get() = winStreakProperty.get()
        set(value) = winStreakProperty.set(value)

    /**
     * 挂机时长，单位：min
     */
    val hangingTimeProperty: IntegerProperty = SimpleIntegerProperty(0)

    var hangingTime
        get() = hangingTimeProperty.get()
        set(value) = hangingTimeProperty.set(value)

    /**
     * 挂机获得的经验
     */
    val hangingEXPProperty: IntegerProperty = SimpleIntegerProperty(0)

    var hangingEXP
        get() = hangingEXPProperty.get()
        set(value) = hangingEXPProperty.set(value)


    /** 仅清空跨对局累计统计，不改变当前 WAR 实体和阶段。 */
    @Synchronized
    fun resetStatistics() {
        warCount = 0
        winCount = 0
        winStreak = 0
        hangingTime = 0
        hangingEXP = 0
    }

    /**
     * 重建一局对战的全部可变状态。
     *
     * Player 必须重新创建并绑定当前 War；不能复用上一局 Area，否则 Card 的归属关系会串局。
     * @param print 是否输出重置日志；[startWar] 内部调用时关闭，避免重复提示。
     */
    @Synchronized
    fun reset(print: Boolean = true) {
        war.run {
            firstPlayerGameId = ""
            currentPhase = WarPhaseEnum.FILL_DECK
            currentTurnStep = null
            rival = Player.UNKNOWN_PLAYER
            me = Player.UNKNOWN_PLAYER
            currentPlayer = Player.UNKNOWN_PLAYER
            player1 = Player(allowLog = true, playerId = "1", war = war).apply {
                handArea.parseCard = ConfigEnum.ANALYZE_CARD_DESCRIPTION.getBoolean()
            }
            player2 = Player(allowLog = true, playerId = "2", war = war).apply {
                handArea.parseCard = ConfigEnum.ANALYZE_CARD_DESCRIPTION.getBoolean()
            }
            warTurn = 0
            conceded = ""
            lost = conceded
            won = lost
            endTime = 0
            startTime = endTime
            maxEntityId = null
            myHeroIncreaseInjury = 0
            rivalHeroIncreaseInjury = 0
        }
        isWin = false
        inWar = false
        aEXP = 0L
        print.isTrue {
            log.info { "已重置游戏状态" }
        }
        war.cardMap.clear()
        for (runnable in resetCallbackList) {
            runnable.run()
        }
        System.gc()
    }

    @Synchronized
    fun addResetCallback(runnable: Runnable) {
        resetCallbackList.add(runnable)
    }

    @Synchronized
    fun addEndCallback(runnable: Runnable) {
        endCallbackList.add(runnable)
    }

    /**
     * 建立新对局边界：先静默重置，再记录开始时间、运行模式并标记正在对局。
     */
    @Synchronized
    fun startWar(runModeEnum: RunModeEnum?) {
        log.info { "当前模式: ${DeckStrategyManager.currentRunMode?.comment}, 当前策略: ${DeckStrategyManager.currentDeckStrategy?.name()}" }
        reset(false)
        war.run {
            startTime = System.currentTimeMillis()
            currentRunMode = runModeEnum
        }
        inWar = true
    }

    /**
     * 完成对局统计并释放局内缓存。
     *
     * 经验值按模式、胜负和最多 30 分钟估算；它用于挂机统计，并不是服务端权威结算值。
     * 所有 end callback 在 `warCount` 增加前执行，回调可读取本局完整结果。
     */
    @Synchronized
    fun endWar() {
        inWar = false
        war.run {
            me.safeRun {
                isWin = printResult()
            }
            endTime = if (startTime == 0L) 0 else System.currentTimeMillis()
            val time = (endTime - startTime) / 1000 / 60
            log.info { "本局游戏时长：${time}分钟" }
            hangingTime += time.toInt()
            var winExp = 0
            var lostExp = 0
            when (currentRunMode) {
                RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.CLASSIC, RunModeEnum.TWIST -> {
                    winExp = 8
                    lostExp = 6
                }

                RunModeEnum.CASUAL, RunModeEnum.BACON -> {
                    winExp = 6
                    lostExp = 4
                }

                RunModeEnum.PRACTICE -> {
                }

                else -> {
                    log.info { "未知模式，增加经验值0" }
                }
            }
            aEXP = (min(time.toDouble(), 30.0) * (if (isWin) winExp else lostExp)).toLong()
            log.info { "本局游戏获得经验值：$aEXP" }
            hangingEXP += aEXP.toInt()
            for (runnable in endCallbackList) {
                runnable.run()
            }
            warCount++
        }
        ParsedCardActionFactory.clear()
    }

    private fun printResult(): Boolean {
        return war.run {
            var flag = false
            if (won == me.gameId) {
                winCount++
                winStreak++
                flag = true
            } else {
                winStreak = 0
            }
            log.info { "本局游戏胜者：$won" }
            log.info { "本局游戏败者：$lost" }
            log.info { "本局游戏投降者：$conceded" }
            flag
        }
    }

    /** 按日志中的 playerId 获取本局玩家，无法匹配时返回哨兵对象而不是 `null`。 */
    @Synchronized
    fun getPlayer(playerId: String): Player {
        return war.run {
            when (playerId) {
                player1.playerId -> player1
                player2.playerId -> player2
                else -> Player.UNKNOWN_PLAYER
            }
        }
    }

    /** 按游戏显示 ID 获取玩家，无法匹配时返回 [Player.UNKNOWN_PLAYER]。 */
    @Synchronized
    fun getPlayerByGameId(gameId: String): Player {
        return war.run {
            when (gameId) {
                player1.gameId -> player1
                player2.gameId -> player2
                else -> Player.UNKNOWN_PLAYER
            }
        }
    }

    /**
     * 返回同类型的对手区域，用于把一个 Area 上的效果映射到另一名玩家。
     * 不属于 player1/player2 或尚未支持的 Area 类型返回 `null`。
     */
    @Synchronized
    fun getReverseArea(area: Area): Area? {
        return war.run {
            when (area.player) {
                player1 -> player2
                player2 -> player1
                else -> null
            }
        }?.let {
            when (area) {
                is PlayArea -> {
                    return it.playArea
                }

                is HandArea -> {
                    return it.handArea
                }

                is DeckArea -> {
                    return it.deckArea
                }

                is GraveyardArea -> {
                    return it.graveyardArea
                }

                is RemovedfromgameArea -> {
                    return it.removedfromgameArea
                }

                is SecretArea -> {
                    return it.secretArea
                }

                is SetasideArea -> {
                    return it.setasideArea
                }

                else -> {
                    return null
                }
            }
        }
    }

}
