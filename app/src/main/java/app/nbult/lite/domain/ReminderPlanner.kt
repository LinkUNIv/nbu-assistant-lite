package app.nbult.lite.domain

import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.data.db.SlotOverride
import app.nbult.lite.data.db.Term
import java.time.LocalDate
import java.time.ZoneId

/**
 * 下一节课。
 *
 * 提醒、通知文案、点击跳转全部基于这一个结果 —— 三处各自算一遍迟早会算出不同的时间。
 */
data class UpcomingClass(
    val week: Int,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    /** 绝对时刻（毫秒），已经按东八区算好。 */
    val startAt: Long,
    val endAt: Long,
    val name: String,
    val room: String,
    val teacher: String,
    /** 同一时刻还有几节（调课撞车时才会 > 0），只用于文案。 */
    val sameTimeCount: Int = 0
)

/**
 * 排出"下一节课"的纯逻辑。
 *
 * 关键点：
 * 1. 调课块在目标周的 `slot` **已经被改写成目标时间**（`movedIn`），所以这里完全不需要
 *    区分它是不是调来的 —— 直接用 `slot` 取节次与星期，出来就是调课之后的时间。
 * 2. 停课块 `isActive == false`，直接跳过。
 * 3. 日期按"那节课所在的那一天"算，不是按今天：周一 8:00 的课，周日晚 23:30 就要提醒，
 *    那一档 T-30 落在前一天。
 * 4. 固定东八区，与 `AppClock` 保持一致。
 */
object ReminderPlanner {

    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 开课前 30 分钟。 */
    const val LEAD_30 = 30 * 60_000L

    /** 开课前 15 分钟。 */
    const val LEAD_15 = 15 * 60_000L

    private const val LOOK_AHEAD_DAYS = 21

    fun next(
        entries: List<TimetableEntry>,
        overrides: List<SlotOverride>,
        periods: List<PeriodTime>,
        term: Term,
        now: Long
    ): UpcomingClass? {
        if (entries.isEmpty() || periods.isEmpty()) return null
        val startMinutes = HashMap<Int, Int>(periods.size)
        val endMinutes = HashMap<Int, Int>(periods.size)
        periods.forEach { p ->
            parseMinutes(p.startTime)?.let { startMinutes[p.idx] = it }
            parseMinutes(p.endTime)?.let { endMinutes[p.idx] = it }
        }
        if (startMinutes.isEmpty()) return null

        val todayEpochDay = DateUtil.epochDayOf(now)
        for (offset in 0..LOOK_AHEAD_DAYS) {
            val epochDay = todayEpochDay + offset
            val week = (Math.floorDiv(epochDay - term.startEpochDay, 7L) + 1L).toInt()
            if (week < 1 || week > term.totalWeeks) continue

            val dayStart = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZONE).toInstant().toEpochMilli()
            // ⚠️ 必须按星期过滤：weekEntries 返回的是**一整周**的块。
            // 不过滤的话每一节课都会被当成"今天"的课 —— 于是周六 17:47 会提醒周一 18:00 的课
            // （v0.20.4 的实测故障）。调课块的 slot 已在落库时改成目标星期，这里同样适用。
            val weekday = DateUtil.dayOfWeekIso(epochDay)
            val blocks = ScheduleService.weekEntries(entries, week, overrides)
                .filter { it.isActive && it.slot.dayOfWeek == weekday }
            if (blocks.isEmpty()) continue

            var best: UpcomingClass? = null
            for (block in blocks) {
                val start = startMinutes[block.slot.startSection] ?: continue
                val startAt = dayStart + start * 60_000L
                if (startAt <= now) continue
                if (best != null && startAt >= best.startAt) continue
                val end = endMinutes[block.slot.endSection] ?: start
                best = UpcomingClass(
                    week = week,
                    dayOfWeek = block.slot.dayOfWeek,
                    startSection = block.slot.startSection,
                    endSection = block.slot.endSection,
                    startAt = startAt,
                    endAt = dayStart + end * 60_000L,
                    name = block.displayName,
                    room = block.slot.room,
                    teacher = block.teacher,
                    sameTimeCount = 0
                )
            }
            val picked = best ?: continue
            // 同一天同一时刻可能不止一节（调课撞车），只提醒最早的一节，其余并进文案
            val sameTime = blocks.count { b ->
                val s = startMinutes[b.slot.startSection] ?: return@count false
                dayStart + s * 60_000L == picked.startAt
            }
            return picked.copy(sameTimeCount = (sameTime - 1).coerceAtLeast(0))
        }
        return null
    }

    /**
     * 此刻**正在上**的那一节；没有就返回 null。
     *
     * 只扫今天：课不会跨零点，没必要为了"现在"去看 21 天。
     * 停课的块 isActive=false，自然被排除。
     */
    fun currentClass(
        entries: List<TimetableEntry>,
        overrides: List<SlotOverride>,
        periods: List<PeriodTime>,
        term: Term,
        now: Long
    ): UpcomingClass? {
        if (entries.isEmpty() || periods.isEmpty()) return null
        val startMinutes = HashMap<Int, Int>(periods.size)
        val endMinutes = HashMap<Int, Int>(periods.size)
        periods.forEach { p ->
            parseMinutes(p.startTime)?.let { startMinutes[p.idx] = it }
            parseMinutes(p.endTime)?.let { endMinutes[p.idx] = it }
        }
        val epochDay = DateUtil.epochDayOf(now)
        val week = (Math.floorDiv(epochDay - term.startEpochDay, 7L) + 1L).toInt()
        if (week < 1 || week > term.totalWeeks) return null
        val dayStart = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZONE).toInstant().toEpochMilli()
        // 同样只取今天这个星期几的课，否则"正在上课"的通知会在没课的日子挂出来
        val weekday = DateUtil.dayOfWeekIso(epochDay)
        val blocks = ScheduleService.weekEntries(entries, week, overrides)
            .filter { it.isActive && it.slot.dayOfWeek == weekday }
        var sameTime = 0
        for (block in blocks) {
            val start = startMinutes[block.slot.startSection] ?: continue
            val end = endMinutes[block.slot.endSection] ?: continue
            val startAt = dayStart + start * 60_000L
            val endAt = dayStart + end * 60_000L
            if (now < startAt || now >= endAt) continue
            sameTime = blocks.count { other ->
                val os = startMinutes[other.slot.startSection] ?: return@count false
                dayStart + os * 60_000L == startAt
            }
            return UpcomingClass(
                week = week,
                dayOfWeek = block.slot.dayOfWeek,
                startSection = block.slot.startSection,
                endSection = block.slot.endSection,
                startAt = startAt,
                endAt = endAt,
                name = block.displayName,
                room = block.slot.room,
                teacher = block.teacher,
                sameTimeCount = (sameTime - 1).coerceAtLeast(0)
            )
        }
        return null
    }

    /** "08:00" / "8:00" 都收；解析不了就返回 null（那一节不参与提醒，而不是整体失败）。 */
    private fun parseMinutes(text: String): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(text) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}
