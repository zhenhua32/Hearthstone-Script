package club.xiaojiawei.hsscriptstrategysdk

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import java.util.*

/**
 * 卡组策略插件的稳定运行时契约。
 *
 * 实现只负责做决策，不应直接管理日志监听或全局插件生命周期。应用层在换牌、我方回合、
 * 发现和时间线事件到来时调用相应方法，并负责异常兜底、点击确认和结束回合。
 *
 * 策略实例会跨多局复用，[reset] 必须清空所有局内可变状态。`name/id/runModes` 为空的实现
 * 会被 Manager 过滤；[id] 同时用于持久化选择和 equals/hashCode，发布后应保持稳定。
 *
 * @author 肖嘉威
 * @date 2024/9/9 0:33
 */
abstract class DeckStrategy {
    /** 由 PluginManager 注入的所属插件 id，用于解析该策略可见的 CardAction 作用域。 */
    var pluginId: String = ""

    /** 运行模式只计算一次；实现的 [getRunMode] 应返回稳定且非空的数组。 */
    val runModes: Array<RunModeEnum> by lazy { getRunMode() }

    /**
     * 是否需要投降，改为true时软件会在适合的时机投降
     */
    var needSurrender = false

    /**
     * 每局游戏开始时调用此方法
     */
    open fun reset() {
        needSurrender = false
    }

    /**
     * 套牌策略名，将会显示在界面中
     * @return 非空
     */
    abstract fun name(): String

    /**
     * 策略描述
     */
    open fun description(): String = ""

    /**
     * 策略运行的模式
     * @return 返回非null非空且不包含null,推荐每次返回的数组对象是一样的
     */
    protected abstract fun getRunMode(): Array<RunModeEnum>

    /**
     * 卡组代码
     * @return 非空
     */
    abstract fun deckCode(): String

    /**
     * 套牌策略唯一标识
     * @return 非空
     */
    abstract fun id(): String

    /**
     * 参考权重
     */
    open fun referWeight(): Boolean = false

    /**
     * 参考使用权重
     */
    open fun referPowerWeight(): Boolean = false

    /**
     * 参考换牌权重
     */
    open fun referChangeWeight(): Boolean = false

    /**
     * 参考卡牌信息
     */
    open fun referCardInfo(): Boolean = false

    /**
     * 执行换牌策略。
     * @param cards 起始手牌的可变副本。保留在集合中的卡不会换；需要换掉的牌必须从集合删除。
     */
    abstract fun executeChangeCard(cards: HashSet<Card>)

    /**
     * 执行出牌策略
     */
    abstract fun executeOutCard()

    /**
     * 执行发现选牌
     * @param cards 发现的牌
     * @return 需要选择的牌的下标，下标范围 [0,数组长度)
     */
    abstract fun executeDiscoverChooseCard(vararg cards: Card): Int

    /**
     * 执行选择时间线
     * @param timeLineEvent 时间线事件，保持或者回溯
     */
    open fun execChooseTimeLine(timeLineEvent: TimelineEvent) {}

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as DeckStrategy
        return id() == that.id()
    }

    override fun hashCode(): Int = Objects.hashCode(id())
}

/**
 * 时间线二选一的可变决策对象。
 * 默认选择保持时间线；策略通过 [rewind] 显式改为回溯。使用对象引用而不是 CardID 区分
 * 两个选项，确保同名或同 CardID 的临时实体仍能被准确选择。
 */
class TimelineEvent(private val rewindCard: Card, private val keepCard: Card) {
    var chooseEventCard = keepCard
        private set

    fun isKeepTime(): Boolean {
        return chooseEventCard === keepCard
    }

    /**
     * 维持时间线
     */
    fun keep() {
        chooseEventCard = keepCard
    }

    /**
     * 回溯时间线
     */
    fun rewind() {
        chooseEventCard = rewindCard
    }
}
