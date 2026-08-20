package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.value.ChangeListener

/**
 * 用户可见的全局开始/暂停状态。
 *
 * 底层使用 JavaFX Property，便于 UI、[club.xiaojiawei.hsscript.core.Core] 和其他监听器
 * 共享同一状态。属性变更监听器可能触发窗口和后台任务操作；非 UI 调用方优先使用
 * [asyncSetPause]，避免在日志处理线程或 native 回调线程中同步执行整条监听链。
 *
 * 此状态只表达用户意图，不等价于“当前在工作时间”或“Starter 已完成”。
 *
 * @author 肖嘉威
 * @date 2023/7/5 15:04
 */
object PauseStatus {

    private val isPauseProperty: ReadOnlyBooleanWrapper = ReadOnlyBooleanWrapper(true)

    /** `true` 表示停止自动操作；写入会同步通知所有 JavaFX ChangeListener。 */
    var isPause: Boolean
        get() {
            return isPauseProperty.get()
        }
        set(value) {
            isPauseProperty.set(value)
        }

    /** [isPause] 的便捷反值，仅用于读取。 */
    val isStart
        get() = !isPauseProperty.get()

    /** 设置暂停状态并返回写入值，便于在表达式或回调中使用。 */
    fun setPauseReturn(isPaused: Boolean): Boolean {
        isPause = isPaused
        return isPause
    }

    /** 在线程池中切换状态，调用方不会等待监听器执行完毕。 */
    fun asyncSetPause(isPaused: Boolean) {
        EXTRA_THREAD_POOL.submit {
            this.isPause = isPaused
        }
    }

    fun addChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.addListener(listener)
    }

    fun removeChangeListener(listener: ChangeListener<Boolean>) {
        isPauseProperty.removeListener(listener)
    }

}
