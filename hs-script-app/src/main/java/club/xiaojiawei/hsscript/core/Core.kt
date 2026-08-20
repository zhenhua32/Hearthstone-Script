package club.xiaojiawei.hsscript.core

import club.xiaojiawei.hsscript.config.StarterConfig
import club.xiaojiawei.hsscript.consts.GAME_CN_NAME
import club.xiaojiawei.hsscript.consts.PLATFORM_CN_NAME
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.enums.MouseControlModeEnum
import club.xiaojiawei.hsscript.enums.OperateEnum
import club.xiaojiawei.hsscript.enums.WindowEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.ScreenLogListener
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.utils.*
import club.xiaojiawei.hsscriptbase.config.CORE_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.isFalse
import club.xiaojiawei.hsscriptbase.util.isTrue
import java.util.concurrent.locks.ReentrantLock

/**
 * 脚本运行生命周期的总协调器。
 *
 * [PauseStatus] 表示用户层面的开始/暂停，[WorkTimeListener] 表示时间规则是否允许工作；
 * 两者都满足时才会进入 Starter 责任链。对象本身不直接启动战网、炉石或日志监听，
 * 而是把这些步骤委托给 `StarterConfig.starter`，从而让失败恢复和重试逻辑保持一致。
 *
 * [launch] 是 lazy 注册入口，应用启动时必须访问一次。真正的启动任务在
 * `CORE_THREAD_POOL` 上执行，并由 [lock] 串行化，防止暂停监听、工作时间监听和
 * 手动重启同时触发多条启动链。
 *
 * @author 肖嘉威
 * @date 2023/7/5 13:15
 */
object Core {
    /** 最近一次检测到游戏活动的时间戳，供异常恢复/超时判断使用。 */
    @Volatile
    var lastActiveTime: Long = 0

    private val lock = ReentrantLock()

    /**
     * 注册暂停状态与工作时间监听器。
     * 访问该属性只负责注册，实际动作由后续属性变化异步触发。
     */
    val launch: Unit by lazy {
        PauseStatus.addChangeListener { _, _, newValue ->
            newValue
                .isTrue {
                    WorkTimeListener.working = false
                    Mode.reset()
                    runUI { WindowUtil.getStage(WindowEnum.MAIN)?.show() }
                    log.info { "当前处于【暂停】状态" }
                }.isFalse {
                    WorkTimeListener.checkWork()
                    if (WorkTimeListener.canWork()) {
                        start()
                    } else {
                        WorkTimeListener.cannotWorkLog()
                        runUI {
                            val alert =
                                WindowUtil.createAlert(
                                    "当前不在工作时间",
                                    "是否睡眠系统(下个可用时间会唤醒系统)",
                                    {
                                        OperateEnum.SLEEP_SYSTEM.exec()
                                    },
                                    {},
                                    WindowUtil.getStage(WindowEnum.MAIN),
                                )
                            go {
                                Thread.sleep(5_000)
                                runUI {
                                    alert.hide()
                                }
                            }
                            alert.show()
                        }
                    }
                    log.info { "当前处于【开始】状态" }
                }
        }
        WorkTimeListener.addWorkStatusListener { _, _, isWorking: Boolean ->
            if (isWorking) {
                start(true)
            }
            if (ConfigExUtil.getMouseControlMode() === MouseControlModeEnum.DRIVE) {
                isWorking
                    .isTrue {
                        CSystemDll.safeRefreshDriver()
                    }.isFalse {
                        CSystemDll.safeReleaseDriver()
                    }
            }
        }
    }

    /**
     * 尝试启动脚本责任链。
     *
     * @param force 为 `true` 时忽略 `WorkTimeListener.working` 的重复启动短路，常用于
     * 工作时间从不可用切换到可用的边界；互斥锁仍然生效。
     *
     * 路径无效时不会启动外部程序，而是提示用户、打开设置页并恢复暂停状态。
     */
    fun start(force: Boolean = false) {
        if ((!force && WorkTimeListener.working) || lock.isLocked) return

        CORE_THREAD_POOL.execute {
            try {
                if ((!force && WorkTimeListener.working) || !lock.tryLock()) return@execute

                if (ScriptStatus.isValidGameInstallPath && ScriptStatus.isValidPlatformProgramPath) {
                    WorkTimeListener.working = true
                    StarterConfig.starter.start()
                } else if (!PauseStatus.isPause) {
                    SystemUtil.notice("需要配置" + GAME_CN_NAME + "和" + PLATFORM_CN_NAME + "的路径")
                    WindowUtil.showStage(WindowEnum.SETTINGS, WindowUtil.getStage(WindowEnum.MAIN))
                    PauseStatus.isPause = true
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * 重置界面日志处理状态、暂停脚本、结束游戏并重新触发启动链。
     *
     * @param sync 是否在调用线程完成状态切换；默认提交到核心线程池，避免阻塞日志或 UI 线程。
     */
    fun restart(sync: Boolean = false) {
        ScreenLogListener.resetDealing()
        val exec = {
            PauseStatus.asyncSetPause(true)
            GameUtil.killGame(true)
            log.info { "${GAME_CN_NAME}重启中……" }
            PauseStatus.isPause = false
        }
        if (sync) {
            exec()
        } else {
            CORE_THREAD_POOL.execute { exec() }
        }
    }
}
