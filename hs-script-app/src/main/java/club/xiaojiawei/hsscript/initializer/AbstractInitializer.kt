package club.xiaojiawei.hsscript.initializer

import club.xiaojiawei.hsscriptbase.config.log

/**
 * 主界面显示前执行的同步初始化责任链节点。
 *
 * [init] 先执行当前节点 [exec]，成功返回后再递归执行下一个节点。这里没有吞掉异常，
 * 任一节点失败都会中止后续初始化并回到 `MainApplication.start` 的统一错误处理。
 * 因此节点应保持幂等，且不要启动需要在暂停时反复取消的长期任务；长期任务属于 Starter。
 *
 * @author 肖嘉威
 * @date 2023/7/4 11:24
 */
abstract class AbstractInitializer {

    private var nextInitializer: AbstractInitializer? = null

    /** 执行当前节点并继续责任链。 */
    fun init() {
        log.info { "执行【${javaClass.simpleName}】" }
        exec()
        initNextInitializer()
    }

    /** 当前节点的同步初始化逻辑。 */
    protected abstract fun exec()

    /** 设置并返回下一个节点，便于 `a.setNextInitializer(b).setNextInitializer(c)` 链式装配。 */
    fun setNextInitializer(nextInitializer: AbstractInitializer): AbstractInitializer {
        return nextInitializer.also { this.nextInitializer = it }
    }

    private fun initNextInitializer() {
        nextInitializer?.init()
    }
}
