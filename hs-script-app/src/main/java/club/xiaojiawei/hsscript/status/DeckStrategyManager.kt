package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PluginManager.DECK_STRATEGY_PLUGINS
import club.xiaojiawei.hsscript.status.PluginManager.loadDeckProperty
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptpluginsdk.bean.PluginWrapper
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import java.util.stream.Stream

/**
 * 已启用 DeckStrategy 的运行时目录和当前选择状态。
 *
 * 插件加载完成后从 [PluginManager.DECK_STRATEGY_PLUGINS] 收集有效策略，并把插件 id
 * 回填到每个策略，供 CardAction 作用域查找。用户选择保存在 JavaFX Property 中，变化时
 * 同步持久化默认策略并通知用户。
 *
 * 当“工作时间规则高优先级”开启时，[currentDeckStrategy] 与 [currentRunMode] 的 getter
 * 会动态返回最近规则的覆盖值，而不会改写用户选择 Property；因此 UI 默认值与当前实际
 * 执行值可能不同，这是有意的优先级设计。
 *
 * @author 肖嘉威
 * @date 2024/9/7 15:17
 */
object DeckStrategyManager {

    /**
     * 当前卡组策略
     */
    val currentDeckStrategyProperty: ObjectProperty<DeckStrategy?> = SimpleObjectProperty()
    val currentRunModeProperty: ObjectProperty<RunModeEnum?> = SimpleObjectProperty()

    var currentDeckStrategy
        set(value) = currentDeckStrategyProperty.set(value)
        get():DeckStrategy? {
            if (ConfigUtil.getBoolean(ConfigEnum.WORK_TIME_RULE_HIGH_PRIORITY)) {
                return WorkTimeListener.closestWorkTimeRule?.strategyId?.let { strategyId ->
                    deckStrategies.find { it.id() == strategyId }
                }
            }
            return currentDeckStrategyProperty.get()
        }

    var currentRunMode
        set(value) = currentRunModeProperty.set(value)
        get():RunModeEnum? {
            if (ConfigUtil.getBoolean(ConfigEnum.WORK_TIME_RULE_HIGH_PRIORITY)) {
                return WorkTimeListener.closestWorkTimeRule?.runMode
            }
            return currentRunModeProperty.get()
        }

    /**
     * 所有卡组策略
     */
    val deckStrategies: ObservableSet<DeckStrategy> = FXCollections.observableSet()

    init {
        currentDeckStrategyProperty.addListener { _: ObservableValue<out DeckStrategy?>?, _: DeckStrategy?, newStrategy: DeckStrategy? ->
            if (newStrategy == null) {
                ConfigUtil.putString(ConfigEnum.DEFAULT_DECK_STRATEGY, "")
            } else if (ConfigUtil.getString(ConfigEnum.DEFAULT_DECK_STRATEGY) != newStrategy.id()
            ) {
                ConfigUtil.putString(ConfigEnum.DEFAULT_DECK_STRATEGY, newStrategy.id())
                val text = "挂机策略改为: ${newStrategy.name()}，模式: ${currentRunMode?.comment}"
                SystemUtil.notice(text)
                log.info { text }
                if (newStrategy.deckCode().isNotBlank()) {
                    log.info { "$" + newStrategy.deckCode() }
                }
            }
        }

        loadDeckProperty().addListener { _: ObservableValue<out Boolean>?, _: Boolean?, t1: Boolean ->
            if (t1) {
                reload()
            }
        }
    }

    /**
     * 展平启用插件中的策略实例，并过滤缺少名称、id 或运行模式的无效实现。
     * 每个 PluginWrapper 只注册一次 enabled 监听器，避免多次 reload 造成监听器泄漏。
     */
    private fun load(): List<DeckStrategy> {
        return DECK_STRATEGY_PLUGINS.values.stream()
            .flatMap { list: List<PluginWrapper<DeckStrategy>> -> list.stream() }
            .flatMap { deckPluginWrapper: PluginWrapper<DeckStrategy> ->
                if (!deckPluginWrapper.isListen) {
                    deckPluginWrapper.addEnabledListener { _: ObservableValue<out Boolean?>?, _: Boolean?, _: Boolean? ->
                        reload()
                    }
                }
                if (deckPluginWrapper.isEnabled()) deckPluginWrapper.spiInstance.stream()
                    .filter { deckStrategy: DeckStrategy ->
                        deckStrategy.pluginId = deckPluginWrapper.plugin.id()
                        deckStrategy.name().isNotBlank() && deckStrategy.id()
                            .isNotBlank() && deckStrategy.runModes.isNotEmpty()
                    } else Stream.empty()
            }.toList()
    }

    /** 以新加载结果整体刷新可观察集合，供设置页和运行逻辑同时收到变化。 */
    private fun reload() {
        log.info { "刷新策略库" }
        deckStrategies.clear()
        deckStrategies.addAll(load())
    }

}
