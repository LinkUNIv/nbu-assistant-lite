package app.nbult.lite.domain

import app.nbult.lite.data.db.Course
import app.nbult.lite.data.db.CourseSlot
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.data.db.Term
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 推迟上课必须**连提醒一起顺延**。
 *
 * 光在格子上写「推迟 10 分钟」而闹钟照旧，比不标更误导：标签说 8:10 上课，
 * 提醒却在 7:30 响（按 8:00 算的提前 30 分钟）。
 */
class ReminderPlannerLateStartTest {

    private val monday = LocalDate.of(2026, 11, 9) // 周一
    private val term = Term(id = 1, name = "2026-2027 第一学期", startEpochDay = monday.toEpochDay(), totalWeeks = 20)
    private val periods = listOf(PeriodTime(1, "08:00", "08:45"), PeriodTime(2, "08:55", "09:40"))

    private fun entry(room: String) = TimetableEntry(
        slot = CourseSlot(
            id = 1, courseId = 1, termId = 1, dayOfWeek = 1,
            startSection = 1, endSection = 1, weekMask = WeekMask.bit(1), room = room
        ),
        course = Course(id = 1, termId = 1, name = "高等数学")
    )

    private fun dayStartMs(): Long =
        monday.atStartOfDay(ReminderPlanner.ZONE).toInstant().toEpochMilli()

    @Test
    fun `逸夫楼的课往后推十分钟`() {
        val now = dayStartMs() + 7 * 60 * 60_000L // 早上 7 点
        val base = ReminderPlanner.next(listOf(entry("第一教学楼101")), emptyList(), periods, term, now)
        // 推迟 10 分钟：起止整体后移（08:00-08:45 → 08:10-08:55）
        val late = ReminderPlanner.next(
            listOf(entry("逸夫楼A301")), emptyList(), periods, term, now,
            timeOverride = { e ->
                if (LateStartPolicy.minutesOf(e.slot.room, listOf(LateStartRule(listOf("逸夫楼"), 10))) > 0) {
                    8 * 60 + 10 to 8 * 60 + 55
                } else null
            }
        )
        assertEquals(dayStartMs() + 8 * 60 * 60_000L, base!!.startAt)
        assertEquals(base.startAt + 10 * 60_000L, late!!.startAt)
        // 结束时刻同样平移：整节课往后挪，不是压缩
        assertEquals(base.endAt + 10 * 60_000L, late.endAt)
    }

    @Test
    fun `不进推迟名单的楼保持原时刻`() {
        val now = dayStartMs() + 7 * 60 * 60_000L
        val normal = ReminderPlanner.next(
            listOf(entry("第一教学楼101")), emptyList(), periods, term, now,
            timeOverride = { null }
        )
        assertEquals(dayStartMs() + 8 * 60 * 60_000L, normal!!.startAt)
    }
}
