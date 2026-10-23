package app.nbult.lite.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 校历事件类型，决定列表配色与图标语义。 */
enum class CalendarEventType { HOLIDAY, REGISTER, CLASS, SPORT, MAKEUP, EXAM, OTHER }

data class CalendarEvent(
    val startDay: Long,
    val endDay: Long,
    val title: String,
    val type: CalendarEventType
) {
    val isRange: Boolean get() = endDay > startDay
    fun covers(day: Long): Boolean = day in startDay..endDay
}

data class CalendarSemester(
    val name: String,
    val startDay: Long,
    val endDay: Long,
    val totalWeeks: Int
) {
    fun covers(day: Long): Boolean = day in startDay..endDay

    /** 该日期属于第几周；不在学期内返回 null。 */
    fun weekOf(day: Long): Int? {
        if (!covers(day)) return null
        return ((day - startDay) / 7 + 1).toInt().coerceIn(1, totalWeeks)
    }

    fun weekStart(week: Int): Long = startDay + (week - 1) * 7L
}

data class CalendarYear(
    val id: String,
    val title: String,
    val verified: Boolean,
    val source: String,
    val semesters: List<CalendarSemester>,
    val events: List<CalendarEvent>
) {
    fun semesterOf(day: Long): CalendarSemester? = semesters.firstOrNull { it.covers(day) }

    fun eventsOn(day: Long): List<CalendarEvent> = events.filter { it.covers(day) }

    fun upcoming(day: Long, limit: Int = 3): List<CalendarEvent> =
        events.filter { it.startDay > day }.sortedBy { it.startDay }.take(limit)
}

data class PeriodTableItem(val idx: Int, val start: String, val end: String)

data class PeriodTable(
    val id: String,
    val name: String,
    val office: String,
    val items: List<PeriodTableItem>,
    val note: String
)

data class SchoolCalendar(
    val years: List<CalendarYear>,
    val periods: List<PeriodTable>,
    val updatedAt: String,
    val notice: String
) {
    /** 选出覆盖该日期的学年；找不到则回退到最近一个学年。 */
    fun yearOf(day: Long): CalendarYear? {
        years.firstOrNull { y -> y.semesters.any { it.covers(day) } || y.events.any { it.covers(day) } }
            ?.let { return it }
        return years.lastOrNull()
    }

    fun byId(id: String): CalendarYear? = years.firstOrNull { it.id == id }
}

/** 校历日期工具：统一按东八区，避免设备时区导致错日。 */
object CalendarDates {

    private val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = AppClock.ZONE
        isLenient = false
    }

    fun parse(text: String): Long =
        runCatching { format.parse(text)?.let { DateUtil.epochDayOf(it.time) } }.getOrNull() ?: 0L

    private fun calendarOf(day: Long): Calendar {
        val cal = Calendar.getInstance(AppClock.ZONE)
        cal.timeInMillis = day * 86_400_000L
        return cal
    }

    fun monthDayShort(day: Long): String {
        val cal = calendarOf(day)
        return "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    fun monthDay(day: Long): String {
        val cal = calendarOf(day)
        return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    }

    fun weekday(day: Long): String =
        DateUtil.dayNames[DateUtil.dayOfWeekIso(day) - 1]

    fun rangeText(start: Long, end: Long): String =
        if (start == end) monthDay(start) else "${monthDay(start)} - ${monthDay(end)}"

    fun monthKey(day: Long): String {
        val cal = calendarOf(day)
        return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun monthLabel(day: Long): String {
        val cal = calendarOf(day)
        return "${cal.get(Calendar.YEAR)} 年 ${cal.get(Calendar.MONTH) + 1} 月"
    }
}
