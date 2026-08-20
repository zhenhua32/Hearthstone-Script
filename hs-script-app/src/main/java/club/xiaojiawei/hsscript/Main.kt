package club.xiaojiawei.hsscript

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.spi.FilterReply
import club.xiaojiawei.hsscript.consts.ARG_AOT
import club.xiaojiawei.hsscript.consts.PROGRAM_NAME
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.utils.WindowUtil
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinError
import javafx.application.Application
import org.slf4j.LoggerFactory
import java.io.File


/**
 * JVM 版桌面程序入口。
 *
 * 启动顺序必须保持为：配置 JNA 搜索路径 -> 判断 AOT 模式 -> 获取单实例锁 ->
 * 重载日志配置 -> 保存命令行参数 -> 启动 JavaFX。前几步都发生在 JavaFX
 * Application Thread 创建之前，因此这里不要直接访问 Stage 或其他 UI 对象。
 *
 * AOT 构建过程允许绕过单实例锁；普通运行时如果发现同名 Windows Mutex 已存在，
 * 会关闭启动页并立即返回，避免两个进程同时读写配置、日志和游戏窗口。
 *
 * @author 肖嘉威
 * @date 2024/10/14 17:42
 */
fun main(args: Array<String>) {
    System.setProperty("jna.library.path", "lib")
    ScriptStatus.aotMode = args.any { it.startsWith(ARG_AOT) }

    if (!createProgramLock() && !ScriptStatus.aotMode){
        WindowUtil.hideLaunchPage()
        return
    }

    setLogPath()

    ScriptStatus.programArgs = args.toList()

    Application.launch(MainApplication::class.java, *args)
}

/**
 * 从程序工作目录重新加载 logback.xml，并为文件 appender 安装运行时级别过滤器。
 *
 * 控制台日志级别和文件日志级别是两套配置；这里仅约束名为 `file_async` 下的
 * `file` appender。配置缺失或 appender 名称变化时方法会安全退出，异常只打印到
 * 标准错误，避免日志初始化失败阻止主界面启动。
 */
private fun setLogPath() {
    try {
        val context = LoggerFactory.getILoggerFactory()
        if (context is LoggerContext) {
            val logbackConfigFile = File("logback.xml")
            if (logbackConfigFile.exists()) {
                val configurator = JoranConfigurator()
                configurator.context = context
                context.reset()
                configurator.doConfigure(logbackConfigFile)
            }

            val appender = context.getLogger("ROOT").getAppender("file_async")
            if (appender is AsyncAppender) {
                for (iteratorForAppender in appender.iteratorForAppenders()) {
                    if (iteratorForAppender.name == "file") {
                        iteratorForAppender.addFilter(object : ThresholdFilter() {
                            override fun decide(iLoggingEvent: ILoggingEvent): FilterReply {
                                return if (iLoggingEvent.level.toInt() >= ScriptStatus.fileLogLevel) FilterReply.ACCEPT else FilterReply.DENY
                            }
                        })
                        break
                    }
                }
            }
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * 通过 Windows 命名 Mutex 实现进程级单实例保护。
 *
 * Mutex 句柄由操作系统在进程结束时回收；返回 `false` 仅表示启动时已经存在同名
 * Mutex，不代表当前进程发生了其他初始化错误。
 */
private fun createProgramLock(): Boolean {
    val name = "${PROGRAM_NAME}.lock"

    val h = Kernel32.INSTANCE.CreateMutex(null, true, name)

    return when (Kernel32.INSTANCE.GetLastError()) {
        WinError.ERROR_ALREADY_EXISTS -> false
        else -> true
    }
}
