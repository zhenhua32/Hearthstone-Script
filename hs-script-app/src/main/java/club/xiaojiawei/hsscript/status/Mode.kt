package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy
import club.xiaojiawei.hsscript.utils.go
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * LoadingScreen 日志驱动的游戏界面状态机。
 *
 * [currMode] 是日志已经确认的界面，[nextMode] 是脚本点击后预期进入的界面，
 * [prevMode] 保存上一个确认状态。模式切换通过有界队列串行消费：先执行旧策略的
 * `afterLeave`，取消旧任务，再执行新策略的 `entering`，避免多个日志线程并发操作 UI。
 *
 * 设置 [nextMode] 后有 5 秒兜底任务；如果游戏没有及时打印确认日志，会把预期模式
 * 视为已进入。停止工作或重置时必须取消该任务，防止过期回调污染新一轮状态。
 *
 * @author 肖嘉威
 * @date 2022/11/25 0:09
 */
object Mode {

    /** 一次模式迁移快照；消费时不再读取可能已变化的全局字段。 */
    data class ModeStruct(var currMode: ModeEnum? = null, var newMode: ModeEnum? = null)

    private val modeQueue = ArrayBlockingQueue<ModeStruct>(5)

    private var nextModeTimeoutTask: Future<*>? = null

    init {
        WorkTimeListener.addWorkStatusListener { _, oldValue, newValue ->
            if (!newValue) {
                stopTask()
            }
        }
        go {
            while (true) {
                val (currMode1, newMode) = modeQueue.take()
                runCatching {
                    AbstractModeStrategy.cancelAllTask()
                }.onFailure {
                    log.error(it) { "" }
                }
                go {
                    currMode1?.modeStrategy?.afterLeave()
                    AbstractModeStrategy.cancelAllTask()
                    newMode?.modeStrategy?.entering()
                }
            }
        }
    }

    private fun stopTask() {
        nextModeTimeoutTask?.let {
            it.cancel(true)
            nextModeTimeoutTask = null
        }
    }

    /** 脚本动作预期进入的模式，用于日志延迟时的超时确认。 */
    @Volatile
    var nextMode: ModeEnum? = null
        set(value) {
            if (value == field) return
            stopTask()
            field = value
            if (value == null) return
            log.info { "准备进入【${value.comment}】" }
            nextModeTimeoutTask = EXTRA_THREAD_POOL.schedule({
                if (currMode != value) {
                    log.warn { "日志长时间未打印已进入${value.comment}，默认已经进入" }
                    currMode = value
                }
            }, 5, TimeUnit.SECONDS)
        }

    /** 日志已确认的当前模式；写入会异步触发离开/进入回调。 */
    @Volatile
    var currMode: ModeEnum? = null
        set(value) {
            if (value === field) return
            stopTask()
            modeQueue.add(ModeStruct(field, value))
            prevMode = field
            field = value
        }

    /** 最近一次已离开的模式，供需要回退或判断来源的策略读取。 */
    @Volatile
    var prevMode: ModeEnum? = null

    /** 清空当前、预期和历史模式，并取消所有模式超时任务。 */
    fun reset() {
        currMode?.let {
            currMode = null
            nextMode = null
            prevMode = null
            log.info { "已重置模式状态" }
        }
    }
}
