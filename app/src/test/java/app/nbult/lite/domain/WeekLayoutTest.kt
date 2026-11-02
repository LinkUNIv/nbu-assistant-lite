package app.nbult.lite.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeekLayoutTest {
    private val monday = LocalDate.parse("2026-10-26").toEpochDay()
    @Test fun sundayWeekHasCorrectDates() {
        assertEquals("2026-10-25", LocalDate.ofEpochDay(WeekLayout.start(monday, 1, true)).toString())
        assertEquals("2026-10-31", LocalDate.ofEpochDay(WeekLayout.date(monday, 1, 6, true)).toString())
        assertEquals("2026-11-01", LocalDate.ofEpochDay(WeekLayout.start(monday, 2, true)).toString())
    }
    @Test fun mondayWeekHasCorrectDates() {
        assertEquals("2026-10-26", LocalDate.ofEpochDay(WeekLayout.start(monday, 1, false)).toString())
        assertEquals("2026-11-01", LocalDate.ofEpochDay(WeekLayout.date(monday, 1, 7, false)).toString())
    }
    @Test fun sundayColumnUsesPreviousAcademicWeek() {
        assertEquals(1, WeekLayout.academicWeek(2, 7, true))
        assertEquals(2, WeekLayout.academicWeek(2, 1, true))
    }
    @Test fun widgetSundayAndTomorrowLandOnSameSundayFirstPage() {
        assertEquals(2, WeekLayout.displayWeek(1, 7, true))
        assertEquals(2, WeekLayout.displayWeek(2, 1, true))
        assertEquals(1, WeekLayout.displayWeek(1, 7, false))
    }
    @Test fun datesStayConsecutiveAcrossYearBoundary() {
        val start = LocalDate.parse("2026-12-28").toEpochDay()
        val days = listOf(7, 1, 2, 3, 4, 5, 6)
        val dates = days.map { WeekLayout.date(start, 1, it, true) }
        dates.zipWithNext().forEach { (a, b) -> assertEquals(a + 1, b) }
    }
}
