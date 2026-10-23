package app.nbult.lite.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Term::class,
        Course::class,
        CourseSlot::class,
        PeriodTime::class,
        NewsItem::class,
        SlotOverride::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun timetableDao(): TimetableDao

    abstract fun newsDao(): NewsDao

    companion object {

        /**
         * 2 → 3：只新增 `slot_override` 表，旧表一行不动。
         *
         * 迁移必须保留：这是用户的课表数据，不能因为加了个"停课/调课"功能就清库。
         * （`fallbackToDestructiveMigration` 保留为兜底，但正常情况下它不会触发。）
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `slot_override` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `slotId` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `fromWeek` INTEGER NOT NULL,
                        `toWeek` INTEGER NOT NULL,
                        `toDay` INTEGER NOT NULL,
                        `toStart` INTEGER NOT NULL,
                        `toEnd` INTEGER NOT NULL,
                        `toRoom` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`slotId`) REFERENCES `course_slot`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_slot_override_slotId` " +
                        "ON `slot_override` (`slotId`)"
                )
            }
        }

        /**
         * 3 → 4：`slot_override` 从「周区间」改为「周次位图」。
         *
         * 不能直接 `ALTER TABLE ... ADD COLUMN`：Room 要求实体字段与表列**完全一致**，
         * 多留 `fromWeek`/`toWeek` 会让 schema 校验失败、启动即崩。
         * 也不能 `DROP COLUMN`：minSdk 24 对应的 SQLite 还不支持该语法。
         * 所以走「建新表 → 逐行转换 → 换名」，全程不动 `course_slot` 与 `course`。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `slot_override_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `slotId` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `weekMask` INTEGER NOT NULL,
                        `toDay` INTEGER NOT NULL,
                        `toStart` INTEGER NOT NULL,
                        `toEnd` INTEGER NOT NULL,
                        `toRoom` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`slotId`) REFERENCES `course_slot`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // SQLite 没有现成的「区间转位图」聚合；override 通常只有个位数条，
                // 用游标逐行算最直白，也最不容易写错位运算。
                db.query(
                    "SELECT id, slotId, kind, fromWeek, toWeek, toDay, toStart, toEnd, toRoom, createdAt " +
                        "FROM `slot_override`"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val from = cursor.getInt(3)
                        val to = cursor.getInt(4)
                        val lo = minOf(from, to).coerceIn(1, 30)
                        val hi = maxOf(from, to).coerceIn(1, 30)
                        var mask = 0
                        for (w in lo..hi) mask = mask or (1 shl (w - 1))
                        db.execSQL(
                            "INSERT INTO `slot_override_new` " +
                                "(id, slotId, kind, weekMask, toDay, toStart, toEnd, toRoom, createdAt) " +
                                "VALUES (?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(
                                cursor.getLong(0),
                                cursor.getLong(1),
                                cursor.getString(2) ?: "suspend",
                                mask,
                                cursor.getInt(5),
                                cursor.getInt(6),
                                cursor.getInt(7),
                                cursor.getString(8) ?: "",
                                cursor.getLong(9)
                            )
                        )
                    }
                }

                db.execSQL("DROP TABLE `slot_override`")
                db.execSQL("ALTER TABLE `slot_override_new` RENAME TO `slot_override`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_slot_override_slotId` " +
                        "ON `slot_override` (`slotId`)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nbu-lite.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.beginTransaction()
                        try {
                            DEFAULT_PERIODS.forEach { (idx, pair) ->
                                db.execSQL(
                                    "INSERT INTO period_time (idx, startTime, endTime) VALUES (?,?,?)",
                                    arrayOf<Any>(idx, pair.first, pair.second)
                                )
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                })
                .build()

        private val DEFAULT_PERIODS: List<Pair<Int, Pair<String, String>>> = listOf(
            1 to ("08:00" to "08:45"),
            2 to ("08:50" to "09:35"),
            3 to ("10:00" to "10:45"),
            4 to ("10:50" to "11:35"),
            5 to ("13:30" to "14:15"),
            6 to ("14:20" to "15:05"),
            7 to ("15:30" to "16:15"),
            8 to ("16:20" to "17:05"),
            9 to ("18:00" to "18:45"),
            10 to ("18:50" to "19:35"),
            11 to ("19:40" to "20:25"),
            12 to ("20:30" to "22:00")
        )
    }
}
