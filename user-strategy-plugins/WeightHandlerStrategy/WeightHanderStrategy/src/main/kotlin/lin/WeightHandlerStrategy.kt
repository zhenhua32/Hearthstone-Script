package lin


import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import lin.domain.ComboDomain


/**
 * 外部权重组合策略的 DeckStrategy 适配层。
 *
 * 该类保持轻量：所有换牌、出牌和发现计算都委托给一局一个状态视图的 [ComboDomain]。
 * 策略声明会读取普通权重、使用权重和换牌权重，因此主程序会为它加载对应 CardInfo。
 * 当全局换牌权重关闭时，使用“费用大于 2 就换掉”的保守降级规则。
 *
 * [ComboDomain] 直接引用全局 [WAR]，插件实例又会跨局复用，所以领域层必须依赖主程序的
 * WarEx reset/生命周期回调清理缓存，不能长期保存上一局 Card 对象。
 *
 * @see club.xiaojiawei.hsscriptcardsdk.bean.BaseCard
 * @see club.xiaojiawei.hsscriptcardsdk.bean.Player
 */
class WeightHandlerStrategy : DeckStrategy() {
    private val comboDomain: ComboDomain


    init {
        myLog.info{
            "执行策略初始化"
        }
        comboDomain = ComboDomain(WAR)


    }


    override fun name(): String = "权重处理策略"

    override fun description(): String = "基于战场计算权重的策略,例如在手牌对应种族就加权重,通过配置绑定到组,然后通过组id关联到权重表(CardWeight)的weight,依赖数据也是\n"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-1-weightHandler-deck-97e9-1f4e126cd33b"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    /**
     * [HsRadicalDeckStrategy]
     * 参考
     * [DeckStrategyUtil.convertToSimulateCard]
     */
    /** 按配置的组合权重筛选起手牌；集合中被删除的卡由主程序执行换牌点击。 */
    override fun executeChangeCard(cards: HashSet<Card>) {
        if (BaseData.enableChangeWeight) {
            comboDomain.executeChangeCard(cards)
        } else {
            cards.removeIf { card -> card.cost > 2 }
        }
    }



    /** 让 ComboDomain 在当前 WAR 快照上寻找并执行本回合的最佳组合。 */
    override fun executeOutCard() {
            comboDomain.outCardStrategy()


    }

    /**
     * 选择发现卡牌；单选或空候选直接返回首项，多候选交给权重领域计算。
     * 返回值必须是从 0 开始的下标，应用层还会执行边界收敛。
     */
    override fun executeDiscoverChooseCard(vararg cards: Card): Int  {

        if (cards.size < 2) return 0
          return   comboDomain.executeDiscoverChooseCard(*cards)
    }
}
