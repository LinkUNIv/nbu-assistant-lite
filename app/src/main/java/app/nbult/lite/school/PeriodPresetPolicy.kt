package app.nbult.lite.school

/**
 * 换学校 / 换季时该不该改写库里的作息 —— 纯判定，不碰 Context 与 org.json，便于单测。
 *
 * 背景：`period_time` 表全校共用一张（没有 schoolId），
 * 切学校必须把时间换过来。反过来切回宁大时若什么都不写，库里就留着湘潭的时间
 * （第五节 14:00 而不是 13:30，整张课表都偏）。但宁大有主校区 / 梅山两套，
 * 一律覆盖又会误伤选了梅山的人。
 *
 * 折中办法是**记来源**：每套作息写入时都带学校前缀 `<schoolId>/<presetKey>`，
 * 前缀就是写入者。只有确知"库里那份是别的学校写的"时，才动手恢复本校那套；
 * 从未自动写过（`appliedKey == null`）就一律不碰，用户手调的结果不会被冲掉。
 */
internal object PeriodPresetPolicy {

    /** 一套作息：`presetId` 用于取时间表，`recordKey` 用于记「已应用」。 */
    data class Target(val presetId: String, val recordKey: String)

    sealed interface Decision {
        /** 需要写入 [presetId] 那一套；成功后把 [recordKey] 记成「已应用」。 */
        data class Apply(val presetId: String, val recordKey: String) : Decision

        /** 不动库里的作息，[reason] 会进日志。 */
        data class Keep(val reason: String) : Decision
    }

    /** 写入标识：`<schoolId>/<presetKey>`。 */
    fun key(schoolId: String, presetKey: String): String = "$schoolId/$presetKey"

    /** 从写入标识里取出写入者；空串与 null 一样当作「不知道」。 */
    fun schoolOf(appliedKey: String?): String? =
        appliedKey?.substringBefore('/')?.takeIf { it.isNotBlank() }

    /**
     * @param schoolId 即将生效的学校
     * @param appliedKey 上次自动写入的标识，null 表示从未自动写过
     * @param defaultSchoolId 默认学校（宁大）：它有多套模板，默认不自动覆盖
     * @param ownPreset 本校按当前季节挑出的那套，null 表示该校没配作息
     * @param rememberedDefaultPresetId 默认学校上次手选的模板 id，null 表示没选过
     * @param defaultFallbackPresetId 默认学校没选过时用的兜底模板 id
     */
    fun decide(
        schoolId: String,
        appliedKey: String?,
        defaultSchoolId: String,
        ownPreset: Target?,
        rememberedDefaultPresetId: String?,
        defaultFallbackPresetId: String
    ): Decision {
        val isDefault = schoolId == defaultSchoolId
        // 默认学校不看自己的 calendar，用「上次手选 / 兜底」那一套
        val target = if (isDefault) {
            val id = rememberedDefaultPresetId?.takeIf { it.isNotBlank() } ?: defaultFallbackPresetId
            Target(id, key(schoolId, id))
        } else {
            ownPreset
        }

        if (target == null) return Decision.Keep("本校未配置作息，保留当前时间")
        if (target.recordKey == appliedKey) return Decision.Keep("已是这一套，保留手调")
        if (isDefault && schoolOf(appliedKey) == null) {
            // 宁可不动：新建与老用户升级都在这里，内置默认作息本就等于主校区
            return Decision.Keep("宁大有多套模板，不自动覆盖")
        }
        return Decision.Apply(target.presetId, target.recordKey)
    }
}
