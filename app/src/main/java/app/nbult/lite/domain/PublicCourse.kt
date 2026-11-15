package app.nbult.lite.domain

import app.nbult.lite.data.db.Course
import app.nbult.lite.data.db.PeriodTime

/**
 * 公选课的独立作息。
 *
 * 有些学校（民政）公共课与学院课不是同一张作息表：学院课第 3 节 10:10，
 * 公选课 10:30。于是"全校一张表 + 楼栋偏移"不够用，需要**按课取表**。
 *
 * 数据来自 `rules.json`：
 * ```json
 * "publicCourse": {
 *   "preset": "C",                       // calendar.json 里 periods 的 id，空 = 该校没有这回事
 *   "badge": "公选课",
 *   "matchKeywords": ["公必", "公限", "公选"]   // 只用于导入时给开关打默认值
 * }
 * ```
 *
 * 判定顺序：用户手动开关（[Course.publicCourseOverride]）> 课程性质关键词 > 否。
 * 开关单独存一列而不是改性质，重新导入不会把用户的选择冲掉。
 */
data class PublicCourseRule(
    val preset: String = "",
    val badge: String = "公选课",
    val matchKeywords: List<String> = emptyList()
) {
    /** 这所学校有没有公选课作息。没有时卡片里不出现开关，也不参与任何计算。 */
    val enabled: Boolean get() = preset.isNotBlank()

    /** 仅按课程性质判断（导入时给开关打默认值用）。 */
    fun categoryLooksPublic(category: String): Boolean =
        enabled && matchKeywords.any { it.isNotBlank() && category.contains(it.trim()) }

    /** 最终是否按公选课作息：手动覆盖优先。 */
    fun appliesTo(course: Course): Boolean {
        if (!enabled) return false
        course.publicCourseOverride?.let { return it }
        return categoryLooksPublic(course.category)
    }
}

/**
 * 取某门课实际使用的作息表。
 *
 * @param publicTable 公选课作息（来自 `calendar.json` 的 periods 表），没有就传空
 * @param fallback    用户当前选中的作息（DB 里的那一套）
 */
fun effectivePeriods(
    course: Course,
    rule: PublicCourseRule,
    publicTable: List<PeriodTime>,
    fallback: List<PeriodTime>
): List<PeriodTime> {
    if (!rule.appliesTo(course)) return fallback
    return if (publicTable.isNotEmpty()) publicTable else fallback
}

/**
 * 从校内作息表里取出公选课那一套。
 *
 * 表本身存在 `calendar.json` 的 `periods`，rules 只声明用哪一套（[PublicCourseRule.preset]）。
 * 取不到就返回空，调用方回退到用户所选作息。
 */
fun publicCoursePeriods(calendar: SchoolCalendar?, rule: PublicCourseRule): List<PeriodTime> {
    if (!rule.enabled) return emptyList()
    val table = calendar?.periods?.firstOrNull { it.id == rule.preset } ?: return emptyList()
    return table.items.map { PeriodTime(it.idx, it.start, it.end) }
}

/**
 * 一门课实际显示/提醒用的起止时刻。
 *
 * 顺序：先按公选课换表（C 套），再叠加楼栋推迟（+N 分钟）。
 * 任一环节不适用就跳过，最后仍取不到返回 null（调用方不显示时刻行）。
 */
fun effectiveTimes(
    entry: TimetableEntry,
    periods: Map<Int, PeriodTime>,
    rule: PublicCourseRule,
    publicTable: List<PeriodTime>,
    roomDelayMinutes: Int
): Pair<String, String>? {
    val table = if (rule.appliesTo(entry.course) && publicTable.isNotEmpty()) {
        publicTable.associateBy { it.idx }
    } else {
        periods
    }
    val start = table[entry.slot.startSection]?.startTime?.take(5) ?: return null
    val end = table[entry.slot.endSection]?.endTime?.take(5) ?: return null
    val delay = roomDelayMinutes.coerceAtLeast(0)
    return LateStartPolicy.shift(start, delay) to LateStartPolicy.shift(end, delay)
}

/** `HH:mm` → 当天第几分钟；解析不了返回 null。 */
fun minutesOfDay(hhmm: String): Int? {
    val parts = hhmm.trim().split(":")
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].take(2).toIntOrNull() ?: return null
    return h * 60 + m
}

/**
 * 提醒用：这门课实际的起止分钟（含公选课换表与楼栋推迟）。
 * 返回 null 表示没有特殊情况，调用方按常规作息算。
 */
fun effectiveMinutes(
    entry: TimetableEntry,
    periods: Map<Int, PeriodTime>,
    rule: PublicCourseRule,
    publicTable: List<PeriodTime>,
    roomDelayMinutes: Int
): Pair<Int, Int>? {
    val shifted = effectiveTimes(entry, periods, rule, publicTable, roomDelayMinutes) ?: return null
    val start = minutesOfDay(shifted.first) ?: return null
    val end = minutesOfDay(shifted.second) ?: return null
    return start to end
}

/** `PeriodTime` 列表转「第几节 → 起止」，渲染与提醒都用这个。 */
fun sectionTimes(periods: List<PeriodTime>): Map<Int, Pair<String, String>> =
    periods.associate { it.idx to (it.startTime to it.endTime) }
