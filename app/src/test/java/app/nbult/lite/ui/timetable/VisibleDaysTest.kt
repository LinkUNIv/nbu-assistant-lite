package app.nbult.lite.ui.timetable

import app.nbult.lite.data.db.Course
import app.nbult.lite.data.db.CourseSlot
import app.nbult.lite.domain.TimetableEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 每一周按**自己**的课决定显示几列。
 *
 * 之前分页里所有页共用当前周的列数：从「五天周」滑到「六天周」时，下一页多出的
 * 周六仍按五列宽度排版，于是压到周五上（0.38.15 → 0.38.16 修的那个重叠 bug）。
 */
class VisibleDaysTest {

    private fun entry(day: Int): TimetableEntry = TimetableEntry(
        slot = CourseSlot(
            id = day.toLong(), courseId = 1, termId = 1, dayOfWeek = day,
            startSection = 1, endSection = 2, weekMask = 1
        ),
        course = Course(id = 1, termId = 1, name = "课$day")
    )

    private fun state(full: Boolean = false) = TimetableUiState(
        weeks = listOf(emptyList(), emptyList()),
        showFullTimetable = full
    )

    @Test
    fun `五天周只出周一到周五`() {
        assertEquals(listOf(1, 2, 3, 4, 5), state().daysFor(listOf(entry(1), entry(3))))
    }

    @Test
    fun `六天周多出周六`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), state().daysFor(listOf(entry(1), entry(6))))
    }

    @Test
    fun `周日有课时也带上周日`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 7), state().daysFor(listOf(entry(7))))
    }

    @Test
    fun `显示整周时固定七天`() {
        assertEquals((1..7).toList(), state(full = true).daysFor(emptyList()))
    }
}
