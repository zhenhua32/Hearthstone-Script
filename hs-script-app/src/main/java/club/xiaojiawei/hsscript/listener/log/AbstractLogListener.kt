package club.xiaojiawei.hsscript.listener.log

import club.xiaojiawei.hsscript.bean.DiskLogFile
import club.xiaojiawei.hsscript.bean.MemoryLogFile
import club.xiaojiawei.hsscript.dll.LogReader
import club.xiaojiawei.hsscript.enums.GameLogModeEnum
import club.xiaojiawei.hsscript.interfaces.LogFile
import club.xiaojiawei.hsscript.interfaces.closer.ScheduledCloser
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscript.utils.FileUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscriptbase.config.LISTEN_LOG_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.isFalse
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 游戏日志监听器的公共生命周期实现。
 *
 * 每个子类只负责“旧日志如何定位”和“新日志如何消费”；本类统一处理：
 * - 等待磁盘文件或 native 内存通道就绪；
 * - 在 [LISTEN_LOG_THREAD_POOL] 上按固定延迟轮询；
 * - 暂停/离开工作时间时取消监听任务；
 * - 通过 [nextLogListener] 串起 LoadingScreen、Decks、Power 等监听器。
 *
 * DISK 模式读取游戏日志目录中的真实文件，MEMORY 模式通过 `LogReader` 打开的通道
 * 构造 [MemoryLogFile]。两种实现都暴露为 [LogFile]，上层解析逻辑不需要区分来源。
 *
 * @param logFileName 游戏日志文件名，同时也是内存日志通道名。
 * @param listenInitialDelay 首次轮询前的延迟。
 * @param listenPeriod 两次消费之间的固定延迟。
 * @param listenTimeUnit 延迟参数的时间单位。
 * @author 肖嘉威
 * @date 2023/9/20 16:54
 */
abstract class AbstractLogListener(
    protected var logFileName: String,
    protected var listenInitialDelay: Long,
    protected var listenPeriod: Long,
    protected var listenTimeUnit: TimeUnit
) : ScheduledCloser {

    init {
        TaskManager.addTask(this)
    }

    var logFile: LogFile? = null
        private set

    private var logScheduledFuture: ScheduledFuture<*>? = null

    var nextLogListener: AbstractLogListener? = null

    /**
     * 设置责任链中的下一个监听器并返回该对象，便于连续调用构建监听链。
     * 当前监听器完成初始化后会立即调用下一个监听器的 [listen]。
     */
    fun setNextLogListener(nextLogListener: AbstractLogListener): AbstractLogListener {
        return nextLogListener.also { this.nextLogListener = it }
    }

    /** 首次打开日志后执行，用于跳过历史内容或重建必要状态。 */
    protected abstract fun dealOldLog()

    /** 每次调度执行，读取从当前位置开始的新内容；实现应在没有完整新行时尽快返回。 */
    protected abstract fun dealNewLog()

    private fun listenNextListener() {
        nextLogListener?.listen()
    }

    /**
     * 最多等待指定时间，返回当前日志模式对应的可读抽象。
     * 暂停会提前中断等待；返回 `null` 时 [listen] 会把脚本恢复为暂停状态。
     */
    private fun waitLogCreated(maxWaitMillisTime: Long = 15_000): LogFile? {
        doWhileBlock@ do {
            val start = System.currentTimeMillis()
            if (ScriptStatus.gameLogMode === GameLogModeEnum.DISK) {
                log.info { "等待创建【${logFileName}】日志" }
                var latestLogDir = GameUtil.getLatestLogDir()

                while (true) {
                    latestLogDir?.listFiles()?.let {
                        for (file in it) {
                            if (FileUtil.isFileLocked(file.absolutePath)) {
                                val createLogFile = createLogFile(latestLogDir)
                                log.info { "已创建游戏【${logFileName}】日志, $createLogFile" }
                                return DiskLogFile(createLogFile.absolutePath)
                            }
                        }
                    }
                    if (PauseStatus.isPause) {
                        break@doWhileBlock
                    }
                    if (System.currentTimeMillis() - start > maxWaitMillisTime) {
                        break@doWhileBlock
                    }
                    Thread.sleep(50)
                    latestLogDir = GameUtil.getLatestLogDir()
                }
            } else if (ScriptStatus.gameLogMode === GameLogModeEnum.MEMORY) {
                log.info { "等待创建游戏【${logFileName}】日志缓冲区" }
                if (!LogReader.nativeInit()){
                    log.error { "日志读取器初始化失败" }
                    break@doWhileBlock
                }
                while (!LogReader.existChannel(logFileName)) {
                    if (PauseStatus.isPause) {
                        break@doWhileBlock
                    }
                    if (System.currentTimeMillis() - start > maxWaitMillisTime) {
                        break@doWhileBlock
                    }
                    Thread.sleep(50)
                }
                log.info { "已创建游戏【${logFileName}】日志缓冲区" }
                return MemoryLogFile(logFileName)
            } else {
                log.error { "不支持的日志模式: ${ScriptStatus.gameLogMode}" }
            }
        } while (false)
        return null
    }

    /**
     * 启动监听器。
     *
     * 方法按实例同步，重复启动同一监听器不会创建第二个调度任务，但仍会继续启动责任链。
     * 成功打开日志后先调用 [dealOldLog]，再注册周期任务，最后启动 [nextLogListener]。
     */
    fun listen() {
        synchronized(this) {
            logScheduledFuture?.let {
                if (!it.isDone) {
                    log.warn { logFileName + "正在被监听，无法再次被监听" }
                    listenNextListener()
                    return
                }
            }
            closeLogFile()
            val waitLogFile = waitLogCreated()
            if (waitLogFile == null){
                log.error { "$logFileName 日志创建失败" }
                PauseStatus.isPause = true
                return
            }else{
                logFile = waitLogFile
            }
            log.info { "开始监听日志: $logFileName" }
            try {
                dealOldLog()
            } catch (e: Exception) {
                log.error(e) {}
                return
            }
            logScheduledFuture = LISTEN_LOG_THREAD_POOL.scheduleWithFixedDelay({
                if (PauseStatus.isPause || !WorkTimeListener.working) {
                    stopAll()
                } else {
                    try {
                        dealNewLog()
                    } catch (e: InterruptedException) {
                        log.warn(e) { logFileName + "监听中断" }
                    } catch (e: Exception) {
                        log.error(e) { logFileName + "监听发生错误" }
                    }
                }
            }, listenInitialDelay, listenPeriod, listenTimeUnit)
            listenNextListener()
        }
    }

    private fun createLogFile(logPath: File): File {
        val logFile = logPath.resolve(logFileName)
        logFile.createNewFile()
        return logFile
    }

    private fun closeLogFile() {
        synchronized(this) {
            logFile?.let {
                it.close()
                logFile = null
            }
        }
    }

    private fun closeLogListener() {
        synchronized(this) {
            logScheduledFuture?.let {
                it.isDone.isFalse {
                    it.cancel(true)
                }
            }
        }
    }

    /**
     * 取消周期读取任务。日志句柄会在下一次 [listen] 开始时关闭并替换，避免重启期间并发换句柄。
     */
    override fun stopAll() {
        closeLogListener()
    }

}
