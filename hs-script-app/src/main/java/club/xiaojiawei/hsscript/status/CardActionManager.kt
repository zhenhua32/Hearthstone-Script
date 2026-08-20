package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptbase.bean.LikeTrie
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.status.PluginManager.CARD_ACTION_PLUGINS
import club.xiaojiawei.hsscript.status.PluginManager.loadCardProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import java.util.function.Supplier

/**
 * 把已启用的 CardAction 插件编译成按策略作用域查询的 CardID 索引。
 *
 * 外层 key 是策略 pluginId（空字符串表示公共作用域），内层 [LikeTrie] 同时支持精确
 * CardID 和 `%` 通配。索引保存工厂而不是 CardAction 单例，确保每张实体获得独立的
 * `belongCard`、执行标记和模拟深度。
 *
 * 插件加载完成或启用状态变化时会整体重建索引；读取方应始终从 [CARD_ACTION_MAP]
 * 获取最新 Trie，不缓存旧 Map 条目。
 *
 * @author 肖嘉威
 * @date 2024/9/7 16:23
 */
object CardActionManager {
    /**
     * key1：pluginId
     * key2：cardId
     */
    val CARD_ACTION_MAP: MutableMap<String, LikeTrie<()->CardAction>> = FXCollections.observableMap(load())

    init {
        loadCardProperty().addListener { _: ObservableValue<out Boolean>?, _: Boolean?, t1: Boolean ->
            if (t1) {
                reload()
            }
        }
    }

    /** 从 PluginManager 快照构建新 Map，构建完成后再由 [reload] 一次性替换内容。 */
    private fun load(): MutableMap<String, LikeTrie<()->CardAction>> {
        return CARD_ACTION_PLUGINS.mapValues { entry ->
            val likeTrie = LikeTrie<()->CardAction>()

            val list = entry.value.flatMap { pluginWrapper ->
                // 添加监听器，当状态变化时重新加载
                pluginWrapper.addEnabledListener { _: ObservableValue<out Boolean?>?, _: Boolean?, _: Boolean? ->
                    reload()
                }
                // 只保留启用的插件实例
                if (pluginWrapper.isEnabled()) pluginWrapper.spiInstance else emptyList()
            }
            for (cardAction in list) {
                // 将每个 CardAction 生成的 Supplier 添加到 LikeTrie
                val idArray = cardAction.getCardId()
                for (cardId in idArray) {
                    likeTrie[cardId] = { cardAction.createNewInstance() }
                }
            }

            likeTrie
        }.toMutableMap()
    }

    /** 响应插件加载/开关变化，重建全部作用域的 CardID Trie。 */
    private fun reload() {
        log.info { "刷新卡牌库" }
        CARD_ACTION_MAP.clear()
        CARD_ACTION_MAP.putAll(load())
    }
}
