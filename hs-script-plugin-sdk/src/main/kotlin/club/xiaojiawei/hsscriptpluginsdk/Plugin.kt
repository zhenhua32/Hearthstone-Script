package club.xiaojiawei.hsscriptpluginsdk

import javafx.scene.layout.Pane

/**
 * 所有卡牌插件和策略插件共享的元数据/生命周期协议。
 *
 * 实现通过 Java ServiceLoader 注册。应用先读取 Plugin，再加载同一包或 ClassLoader 中的
 * CardAction/DeckStrategy 实例；相同 [id] 的候选只保留最高 [version]。因此 id 是升级与
 * 用户禁用配置的稳定主键，name 仅用于展示，不可互换。
 *
 * [cardSDKVersion] 与 [strategySDKVersion] 用于声明编译期兼容基线；未使用的 SDK 返回
 * `null`。图形描述会进入 JavaFX UI，插件应避免在 getter 中执行阻塞 I/O。
 *
 * @author 肖嘉威
 * @date 2024/9/8 16:37
 */
interface Plugin {
    /**
     * 图形化插件描述
     */
    fun graphicDescription(): Pane? = null

    /**
     * 插件描述
     */
    fun description(): String = ""

    /**
     * 插件作者
     */
    fun author(): String

    /**
     * 插件版本号
     */
    fun version(): String

    /**
     * 插件ID
     */
    fun id(): String

    /**
     * 插件名
     */
    fun name(): String

    /**
     * 插件主页链接
     */
    fun homeUrl(): String

    /**
     * 插件更新链接
     */
    fun updateUrl(): String = ""

    /**
     * 获取插件信息
     */
    fun getInfoString(): String =
        "name: ${name()}, version: ${version()}, author: ${author()}, id: ${id()}, description: ${description()}"

    /**
     * 使用的卡牌sdk版本
     * 如果没使用返回null
     */
    fun cardSDKVersion(): String?

    /**
     * 使用的策略sdk版本
     * 如果没使用返回null
     */
    fun strategySDKVersion(): String?

    /**
     * 初始化插件。仅当插件启用时、在 SPI 实例进入运行时索引之前调用。
     * 实现应可安全重复执行，因为插件重载可能再次创建或初始化实例。
     */
    fun init() {}

}
