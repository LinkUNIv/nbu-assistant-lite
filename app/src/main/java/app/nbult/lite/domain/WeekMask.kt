package app.nbult.lite.domain

import java.util.Calendar

object WeekMask {

    const val MAX_WEEKS = 30

    enum class Parity { ALL, ODD, EVEN }

    fun of(vararg weeks: Int): Int = weeks.fold(0) { acc, w -> acc or bit(w) }

    fun range(from: Int, to: Int): Int {
        val start = minOf(from, to).coerceIn(1, MAX_WEEKS)
        val end = maxOf(from, to).coerceIn(1, MAX_WEEKS)
        var mask = 0
        for (w in start..end) mask = mask or bit(w)
        return mask
    }

    fun oddOf(from: Int, to: Int): Int {
        val start = minOf(from, to).coerceIn(1, MAX_WEEKS)
        val end = maxOf(from, to).coerceIn(1, MAX_WEEKS)
        var mask = 0
        for (w in start..end) if (w % 2 == 1) mask = mask or bit(w)
        return mask
    }

    fun evenOf(from: Int, to: Int): Int {
        val start = minOf(from, to).coerceIn(1, MAX_WEEKS)
        val end = maxOf(from, to).coerceIn(1, MAX_WEEKS)
        var mask = 0
        for (w in start..end) if (w % 2 == 0) mask = mask or bit(w)
        return mask
    }

    fun build(from: Int, to: Int, parity: Parity): Int = when (parity) {
        Parity.ALL -> range(from, to)
        Parity.ODD -> oddOf(from, to)
        Parity.EVEN -> evenOf(from, to)
    }

    fun startWeek(mask: Int): Int = weeksOf(mask).firstOrNull() ?: 1

    fun endWeek(mask: Int): Int = weeksOf(mask).lastOrNull() ?: 16

    fun parityOf(mask: Int): Parity {
        val weeks = weeksOf(mask)
        if (weeks.isEmpty()) return Parity.ALL
        val allOdd = weeks.all { it % 2 == 1 }
        val allEven = weeks.all { it % 2 == 0 }
        return when {
            allOdd && weeks.size >= 2 -> Parity.ODD
            allEven && weeks.size >= 2 -> Parity.EVEN
            else -> Parity.ALL
        }
    }

    fun bit(week: Int): Int =
        if (week in 1..MAX_WEEKS) 1 shl (week - 1) else 0

    fun isOn(mask: Int, week: Int): Boolean =
        week in 1..MAX_WEEKS && (mask shr (week - 1)) and 1 == 1

    fun count(mask: Int): Int = Integer.bitCount(mask)

    fun weeksOf(mask: Int): List<Int> = (1..MAX_WEEKS).filter { isOn(mask, it) }

    fun label(mask: Int): String {
        val weeks = weeksOf(mask)
        if (weeks.isEmpty()) return "未设置周次"
        val parity = parityOf(mask)
        if (parity != Parity.ALL) {
            return "${weeks.first()}-${weeks.last()}周" + if (parity == Parity.ODD) "(单)" else "(双)"
        }
        if (weeks.size == 1) return "${weeks.first()}周"
        val isConsecutive = weeks.zipWithNext().all { (a, b) -> b - a == 1 }
        if (isConsecutive) return "${weeks.first()}-${weeks.last()}周"
        return weeks.joinToString(",") + "周"
    }
}

object DateUtil {

    fun todayEpochDay(): Long = epochDayOf(AppClock.now())

    fun epochDayOf(millis: Long): Long {
        val cal = Calendar.getInstance(AppClock.ZONE)
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val offset = cal.timeZone.getOffset(cal.timeInMillis)
        return Math.floorDiv(cal.timeInMillis + offset, 86_400_000L)
    }

    fun dayOfWeekIso(epochDay: Long): Int {
        val cal = Calendar.getInstance(AppClock.ZONE)
        cal.timeInMillis = epochDay * 86_400_000L
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    fun mondayOfWeek(epochDay: Long): Long = epochDay - (dayOfWeekIso(epochDay) - 1)

    fun formatEpochDay(epochDay: Long): String {
        val cal = Calendar.getInstance(AppClock.ZONE)
        cal.timeInMillis = epochDay * 86_400_000L
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    fun formatTime(millis: Long): String {
        val cal = Calendar.getInstance(AppClock.ZONE)
        cal.timeInMillis = millis
        return "%02d:%02d:%02d".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND)
        )
    }

    val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
}
