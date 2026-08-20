package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscript.interfaces.closer.ScheduledCloser
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscriptbase.util.isFalse
import java.io.Closeable
import java.util.concurrent.ScheduledFuture

/**
 * 可暂停、可重试的运行时启动责任链节点。
 *
 * 与一次性的 Initializer 不同，Starter 会在每次开始工作或重启游戏时重新执行。节点可用
 * [addTask] 保存一个轮询/超时任务；再次 [start]、暂停或 TaskManager 全局停止时，该任务
 * 会先被取消。当前节点确认前置条件满足后调用 [startNextStarter]，下一节点始终在线程池
 * 上启动，避免递归占用当前轮询线程。
 *
 * 子类必须在成功、重试和失败路径中明确选择：进入下一节点、登记新任务或调用 [pause]，
 * 否则责任链会停留在当前节点。
 *
 * @author 肖嘉威
 * @date 2023/7/5 14:37
 */
abstract class AbstractStarter : ScheduledCloser{

    private var nextStarter: AbstractStarter? = null

    protected var scheduledFuture: ScheduledFuture<*>? = null

    /** 取消当前节点遗留任务，再从头执行本节点。 */
    fun start() {
        log.info { "执行【${javaClass.simpleName}】" }
        stopTask()
        execStart()
    }

    fun setNextStarter(nextStarter: AbstractStarter?): AbstractStarter {
        return nextStarter?.also { this.nextStarter = it } ?: this
    }

    fun getNextStarter(): AbstractStarter? {
        return nextStarter
    }

    /** 取消当前节点唯一的调度任务；允许安全重复调用。 */
    fun stopTask() {
        scheduledFuture?.let {
            it.isDone.isFalse {
                it.cancel(true)
            }
        }
        scheduledFuture = null
    }

    /** 用新任务替换旧任务，保证每个 Starter 同时最多维护一个轮询/超时任务。 */
    protected fun addTask(taskFuture: ScheduledFuture<*>) {
        stopTask()
        scheduledFuture = taskFuture
    }

    protected abstract fun execStart()

    /** 异步启动下一节点，并释放当前节点的调度任务。 */
    protected fun startNextStarter() {
        EXTRA_THREAD_POOL.execute {
            nextStarter?.start()
        }
        stopTask()
    }

    /** 终止当前节点并异步恢复全局暂停，通常用于不可恢复的配置或启动失败。 */
    protected fun pause() {
        stopTask()
        PauseStatus.asyncSetPause(true)
    }

    override fun stopAll() {
        stopTask()
    }

}
