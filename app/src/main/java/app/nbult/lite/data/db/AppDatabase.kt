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
    version = 3,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nbu-lite.db")
                .addMigrations(MIGRATION_2_3)
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
