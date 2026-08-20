package club.xiaojiawei.hsscript.listener.log

import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.consts.GAME_WAR_LOG_NAME
import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.utils.PowerLogUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.StepEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import java.util.concurrent.TimeUnit

/**
 * Power.log 对局事件监听器，是日志文本进入战斗状态机的入口。
 *
 * 首次打开日志时跳到文件尾部并重置 [WAR]，避免把旧对局重放到当前状态；之后仅在
 * 脚本未暂停、处于工作时间且阶段处理器空闲时读取新行。相关行按 [WarPhaseEnum]
 * 分派给 PhaseStrategy，PhaseStrategy 再调用 `PowerLogUtil` 修改战局模型。
 *
 * 监听周期很短，因此 [dealNewLog] 必须在无数据时立即返回，不能长期占用日志线程池。
 *
 * @author 肖嘉威
 * @date 2023/7/5 20:40
 */
object PowerLogListener :
    AbstractLogListener(GAME_WAR_LOG_NAME, 0, 50L, TimeUnit.MILLISECONDS) {

    private val war = WAR

    /** 为游戏追加结束日志预留的空间，达到上限前提前触发重启。 */
    private const val RESERVE_SIZE_B = 4 * 1024 * 1024

    /** 忽略已有内容，只从启动后的新日志开始建模。 */
    override fun dealOldLog() {
        logFile?.let {
            it.seek(it.length())
        }
        WarEx.reset()
    }

    /** 连续消费当前批次可读行，遇到 EOF 或执行条件失效即归还调度线程。 */
    override fun dealNewLog() {
        while (!PauseStatus.isPause && !AbstractPhaseStrategy.dealing && WorkTimeListener.working) {
            logFile?.let {
                val line = it.readLine()
                if (line == null) {
                    return@dealNewLog
                } else if (PowerLogUtil.isRelevance(line)) {
                    resolveLog(line)
                }
            } ?: return
        }
    }

    /**
     * 将一行日志交给当前阶段处理器，并把 FINAL_GAMEOVER 统一收敛到 GAME_OVER 阶段。
     * FILL_DECK 与 GAME_OVER 单独列出是为了强调它们是建局和收尾边界。
     */
    private fun resolveLog(line: String) {
        when (war.currentPhase) {
            WarPhaseEnum.FILL_DECK -> {
                WarPhaseEnum.FILL_DECK.phaseStrategy?.deal(line)
            }

            WarPhaseEnum.GAME_OVER -> {
                WarPhaseEnum.GAME_OVER.phaseStrategy?.deal(line)
            }

            else -> war.currentPhase.phaseStrategy?.deal(line)
        }
        if (war.currentTurnStep == StepEnum.FINAL_GAMEOVER) {
            war.currentPhase = WarPhaseEnum.GAME_OVER
        }
    }

    /**
     * 检查 Power.log 是否接近游戏配置的最大值。
     *
     * @return `true` 表示可继续监听；接近上限时触发异步重启并返回 `false`。
     */
    fun checkPowerLogSize(): Boolean {
        val logFile = logFile
        logFile ?: return false

        if (ScriptStatus.maxLogSizeB > 0 && logFile.length() + RESERVE_SIZE_B >= ScriptStatus.maxLogSizeB) {
            log.info { "${GAME_WAR_LOG_NAME}即将达到" + (ScriptStatus.maxLogSizeKB) + "KB，准备重启游戏" }
            Core.restart()
            return false
        }
        return true
    }

}
