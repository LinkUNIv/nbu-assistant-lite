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
 * 按「周次位图」生效（`weekMask`）：一条记录可以只作用于第 3、5、7 周这样的**任意组合**。
 *
 * 早先用 `fromWeek..toWeek` 的连续区间表达，于是只能提供「仅本周 / 本周起每周」两个选项。
 * 但「本周起每周」本质上是修改课程本身的排课，应当去改 `course_slot.weekMask`，
 * 而不是在这里用一条区间覆盖去近似 —— 两套周次语义并存，必然对不上。
 * 现改为直接选周次，语义只有一套。
 *
 * 关键约束：覆盖只影响**选中的那几周**，不改 `course_slot` 本身 ——
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
    /** 生效周次位图，位 (week-1) 为 1 表示该周生效。见 `domain.WeekMask`。 */
    val weekMask: Int,
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
