package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.bean.Release
import club.xiaojiawei.hsscript.consts.PLUGIN_PATH
import club.xiaojiawei.hsscript.utils.ClassLoaderUtil
import club.xiaojiawei.hsscript.utils.ConfigExUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.CardPlugin
import club.xiaojiawei.hsscriptpluginsdk.Plugin
import club.xiaojiawei.hsscriptpluginsdk.bean.PluginWrapper
import club.xiaojiawei.hsscriptpluginsdk.config.PluginScope
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import java.io.File
import java.util.*
import java.util.stream.StreamSupport


/**
 * CardAction 与 DeckStrategy 插件的发现、去重和作用域注册中心。
 *
 * 加载分为内置 classpath SPI 与 `plugin` 目录外部 ClassLoader 两部分。每个 Plugin 元数据
 * 与同包/同 ClassLoader 下的 SPI 实例组合成 `PluginWrapper`，随后按插件 id 只保留最高版本。
 * 最终 Map 的 key 不是始终等于插件自身 id：CardPlugin 会根据 [PluginScope] 映射到公共、
 * 私有或指定策略作用域；StrategyPlugin 则只映射到自身 id。
 *
 * [loadCard]、[loadDeck] 是“本轮加载完成”信号，Manager 监听它们重建运行时索引。
 * 插件开关只影响实例是否进入索引，不卸载对应 ClassLoader。
 *
 * @author 肖嘉威
 * @date 2024/9/7 15:05
 */
object PluginManager {

    private data class PendingPlugin<T>(
        val pluginWrapper: PluginWrapper<T>,
        val targetIds: List<String>,
    )

    /**
     * key：pluginId
     */
    val CARD_ACTION_PLUGINS: MutableMap<String, MutableList<PluginWrapper<CardAction>>> = mutableMapOf()

    val DECK_STRATEGY_PLUGINS: MutableMap<String, MutableList<PluginWrapper<DeckStrategy>>> = mutableMapOf()

    private val loadDeck = ReadOnlyBooleanWrapper(false)

    private val loadCard = ReadOnlyBooleanWrapper(false)

    fun isLoadDeck(): Boolean {
        return loadDeck.get()
    }

    fun loadDeckProperty(): ReadOnlyBooleanProperty {
        return loadDeck.readOnlyProperty
    }

    fun isLoadCard(): Boolean {
        return loadCard.get()
    }

    fun loadCardProperty(): ReadOnlyBooleanProperty {
        return loadCard.readOnlyProperty
    }

    /** 先加载卡牌动作，再加载策略；保证策略索引建立时卡牌作用域已经可用。 */
    fun loadAllPlugins() {
        loadCardPlugin()
        loadDeckPlugin()
    }

    private fun loadDeckPlugin() {
        DeckStrategyManager
        loadDeck.set(false)
        loadPlugin(DeckStrategy::class.java, StrategyPlugin::class.java, DECK_STRATEGY_PLUGINS)
        loadDeck.set(true)
    }

    private fun loadCardPlugin() {
        CardActionManager
        loadCard.set(false)
        loadPlugin(CardAction::class.java, CardPlugin::class.java, CARD_ACTION_PLUGINS)
        loadCard.set(true)
    }

    private val pluginDir by lazy {
        File(PLUGIN_PATH).apply {
            if (!exists()) {
                log.info { "插件目录不存在：${toString()}" }
            }
        }
    }

    /**
     * 通用 SPI 加载流程。先收集候选项，不立即写目标 Map，以便跨内置/外部来源统一比较版本。
     */
    private fun <T, P : Plugin> loadPlugin(
        aClass: Class<T>,
        pluginClass: Class<P>,
        pluginWrapperMap: MutableMap<String, MutableList<PluginWrapper<T>>>
    ) {
        pluginWrapperMap.clear()
        val result = ClassLoaderUtil.getClassLoader(pluginDir)
        val pendingPlugins = mutableListOf<PendingPlugin<T>>()

        val deckClassLoaders = result.getOrDefault(emptyList())

        var pluginWrapper: PluginWrapper<T>
        val disableSet: MutableSet<String> =
            if (pluginClass == CardPlugin::class.java) {
                ConfigExUtil.getCardPluginDisabled()
            } else {
                ConfigExUtil.getDeckPluginDisabled()
            }.toMutableSet()
        disableSet.removeAll { it.trim().isEmpty() }

        //        加载内部spi
        val basePlugin = StreamSupport.stream(
            ServiceLoader.load(
                pluginClass,
                PluginManager::class.java.classLoader
            ).spliterator(), false
        ).toList()
        val innerAllInstance: List<T>?
        if (basePlugin.isEmpty()) {
            innerAllInstance = null
        } else {
            innerAllInstance = StreamSupport.stream(
                ServiceLoader.load(aClass, PluginManager::class.java.classLoader).spliterator(),
                false
            ).toList()
            for (p in basePlugin) {
                val isEnabled = !disableSet.contains(p.id())
                if (isEnabled) {
                    p.init()
                }
                val packageName = p::class.java.packageName
                pluginWrapper = PluginWrapper(p, innerAllInstance.filter {
                    it::class.java.packageName.startsWith(packageName)
                })
                pluginWrapper.setEnabled(isEnabled)
                pendingPlugins.add(PendingPlugin(pluginWrapper, resolveTargetIds(pluginWrapper.plugin)))
            }
        }

        //        加载外部spi
        for (deckClassLoader in deckClassLoaders) {
            try {
                val plugins = ArrayList(
                    StreamSupport.stream(
                        ServiceLoader.load(
                            pluginClass, deckClassLoader
                        ).spliterator(), false
                    ).toList()
                )
                if (plugins.isNotEmpty()) {
                    val plugin = plugins.last()
                    val isEnabled = !disableSet.contains(plugin.id())
                    if (isEnabled) {
                        plugin.init()
                    }

                    var stream = StreamSupport.stream(ServiceLoader.load(aClass, deckClassLoader).spliterator(), false)
                    innerAllInstance?.let {
                        stream = stream.filter { i: T ->
                            for (t in innerAllInstance) {
                                if (t!!::class.java.name == i!!::class.java.name) {
                                    return@filter false
                                }
                            }
                            true
                        }
                    }
                    val spiList = stream.toList()
                    if (spiList.isEmpty()) continue
                    pluginWrapper = PluginWrapper(plugin, spiList)
                    pluginWrapper.setEnabled(isEnabled)
                    pendingPlugins.add(PendingPlugin(pluginWrapper, resolveTargetIds(plugin)))
                }
            } catch (e: ServiceConfigurationError) {
                log.warn(e) { "加载SPI错误" }
            } catch (e: Error) {
                log.warn(e) { "加载插件错误" }
            } catch (e: Exception) {
                log.warn(e) { "加载插件错误" }
            }
        }

        applyHighestVersionPolicy(pendingPlugins, pluginWrapperMap, pluginClass.simpleName)
    }

    /** 按插件 id 比较版本，只注册每个 id 的最高版本候选；同一候选可映射到多个 targetId。 */
    private fun <T> applyHighestVersionPolicy(
        pendingPlugins: List<PendingPlugin<T>>,
        pluginWrapperMap: MutableMap<String, MutableList<PluginWrapper<T>>>,
        type: String,
    ) {
        val latestPluginMap = pendingPlugins
            .groupBy { it.pluginWrapper.plugin.id() }
            .mapValues { (_, plugins) ->
                plugins.maxWith { left, right ->
                    Release.compareVersion(
                        left.pluginWrapper.plugin.version(),
                        right.pluginWrapper.plugin.version(),
                    )
                }
            }
        for (pendingPlugin in pendingPlugins) {
            val pluginWrapper = pendingPlugin.pluginWrapper
            val plugin = pluginWrapper.plugin
            if (latestPluginMap[plugin.id()] !== pendingPlugin) {
                log.info {
                    "插件${plugin.name()}(${plugin.id()})版本${plugin.version()}不是最高版本，跳过加载"
                }
                continue
            }
            for (targetId in pendingPlugin.targetIds) {
                addPluginWrapper(pluginWrapper, pluginWrapperMap, targetId, type)
            }
        }
    }

    /** 把插件声明的 Scope 转成运行时索引 key。空字符串代表所有策略都可见的公共卡牌动作。 */
    private fun resolveTargetIds(plugin: Plugin): List<String> {
        return if (plugin is CardPlugin) {
            val pluginScope = plugin.pluginScope()
            if (pluginScope === PluginScope.PUBLIC) {
                listOf("")
            } else if (pluginScope === PluginScope.PROTECTED) {
                listOf(plugin.id())
            } else {
                pluginScope.toList()
            }
        } else if (plugin is StrategyPlugin) {
            listOf(plugin.id())
        } else {
            emptyList()
        }
    }

    private fun <T> addPluginWrapper(
        pluginWrapper: PluginWrapper<T>,
        pluginWrapperMap: MutableMap<String, MutableList<PluginWrapper<T>>>,
        pluginId: String, type: String
    ) {
        var pluginWrapperList = pluginWrapperMap[pluginId]
        if (pluginWrapperList == null) {
            pluginWrapperList = mutableListOf(pluginWrapper)
            pluginWrapperMap[pluginId] = pluginWrapperList
        } else {
            pluginWrapperList.add(pluginWrapper)
        }
        pluginWrapper.plugin.apply {
            log.info { "加载${type}：{name: ${name()}, version: ${version()}, author: ${author()}, id: ${id()}, description: ${description()}}" }
        }
    }

    private fun equalsPlugin(plugin1: Plugin?, plugin2: Plugin?): Boolean {
        if (plugin1 == null || plugin2 == null) {
            return false
        }
        return plugin1.id() == plugin2.id()
    }
}
