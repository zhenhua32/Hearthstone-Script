package club.xiaojiawei.hsscript.initializer

import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.WarPhaseEnum
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscriptbase.interfaces.ModeStrategy
import club.xiaojiawei.hsscriptbase.interfaces.PhaseStrategy

/**
 * 初始化领域基础配置并把枚举项绑定到对应策略单例。
 *
 * Mode/WarPhase 枚举与策略类遵循严格命名约定，例如 `GAMEPLAY` 对应
 * `GameplayModeStrategy`。新增枚举值时必须同时创建同名规则的 Kotlin `object`，否则
 * 这里的反射会失败并中止应用初始化。这样做把后续日志分派简化为枚举直接持有策略引用。
 *
 * @author 肖嘉威
 * @date 2023/7/4 11:33
 */
class BaseInitializer : AbstractInitializer() {

    private fun toCamelCase(snakeCase: String): String {
        return snakeCase.split("_")
            .joinToString("") { it.lowercase().replaceFirstChar { char -> char.uppercase() } }
    }

    /** 读取权重开关，并完成 ModeStrategy/PhaseStrategy 的一次性反射注册。 */
    override fun exec() {
        BaseData.enableChangeWeight = ConfigUtil.getBoolean(ConfigEnum.ENABLE_CHANGE_WEIGHT)
        ModeEnum.entries.forEach {
            it.modeStrategy =
                Class.forName("club.xiaojiawei.hsscript.strategy.mode." + toCamelCase(it.name) + "ModeStrategy").kotlin.objectInstance as ModeStrategy<*>?
        }
        WarPhaseEnum.entries.forEach {
            it.phaseStrategy =
                Class.forName("club.xiaojiawei.hsscript.strategy.phase." + toCamelCase(it.name) + "PhaseStrategy").kotlin.objectInstance as PhaseStrategy?
        }
    }

}
