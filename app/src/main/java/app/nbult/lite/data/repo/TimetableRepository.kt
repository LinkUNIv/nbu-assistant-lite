package app.nbult.lite.data.repo

import app.nbult.lite.data.SettingsStore
import app.nbult.lite.data.db.AppDatabase
import app.nbult.lite.data.db.Course
import app.nbult.lite.data.db.CourseSlot
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.data.db.SlotOverride
import app.nbult.lite.data.db.Term
import app.nbult.lite.domain.TimetableEntry
import app.nbult.lite.domain.WeekMask
import app.nbult.lite.parser.ParseResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableRepository(
    private val database: AppDatabase,
    private val settings: SettingsStore
) {

    private val dao = database.timetableDao()

    data class Snapshot(
        val term: Term?,
        val entries: List<TimetableEntry>,
        val periods: List<PeriodTime>,
        val overrides: List<SlotOverride> = emptyList()
    )

    fun observeSnapshot(): Flow<Snapshot> =
        combine(
            dao.observeCurrentTerm(),
            dao.observePeriods(),
            dao.observeOverrides()
        ) { term, periods, overrides -> Triple(term, periods, overrides) }
            .flatMapLatest { (term, periods, overrides) ->
                if (term == null) {
                    flowOf(Snapshot(null, emptyList(), periods, overrides))
                } else {
                    combine(dao.observeCourses(term.id), dao.observeSlots(term.id)) { courses, slots ->
                        val byId = courses.associateBy { it.id }
                        val entries = slots.mapNotNull { slot ->
                            byId[slot.courseId]?.let { TimetableEntry(slot, it) }
                        }
                        Snapshot(term, entries, periods, overrides)
                    }
                }
            }

    /**
     * 写入/替换某节课在 [fromWeek]..[toWeek] 的覆盖。
     *
     * 先清掉与该区间相交的旧覆盖，避免"停课"与"调课"叠加成两条互相打架的记录。
     */
    suspend fun setOverride(override: SlotOverride) {
        val existing = dao.overridesOfSlot(override.slotId)
        existing.filter { it.fromWeek <= override.toWeek && it.toWeek >= override.fromWeek }
            .forEach { dao.deleteOverrideById(it.id) }
        dao.insertOverride(override)
    }

    suspend fun clearOverrides(slotId: Long) = dao.deleteOverridesOfSlot(slotId)

    suspend fun clearOverride(overrideId: Long) = dao.deleteOverrideById(overrideId)

    suspend fun importResult(
        parsed: ParseResult,
        termName: String,
        startEpochDay: Long,
        totalWeeks: Int,
        replace: Boolean = true
    ): Int {
        val termId = ensureTerm(termName, startEpochDay, totalWeeks)

        if (replace) {
            dao.deleteSlots(termId)
            dao.deleteCourses(termId)
        }

        val grouped = parsed.slots.groupBy { it.name }
        var inserted = 0

        grouped.forEach { (name, slots) ->
            val first = slots.first()
            val courseId = dao.insertCourse(
                Course(
                    termId = termId,
                    name = name,
                    teacher = first.teacher,
                    colorSeed = name.hashCode() and 0x7fffffff
                )
            )
            val rows = slots.map { slot ->
                CourseSlot(
                    courseId = courseId,
                    termId = termId,
                    dayOfWeek = slot.dayOfWeek,
                    startSection = slot.startSection,
                    endSection = slot.endSection,
                    weekMask = if (slot.weekMask == 0) WeekMask.range(1, 16) else slot.weekMask,
                    room = slot.room
                )
            }
            dao.insertSlots(rows)
            inserted += rows.size
        }
        return inserted
    }

    suspend fun ensureTerm(name: String, startEpochDay: Long, totalWeeks: Int): Long {
        val existing = dao.currentTerm()
        if (existing != null) {
            if (existing.startEpochDay != startEpochDay || existing.totalWeeks != totalWeeks) {
                dao.updateTerm(existing.copy(startEpochDay = startEpochDay, totalWeeks = totalWeeks))
            }
            return existing.id
        }
        dao.clearCurrentFlag()
        return dao.insertTerm(
            Term(name = name, startEpochDay = startEpochDay, totalWeeks = totalWeeks, isCurrent = true)
        )
    }

    suspend fun updateTerm(term: Term) = dao.updateTerm(term)

    suspend fun currentTerm(): Term? = dao.currentTerm()

    suspend fun addCourse(
        termId: Long,
        name: String,
        teacher: String,
        room: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        weekMask: Int
    ): Long {
        val courseId = dao.insertCourse(
            Course(
                termId = termId,
                name = name,
                teacher = teacher,
                colorSeed = name.hashCode() and 0x7fffffff
            )
        )
        dao.insertSlot(
            CourseSlot(
                courseId = courseId,
                termId = termId,
                dayOfWeek = dayOfWeek,
                startSection = startSection,
                endSection = endSection,
                weekMask = if (weekMask == 0) WeekMask.range(1, 16) else weekMask,
                room = room
            )
        )
        return courseId
    }

    suspend fun slotById(id: Long): CourseSlot? = dao.slotById(id)

    suspend fun updateSlot(slot: CourseSlot) = dao.updateSlot(slot)

    suspend fun deleteSlot(slot: CourseSlot) = dao.deleteSlot(slot)

    suspend fun deleteCourse(courseId: Long) {
        dao.deleteSlotsOfCourse(courseId)
        dao.courseById(courseId)?.let { dao.deleteCourse(it) }
    }

    suspend fun updateCourse(course: Course) = dao.updateCourse(course)

    suspend fun applyPeriodPreset(periods: List<Pair<String, String>>) {
        dao.upsertPeriods(
            periods.mapIndexed { index, pair ->
                PeriodTime(idx = index + 1, startTime = pair.first, endTime = pair.second)
            }
        )
    }

    suspend fun clearAll() {
        val term = dao.currentTerm() ?: return
        dao.clearOverrides()
        dao.deleteSlots(term.id)
        dao.deleteCourses(term.id)
    }

    suspend fun slotCount(): Int = dao.slotCount()
}
