package club.xiaojiawei.hsscript

import club.xiaojiawei.hsscript.bean.CommonCardAction.Companion.DEFAULT
import club.xiaojiawei.hsscript.bean.Release
import club.xiaojiawei.hsscript.config.InitializerConfig
import club.xiaojiawei.hsscript.config.ShutdownHookConfig
import club.xiaojiawei.hsscript.consts.*
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.SoftProtectedModeEnum
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.listener.GlobalHotkeyListener
import club.xiaojiawei.hsscript.listener.StatisticsListener
import club.xiaojiawei.hsscript.listener.VersionListener
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscript.utils.*
import club.xiaojiawei.hsscript.utils.SystemUtil.addTray
import club.xiaojiawei.hsscript.utils.SystemUtil.shutdownSoft
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.config.submitExtra
import club.xiaojiawei.hsscriptbase.const.BuildInfo
import club.xiaojiawei.hsscriptbase.const.SoftRunMode
import club.xiaojiawei.hsscriptbase.util.isFalse
import club.xiaojiawei.hsscriptbase.util.isTrue
import club.xiaojiawei.hsscriptcardsdk.CardAction
import com.sun.jna.Memory
import com.sun.jna.WString
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.stage.Screen
import javafx.stage.Stage
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.awt.MenuItem
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.io.File
import java.net.URLClassLoader
import java.util.Locale.getDefault
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.prefs.Preferences
import javax.swing.AbstractAction
import kotlin.system.exitProcess

/**
 * JavaFX 应用生命周期入口，同时负责把 UI 生命周期与脚本后台服务连接起来。
 *
 * 主要阶段：
 * 1. [start] 处理只打开指定窗口的轻量参数，否则执行完整初始化链；
 * 2. [preInit] 安装默认卡牌动作、后台服务、卡牌组和退出钩子；
 * 3. `InitializerConfig` 初始化路径、日志、插件、驱动等基础设施；
 * 4. [showMainPage] 展示主窗口；窗口首次可见后由 [afterShowing] 完成托盘、
 *    系统检查、命令行语义和目录保护。
 *
 * JavaFX 被设置为关闭最后一个窗口时不退出，真正退出由托盘菜单或关闭钩子管理。
 * 耗时操作应提交到后台线程，所有 Stage 操作都应通过 `runUI` 回到 FX 线程。
 *
 * @author 肖嘉威
 * @date 2023/7/6 9:46
 */
class MainApplication : Application() {
    private var stageShowingListener: ChangeListener<Boolean?>? = null

    /** 开发辅助入口：动态编译并反射调用外部 Java 文件，不参与正常启动流程。 */
    fun testJava() {
        try {
            val classManager = DynamicClassManager()

            // 加载外部Java文件
            val testUtilClass =
                classManager.loadJavaFile("S:\\IdeaProjects\\fs32\\src\\main\\java\\com\\fs\\TestUtil1.java")
            println("成功加载类：${testUtilClass.name}")

            // 创建实例
            val testUtil = classManager.instantiate(testUtilClass)
            println("成功创建示例： $testUtil")

            // 调用方法 (使用反射)
            val getWarMethod = testUtilClass.getMethod("getWar")
            val result = getWarMethod.invoke(testUtil)
            println("调用getWar方法结果： $result")
        } catch (e: Exception) {
            println("发生错误: ${e.message}")
            e.printStackTrace()
        }
    }

    /** 开发辅助入口：动态加载单个外部 Kotlin 文件，不参与正常启动流程。 */
    fun testKt() {
        try {
            val manager = DynamicClassManager()

            // 动态加载外部 Kotlin 文件
            val loadedClass = manager.loadKotlinFile("S:\\IdeaProjects\\fs32\\src\\main\\java\\com\\fs\\TestUtil.kt")

            // 创建实例
            val instance = loadedClass.getDeclaredConstructor().newInstance()

            // 调用方法 sayHello()
            val method = loadedClass.getMethod("getWar")
            method.invoke(instance)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 将给定 Kotlin 文件编译到系统临时目录，并反射调用第一个文件对应类的 `getWar`。
     * 该方法用于开发期实验，调用方必须自行提供完整 classpath；编译产物不进入正式插件体系。
     */
    fun compileAndRunExternalKtFiles(
        filePaths: List<String>,
        classpath: String,
    ) {
        val ktFiles =
            filePaths.map { File(it) }.filter {
                val res = it.exists()
                res.isFalse {
                    println(it.absolutePath + "不存在")
                }
                res
            }
        if (ktFiles.isEmpty()) return

        val tempDir = File(System.getProperty("java.io.tmpdir"), "kotlin-temp")
        tempDir.mkdirs()

        val classDir = File(tempDir, "classes")
        classDir.mkdirs()

        val compiler = K2JVMCompiler()
        val args = mutableListOf<String>()
        args.addAll(ktFiles.map { it.absolutePath }) // 添加所有文件路径
        args.addAll(
            arrayOf(
                "-d",
                classDir.absolutePath,
                "-classpath",
                classpath,
            ),
        )

        val exitCode = compiler.exec(System.out, *args.toTypedArray())
        if (exitCode.code != 0) {
            println("Compilation failed")
            return
        }

        val classLoader = URLClassLoader(arrayOf(classDir.toURI().toURL()))
        val className =
            ktFiles.first().nameWithoutExtension.replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() } // 使用第一个文件的类名
        try {
            val tempClass = classLoader.loadClass(className)
            val newInstance = tempClass.getDeclaredConstructor().newInstance()
            val mainMethod = tempClass.getDeclaredMethod("getWar")
            println(mainMethod.invoke(newInstance))
        } catch (e: ClassNotFoundException) {
            println("Class not found: $className")
        } catch (e: NoSuchMethodException) {
            println("Main method not found in class: $className")
        } catch (e: Exception) {
            println("Error running compiled code: ${e.message}")
        }
    }

    /**
     * JavaFX 回调入口。
     *
     * `--window=` 用于复用已启动进程只展示某个窗口，此时会跳过完整初始化。
     * 正常启动则先装配全局服务，再运行 initializer 责任链。即使初始化异常，仍会
     * 尝试展示主界面，以便用户查看日志或进入设置修复路径配置。
     */
    override fun start(stage: Stage?) {
        runCatching {
            for (string in ScriptStatus.programArgs) {
                if (string.startsWith("--window=")) {
                    val windowEnum = WindowEnum.fromString(string.split("=")[1]) ?: break
                    WindowUtil.showStage(windowEnum)
                    return
                }
            }
            preInit()
            InitializerConfig.initializer.init()
        }.onFailure {
            log.error { it }
        }
        showMainPage()
//        testJava()
//        testKt()
//        compileAndRunExternalKtFiles(listOf("S:\\IdeaProjects\\fs32\\src\\main\\java\\com\\fs\\TestUtil.kt"), System.getProperty("java.class.path"))
    }

    /**
     * 安装所有 initializer 之前就必须可用的进程级依赖。
     *
     * 默认 CardAction 工厂会被 SDK 的每个卡牌动作间接使用；服务监听器通过 lazy
     * 属性注册；卡牌组需要先进入全局 Trie；退出钩子最后注册以接管资源清理。
     */
    private fun preInit() {
        CardAction.commonActionFactory = Supplier { DEFAULT.createNewInstance() }
        Platform.setImplicitExit(false)
        launchService()
        CardGroupUtil.applyEnabledCardGroups()
        ShutdownHookConfig.init
    }

    /**
     * 根据 `--page=` 参数展示指定页面，或创建主窗口。
     *
     * 主窗口的后置初始化只在第一次真正显示时执行，监听器随后立即移除，防止窗口
     * 隐藏/恢复时重复创建托盘、重复检查参数或重复设置目录保护。
     */
    private fun showMainPage() {
        if (ScriptStatus.programArgs.stream().anyMatch {
                if (it.startsWith(ARG_PAGE)) {
                    WindowEnum.fromString(it.removePrefix(ARG_PAGE).uppercase())?.let { windowEnum ->
                        WindowUtil.showStage(windowEnum)
                        WindowUtil.hideLaunchPage()
                        return@anyMatch true
                    }
                }
                return@anyMatch false
            }
        ) {
            return
        }
        val stage = WindowUtil.buildStage(WindowEnum.MAIN)
        stageShowingListener =
            ChangeListener { _, aBoolean: Boolean?, t1: Boolean? ->
                if (t1 != null && t1) {
                    stage.showingProperty().removeListener(stageShowingListener)
                    stageShowingListener = null
                    afterShowing()
                }
            }
        stage.showingProperty().addListener(stageShowingListener)
        stage.show()
    }

    @Deprecated("")
    private fun setSystemTray() {
        val isPauseItem = MenuItem("开始")
        isPauseItem.addActionListener(
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    PauseStatus.asyncSetPause(!PauseStatus.isPause)
                }
            },
        )
        PauseStatus.addChangeListener { observableValue: ObservableValue<out Boolean?>?, aBoolean: Boolean?, isPause: Boolean ->
            if (isPause) {
                isPauseItem.label = "开始"
            } else {
                isPauseItem.label = "暂停"
            }
        }

        val settingsItem = MenuItem("设置")
        settingsItem.addActionListener(
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    WindowUtil.showStage(WindowEnum.SETTINGS, WindowUtil.getStage(WindowEnum.MAIN))
                }
            },
        )

        val quitItem = MenuItem("退出")
        quitItem.addActionListener(
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    shutdownSoft()
                }
            },
        )

        addTray(
            Consumer { e: MouseEvent? ->
//            左键点击
                if (e?.button == 1) {
                    (WindowUtil.getStage(WindowEnum.MAIN)?.isShowing ?: false)
                        .isTrue {
                            WindowUtil.hideAllStage(true)
                        }.isFalse {
                            WindowUtil.showStage(WindowEnum.MAIN)
                        }
                }
            },
            isPauseItem,
            settingsItem,
            quitItem,
        )
    }

    private var trayItemArr: CSystemDll.TrayItem.Reference? = null

    private val initTrayMenu: CSystemDll.TrayMenu.Reference by lazy {
        val textMemorySize = 50L
        val iconPathMemorySize = 255L
        CSystemDll.TrayMenu.Reference().apply {
            text =
                Memory(textMemorySize).apply {
                    setWideString(0, PROGRAM_NAME)
                }
            iconPath = WString(SystemUtil.getTrayIconFile().absolutePath)
            clickCallback =
                object : CSystemDll.TrayCallback {
                    override fun invoke() {
                        val mainStage = WindowUtil.getStage(WindowEnum.MAIN)
                        if (mainStage == null || !mainStage.isShowing || mainStage.isIconified) {
                            WindowUtil.showStage(WindowEnum.MAIN)
                        } else {
                            WindowUtil.hideAllStage()
                        }
                    }
                }
            itemCount = 5
            trayItem = CSystemDll.TrayItem.Reference()
            trayItemArr = trayItem
            val trayItemArr = trayItem!!.toArray(itemCount) as Array<CSystemDll.TrayItem>
            trayItemArr[0].apply {
                id = 1000
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "开始")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_START_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            PauseStatus.asyncSetPause(!PauseStatus.isPause)
                        }
                    }
            }
            trayItemArr[1].apply {
                id = 1001
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "设置")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_SETTINGS_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            WindowUtil.showStage(WindowEnum.SETTINGS, WindowUtil.getStage(WindowEnum.MAIN))
                        }
                    }
            }
            trayItemArr[2].apply {
                id = 1002
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "统计")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_STATISTICS_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            WindowUtil.showStage(WindowEnum.STATISTICS, WindowUtil.getStage(WindowEnum.MAIN))
                        }
                    }
            }
            trayItemArr[3].apply {
                id = 1003
                type = CSystemDll.MF_SEPARATOR
            }
            trayItemArr[4].apply {
                id = 1004
                type = CSystemDll.MF_STRING
                text =
                    Memory(textMemorySize).apply {
                        setWideString(0, "退出")
                    }
                iconPath =
                    Memory(iconPathMemorySize).apply {
                        setWideString(0, SystemUtil.getResourcesImgFile(TRAY_EXIT_IMG_NAME).absolutePath)
                    }
                callback =
                    object : CSystemDll.TrayCallback {
                        override fun invoke() {
                            shutdownSoft()
                        }
                    }
            }
            PauseStatus.addChangeListener { _, _, isPause: Boolean ->
                if (isPause) {
                    trayItemArr[0].text?.setWideString(0, "开始")
                    trayItemArr[0].iconPath?.setWideString(
                        0,
                        SystemUtil.getResourcesImgFile(TRAY_START_IMG_NAME).absolutePath,
                    )
                } else {
                    trayItemArr[0].text?.setWideString(0, "暂停")
                    trayItemArr[0].iconPath?.setWideString(
                        0,
                        SystemUtil.getResourcesImgFile(TRAY_PAUSE_IMG_NAME).absolutePath,
                    )
                }
            }
            CSystemDll.INSTANCE.addSystemTray(this).isFalse {
                log.warn { "系统托盘创建失败" }
            }
        }
    }

    /**
     * 触发全局后台服务的 lazy 初始化。
     *
     * 这些对象大多通过属性访问完成监听器注册；热键监听可能阻塞，因此单独放入后台线程。
     */
    private fun launchService() {
        TaskManager.launch
        Core.launch
        go { GlobalHotkeyListener.launch }
        VersionListener.launch
        WorkTimeListener.launch
        StatisticsListener.launch
    }

    private var aoting = false

    /**
     * 解释需要等主窗口可见后才能处理的命令行参数。
     *
     * `--pause=false` 会自动开始脚本；否则根据版本和首次使用标记展示关于/更新页面。
     * AOT 参数只记录构建状态，实际退出时机由 [afterShowing] 统一处理。
     */
    private fun checkArg() {
        val args = this.parameters.raw
        var pause: String? = ""
        for (arg in args) {
            if (arg.startsWith(ARG_AOT)) {
                aoting = true
            } else if (arg.startsWith(ARG_PAUSE)) {
                val split: Array<String?> = arg.split("=".toRegex(), limit = 2).toTypedArray()
                if (split.size > 1) {
                    pause = split[1]
                }
            }
        }
        if ("false" == pause) {
            log.info { "接收到开始参数，开始脚本" }
            Thread.sleep(1000)
            PauseStatus.isPause = false
        } else {
            val preferences = Preferences.userNodeForPackage(this::class.java)
            val key = "used"
            val version = ConfigUtil.getString(ConfigEnum.CURRENT_VERSION)
            if (Release.compareVersion(BuildInfo.VERSION, version) > 0) {
                runUI {
                    WindowUtil.showStage(WindowEnum.ABOUT)
                    WindowUtil.showStage(WindowEnum.VERSION_MSG, WindowUtil.getStage(WindowEnum.MAIN))
                    ConfigUtil.putString(ConfigEnum.CURRENT_VERSION, BuildInfo.VERSION)
                }
            } else {
                if (preferences.get(key, "").isNullOrBlank()) {
                    WindowUtil.showStage(WindowEnum.ABOUT)
                }
            }
            preferences.put(key, "true")
        }
    }

    /**
     * 检查管理员权限、多显示器和 JVM AOT 缓存前置条件。
     *
     * 检查失败通常只降级或提示，不直接终止应用；这样用户仍可进入设置页面调整环境。
     */
    private fun checkSystem() {
        CSystemDll.INSTANCE.isRunAsAdministrator().isFalse {
            val text = "当前进程不是以管理员启动，功能可能受限"
            log.warn { text }
            SystemUtil.notice(text)
        }

        Screen.getScreens()?.let {
            if (it.size > 1) {
                log.info { "检测到多台显示器，开始运行后${GAME_CN_NAME}窗口不要移动到其他显示器" }
            }
        }
        if (!ConfigUtil.getBoolean(ConfigEnum.INIT_CREATE_AOT_CACHE) && BuildInfo.SOFT_RUN_MODE === SoftRunMode.JAR && !File(
                AOT_FILE_PATH
            ).exists()
        ) {
            runUI {
                AOTUtil.buildAOTAlert().getOrNull()?.show()
            }
            ConfigUtil.putBoolean(ConfigEnum.INIT_CREATE_AOT_CACHE, true)
        }
    }

    /**
     * 主窗口首次显示后的后台收尾阶段。
     *
     * 这里创建系统托盘、应用目录保护模式并关闭启动页。AOT 采集模式会短暂打开设置页
     * 后自动退出；普通模式则保持 JavaFX 和后台监听器持续运行。
     */
    private fun afterShowing() {
        submitExtra {
            initTrayMenu
            checkSystem()
            checkArg()
            val softProtectedMode = ConfigExUtil.getSoftProtectedMode()
            log.info { "软件保护模式: ${softProtectedMode.comment}" }
            when (softProtectedMode) {
                SoftProtectedModeEnum.NORMAL -> {
                    CSystemDll.INSTANCE.protectDirectory(PROTECT_PATH, false)
                }

                SoftProtectedModeEnum.STRONG -> {
                    CSystemDll.INSTANCE.protectDirectory(PROTECT_PATH, true)
                }

                SoftProtectedModeEnum.NONE -> {
                    CSystemDll.INSTANCE.unprotectDirectory(PROTECT_PATH)
                }
            }
            go {
                Thread.sleep(1000)
                WindowUtil.hideLaunchPage()
            }
            if (aoting) {
                runUI {
                    WindowUtil.showStage(WindowEnum.SETTINGS, WindowUtil.getStage(WindowEnum.MAIN))
                }
                go {
                    Thread.sleep(5000)
                    exitProcess(0)
                }
            }
        }
    }
}
