package app.nbult.lite.domain

/** 显示周按首日划分；课程、单双周和调课仍使用其真实教学周。 */
object WeekLayout {
    fun displayWeek(academicWeek: Int, day: Int, sundayFirst: Boolean): Int =
        academicWeek + if (sundayFirst && day == 7) 1 else 0

    fun academicWeek(displayWeek: Int, day: Int, sundayFirst: Boolean): Int =
        displayWeek - if (sundayFirst && day == 7) 1 else 0

    fun start(monday: Long, week: Int, sundayFirst: Boolean): Long =
        monday + (week - 1) * 7L - if (sundayFirst) 1L else 0L

    fun date(monday: Long, week: Int, day: Int, sundayFirst: Boolean): Long =
        monday + (academicWeek(week, day, sundayFirst) - 1) * 7L + day - 1
}
