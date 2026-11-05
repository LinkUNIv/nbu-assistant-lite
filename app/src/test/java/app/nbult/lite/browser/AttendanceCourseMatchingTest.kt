package app.nbult.lite.browser

import org.junit.Test
import org.junit.Assert.*

class AttendanceCourseMatchingTest {
    @Test fun codeWhitespaceAndWidthMatch() {
        assertEquals(attendanceCourseName("模拟电子技术"), attendanceCourseName(" ABCD1234_模拟 电子技术\u00a0"))
        assertEquals(attendanceCourseName("大学英语(1)"), attendanceCourseName("大学英语（１）"))
        assertNotEquals(attendanceCourseName("大学英语(1)"), attendanceCourseName("大学英语(2)"))
    }
    @Test fun fuzzyNeedsTimeAndRejectsAmbiguity() {
        val target = listOf(AttendanceTarget("模拟电子技术", "10:00", "11:35"))
        val row = CourseRow(0,"模拟电子技术A","10:00—11:35","",open=true)
        assertEquals(row, matchingAttendanceCourse(listOf(row),target))
        assertNull(matchingAttendanceCourse(listOf(row.copy(details="")),target))
        assertNull(matchingAttendanceCourse(listOf(row,row.copy(index=1)),target))
        assertNull(matchingAttendanceCourse(listOf(row.copy(title="高等数学")),target))
        assertNull(matchingAttendanceCourse(listOf(row.copy(title="模拟电子技术",details="13:30—15:05")),target))
    }
    @Test fun separateOccurrencesOfSameCourseHaveDifferentKeys() {
        val slot = app.nbult.lite.data.db.CourseSlot(id=1,courseId=1,termId=1,dayOfWeek=1,startSection=1,endSection=2,weekMask=1)
        val entry = app.nbult.lite.domain.TimetableEntry(slot, app.nbult.lite.data.db.Course(id=1,termId=1,name="课程"))
        assertNotEquals(attendanceSessionKey(entry),attendanceSessionKey(entry.copy(slot=slot.copy(startSection=5,endSection=6))))
        assertNotEquals(attendanceSessionKey(entry),attendanceSessionKey(entry.copy(slot=slot.copy(dayOfWeek=2))))
    }
}
