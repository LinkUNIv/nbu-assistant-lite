package app.nbult.lite.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "term")
data class Term(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startEpochDay: Long,
    val totalWeeks: Int = 20,
    val isCurrent: Boolean = true
)

@Entity(tableName = "course")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val name: String,
    val teacher: String = "",
    val colorSeed: Int = 0
)

@Entity(
    tableName = "course_slot",
    foreignKeys = [
        ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("courseId"),
        Index("termId"),
        Index(value = ["termId", "dayOfWeek", "startSection"])
    ]
)
data class CourseSlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val termId: Long,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weekMask: Int,
    val room: String = "",
    val note: String = ""
)

@Entity(tableName = "period_time")
data class PeriodTime(
    @PrimaryKey val idx: Int,
    val startTime: String,
    val endTime: String
)

/**
 * 单次课的状态覆盖：停课 / 调课。
 *
 * 按「周区间」生效，而不是按「一次」：`fromWeek..toWeek` 覆盖连续多周，
 * 「仅本周」就是 fromWeek == toWeek，不需要为每周各插一行。
 *
 * 关键约束：覆盖只影响**某几周的上课安排**，不改 `course_slot` 本身 ——
 * 所以「这周三停课」不会污染其他周，也不会动到原始课表数据。
 */
@Entity(
    tableName = "slot_override",
    foreignKeys = [
        ForeignKey(
            entity = CourseSlot::class,
            parentColumns = ["id"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("slotId")]
)
data class SlotOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slotId: Long,
    /** 见 [KIND_SUSPEND] / [KIND_MOVE] */
    val kind: String,
    val fromWeek: Int,
    val toWeek: Int,
    /** 仅 KIND_MOVE 使用：调课后的星期与节次。 */
    val toDay: Int = 0,
    val toStart: Int = 0,
    val toEnd: Int = 0,
    val toRoom: String = "",
    val createdAt: Long = 0L
) {
    companion object {
        const val KIND_SUSPEND = "suspend"
        const val KIND_MOVE = "move"
    }
}

@Entity(tableName = "news")
data class NewsItem(
    @PrimaryKey val id: Long,
    val columnId: Int,
    val columnName: String,
    val title: String,
    val url: String,
    val publishedAt: String,
    val summary: String,
    val source: String,
    val readAt: Long? = null,
    val fetchedAt: Long = 0L,
    val coverUrl: String = "",
    val contentBlocks: String = "",
    val contentFetched: Boolean = false
)
