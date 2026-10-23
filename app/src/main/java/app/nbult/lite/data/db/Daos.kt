package app.nbult.lite.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {

    @Query("SELECT * FROM term WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrentTerm(): Flow<Term?>

    @Query("SELECT * FROM term WHERE isCurrent = 1 LIMIT 1")
    suspend fun currentTerm(): Term?

    @Query("UPDATE term SET isCurrent = 0")
    suspend fun clearCurrentFlag()

    @Insert
    suspend fun insertTerm(term: Term): Long

    @Update
    suspend fun updateTerm(term: Term)

    @Insert
    suspend fun insertCourse(course: Course): Long

    @Insert
    suspend fun insertSlots(slots: List<CourseSlot>)

    @Query("SELECT * FROM course WHERE termId = :termId")
    fun observeCourses(termId: Long): Flow<List<Course>>

    @Query("SELECT * FROM course_slot WHERE termId = :termId")
    fun observeSlots(termId: Long): Flow<List<CourseSlot>>

    @Query("DELETE FROM course_slot WHERE termId = :termId")
    suspend fun deleteSlots(termId: Long)

    @Query("DELETE FROM course WHERE termId = :termId")
    suspend fun deleteCourses(termId: Long)

    @Query("SELECT * FROM course_slot WHERE id = :id")
    suspend fun slotById(id: Long): CourseSlot?

    @Update
    suspend fun updateSlot(slot: CourseSlot)

    @Delete
    suspend fun deleteSlot(slot: CourseSlot)

    @Query("DELETE FROM course_slot WHERE courseId = :courseId")
    suspend fun deleteSlotsOfCourse(courseId: Long)

    @Query("SELECT * FROM course WHERE id = :id")
    suspend fun courseById(id: Long): Course?

    @Update
    suspend fun updateCourse(course: Course)

    @Delete
    suspend fun deleteCourse(course: Course)

    @Insert
    suspend fun insertSlot(slot: CourseSlot): Long

    @Query("SELECT COUNT(*) FROM course_slot")
    suspend fun slotCount(): Int

    @Query("SELECT * FROM period_time ORDER BY idx")
    fun observePeriods(): Flow<List<PeriodTime>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeriods(periods: List<PeriodTime>)

    // ---- 停课 / 调课 ----

    @Query("SELECT * FROM slot_override")
    fun observeOverrides(): Flow<List<SlotOverride>>

    @Query("SELECT * FROM slot_override WHERE slotId = :slotId")
    suspend fun overridesOfSlot(slotId: Long): List<SlotOverride>

    @Insert
    suspend fun insertOverride(override: SlotOverride): Long

    @Query("DELETE FROM slot_override WHERE slotId = :slotId")
    suspend fun deleteOverridesOfSlot(slotId: Long)

    @Query("UPDATE slot_override SET weekMask = :mask WHERE id = :id")
    suspend fun updateOverrideMask(id: Long, mask: Int)

    @Query("DELETE FROM slot_override WHERE id = :id")
    suspend fun deleteOverrideById(id: Long)

    @Query("DELETE FROM slot_override")
    suspend fun clearOverrides()
}

@Dao
interface NewsDao {

    @Query("SELECT * FROM news ORDER BY publishedAt DESC, id DESC LIMIT 200")
    fun observeAll(): Flow<List<NewsItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<NewsItem>)

    @Query("UPDATE news SET readAt = :timestamp WHERE id = :id")
    suspend fun markRead(id: Long, timestamp: Long)

    @Query("UPDATE news SET contentBlocks = :blocks, coverUrl = :cover, contentFetched = 1 WHERE id = :id")
    suspend fun saveContent(id: Long, blocks: String, cover: String)

    @Query("SELECT * FROM news WHERE id = :id")
    suspend fun findById(id: Long): NewsItem?

    @Query("SELECT MAX(id) FROM news")
    suspend fun maxId(): Long?

    @Query("SELECT COUNT(*) FROM news")
    suspend fun count(): Int

    @Query("DELETE FROM news")
    suspend fun clear()
}
