package app.nbult.lite.domain

import app.nbult.lite.data.db.Course
import app.nbult.lite.data.db.CourseSlot
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.data.db.SlotOverride
import app.nbult.lite.data.db.Term
import java.util.Calendar

data class TimetableEntry(
    val slot: CourseSlot,
    val course: Course,
    /** 原始时段：调课后 [slot] 指向目标时段，但编辑/删除永远针对原始时段。 */
    val originSlot: CourseSlot = slot,
    /** 本周停课。 */
    val suspended: Boolean = false,
    /** 非空表示这是一个"原位占位"（灰块），内容为调课去向说明。 */
    val ghostLabel: String = "",
    /** 非空则右上角显示角标，如「停课」「调」。 */
    val badge: String = "",
    /** 该状态来自哪条 override，用于"恢复原样"。 */
    val overrideId: Long = 0L
) {
    val displayName: String
        get() = course.name.replace(Regex("""^[A-Za-z0-9]{4,14}\s*[-_]\s*(?=\S)"""), "")

    val code: String
        get() = Regex("""^([A-Za-z0-9]{4,14})\s*[-_]\s*\S""")
            .find(course.name)?.groupValues?.get(1).orEmpty()

    val teacher: String get() = course.teacher
    val room: String get() = slot.room

    /** 是否是一个真实可提醒的上课时段（占位块与停课都不算）。 */
    val isActive: Boolean get() = !suspended && ghostLabel.isEmpty()
}

data class DayPlan(
    val entries: List<TimetableEntry>,
    val isTomorrow: Boolean
)

object ScheduleService {

    fun currentWeek(term: Term, todayEpochDay: Long = DateUtil.todayEpochDay()): Int {
        val diff = todayEpochDay - term.startEpochDay
        if (diff < 0) return 1
        return (diff / 7).toInt() + 1
    }

    fun entriesOfWeek(entries: List<TimetableEntry>, week: Int): List<TimetableEntry> =
        entries.filter { WeekMask.isOn(it.slot.weekMask, week) }

    /**
     * 叠加停课/调课之后的某一周课程。
     *
     * 调课会产生两个块：目标时段的正常块（角标「调」）+ 原时段的灰色占位（"已调至…"）。
     * 占位块存在的意义是：不让人误以为"这门课这周没了"。
     */
    fun weekEntries(
        entries: List<TimetableEntry>,
        week: Int,
        overrides: List<SlotOverride>
    ): List<TimetableEntry> {
        val base = entriesOfWeek(entries, week)
        if (overrides.isEmpty()) return base.sortedBy { it.slot.startSection }

        val bySlot = overrides.groupBy { it.slotId }
        val out = ArrayList<TimetableEntry>(base.size + 2)
        base.forEach { entry ->
            val ov = bySlot[entry.slot.id]?.lastOrNull { WeekMask.isOn(it.weekMask, week) }
            when {
                ov == null -> out.add(entry)

                ov.kind == SlotOverride.KIND_SUSPEND -> out.add(
                    entry.copy(suspended = true, badge = "停课", overrideId = ov.id)
                )

                else -> {
                    val start = ov.toStart.coerceIn(1, 12)
                    val end = ov.toEnd.coerceIn(start, 12)
                    val target = entry.slot.copy(
                        dayOfWeek = ov.toDay.coerceIn(1, 7),
                        startSection = start,
                        endSection = end,
                        room = ov.toRoom.ifBlank { entry.slot.room }
                    )
                    out.add(
                        entry.copy(
                            slot = target,
                            originSlot = entry.slot,
                            badge = "调",
                            overrideId = ov.id
                        )
                    )
                    val day = DateUtil.dayNames.getOrNull(ov.toDay - 1).orEmpty()
                    out.add(
                        entry.copy(
                            slot = entry.slot,
                            originSlot = entry.slot,
                            ghostLabel = "已调至 $day $start-$end 节",
                            overrideId = ov.id
                        )
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.slot.startSection }, { it.slot.dayOfWeek }))
    }

    fun entriesOfDay(entries: List<TimetableEntry>, week: Int, dayOfWeek: Int): List<TimetableEntry> =
        entriesOfWeek(entries, week)
            .filter { it.slot.dayOfWeek == dayOfWeek }
            .sortedBy { it.slot.startSection }

    fun today(entries: List<TimetableEntry>, term: Term?, today: Long = DateUtil.todayEpochDay()): List<TimetableEntry> {
        if (term == null) return emptyList()
        val week = currentWeek(term, today)
        val day = DateUtil.dayOfWeekIso(today)
        return entriesOfDay(entries, week, day)
    }

    fun planForToday(
        entries: List<TimetableEntry>,
        term: Term?,
        periods: List<PeriodTime>,
        overrides: List<SlotOverride> = emptyList(),
        nowMillis: Long = AppClock.now()
    ): DayPlan {
        if (term == null) return DayPlan(emptyList(), false)

        val today = DateUtil.epochDayOf(nowMillis)
        val week = currentWeek(term, today)
        val day = DateUtil.dayOfWeekIso(today)
        val nowMinutes = minutesOfDay(nowMillis)

        // 停课与"已调走"的占位块都不参与"下一节课"
        fun dayEntries(w: Int, d: Int): List<TimetableEntry> =
            weekEntries(entries, w, overrides)
                .filter { it.slot.dayOfWeek == d && it.isActive }
                .sortedBy { it.slot.startSection }

        val remaining = dayEntries(week, day).filter { entry ->
            val endTime = periods.firstOrNull { it.idx == entry.slot.endSection }?.endTime
            val endMinutes = endTime?.let { parseMinutes(it) } ?: return@filter true
            endMinutes > nowMinutes
        }
        if (remaining.isNotEmpty()) return DayPlan(remaining, false)

        val tomorrowDay = if (day == 7) 1 else day + 1
        val tomorrowWeek = if (day == 7) week + 1 else week
        return DayPlan(dayEntries(tomorrowWeek, tomorrowDay), true)
    }

    fun minutesOfDay(millis: Long): Int {
        val calendar = Calendar.getInstance(AppClock.ZONE)
        calendar.timeInMillis = millis
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    fun parseMinutes(time: String): Int? {
        val parts = time.split(":")
        if (parts.size < 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        return hour * 60 + minute
    }

    fun totalSections(entries: List<TimetableEntry>, minimum: Int = 10): Int =
        maxOf(minimum, entries.maxOfOrNull { it.slot.endSection } ?: minimum)
}
