package app.nbult.lite.data.repo

import app.nbult.lite.data.db.*
import app.nbult.lite.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class WeekExpansionCacheTest {
    @Test fun diskRoundTripInvalidationAndCorruption() {
        val file = File.createTempFile("week-cache", ".bin")
        try {
            val term = Term(id=1, name="test", startEpochDay=0, totalWeeks=4)
            val entry = TimetableEntry(CourseSlot(id=1,courseId=1,termId=1,dayOfWeek=1,startSection=3,endSection=4,weekMask=15,room="教室"), Course(id=1,termId=1,name="课程"))
            val overrides = listOf(SlotOverride(id=1,slotId=1,kind=SlotOverride.KIND_MOVE,weekMask=1,toWeek=3,toDay=2,toStart=5,toEnd=6,toRoom="新教室"))
            val expected = (1..4).map { ScheduleService.weekEntries(listOf(entry),it,overrides) }
            assertEquals(expected, WeekExpansionCache(file).get(term,listOf(entry),overrides))
            assertEquals(expected, WeekExpansionCache(file).get(term,listOf(entry),overrides))
            val changed = entry.copy(course=entry.course.copy(name="新课程"))
            val updated = (1..4).map { ScheduleService.weekEntries(listOf(changed),it,emptyList()) }
            assertEquals(updated, WeekExpansionCache(file).get(term,listOf(changed),emptyList()))
            file.writeText("broken")
            val cache = WeekExpansionCache(file)
            assertEquals(updated, cache.get(term,listOf(changed),emptyList()))
            cache.clear(); assertFalse(file.exists())
        } finally { file.delete(); File(file.path+".tmp").delete() }
    }
}
