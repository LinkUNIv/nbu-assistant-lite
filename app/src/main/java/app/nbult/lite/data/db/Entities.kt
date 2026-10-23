package app.nbult.lite.data.db

import androidx.room.ColumnInfo
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
 * 语义是「**这一周的这节课**怎么办」，与「课程本身怎么排」彻底分开：
 *
 * - `weekMask` 是**源周**（哪几周的这一节被改动）。写入时恒为当前周这一位，
 *   但保留位图形态，将来要支持"一次改多周"不必再动表。
 * - `toWeek` 是**目标周**（仅调课用）。0 表示与原周相同（同周换天），
 *   同时兼容 0.16.x 及更早的旧数据 —— 那些记录本来就没有跨周概念。
 * - 若 `toWeek != weekMask` 的那一周，这节课就是"调到未来某周"，
 *   源周只留一个灰色占位，目标周才会出现「调」块。
 *
 * 关键约束：任何覆盖都**不改 `course_slot`**。所以调课不会动到课程本身的周次设置，
 * 也不会影响同一门课的其他时段。
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
    /** 源周位图，位 (week-1) 为 1 表示该周的这一节被改动。见 `domain.WeekMask`。 */
    val weekMask: Int,
    /**
     * 目标周（仅 KIND_MOVE）。0 表示与源周相同，即"本周内换天"。
     *
     * 必须显式声明 defaultValue：DB 4→5 用 `ALTER TABLE ADD COLUMN ... DEFAULT 0` 加列，
     * 而 Room 会比对列的默认值 —— 实体不声明的话，迁移后校验会因
     * 「期望 null、实际 '0'」而失败，启动即崩。
     */
    @ColumnInfo(defaultValue = "0") val toWeek: Int = 0,
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
