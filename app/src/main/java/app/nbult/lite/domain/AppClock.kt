package app.nbult.lite.domain

import java.util.TimeZone

object AppClock {

    val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Volatile
    private var offsetMillis: Long = 0L

    fun setOffset(millis: Long) {
        offsetMillis = millis
    }

    fun offset(): Long = offsetMillis

    fun now(): Long = System.currentTimeMillis() + offsetMillis

    fun todayEpochDay(): Long = DateUtil.epochDayOf(now())

    fun deviceZoneId(): String = TimeZone.getDefault().id

    fun deviceIsShanghai(): Boolean {
        val zone = TimeZone.getDefault()
        return zone.rawOffset == 8 * 3600 * 1000 && zone.dstSavings == 0
    }
}

object PeriodPresets {

    data class Preset(val id: String, val name: String, val note: String, val periods: List<Pair<String, String>>)

    val HEADQUARTERS = Preset(
        id = "main",
        name = "主校区（本部）",
        note = "依据学校公布的校历上课时间表",
        periods = listOf(
            "08:00" to "08:45",
            "08:50" to "09:35",
            "10:00" to "10:45",
            "10:50" to "11:35",
            "13:30" to "14:15",
            "14:20" to "15:05",
            "15:30" to "16:15",
            "16:20" to "17:05",
            "18:00" to "18:45",
            "18:50" to "19:35",
            "19:40" to "20:25",
            "20:30" to "22:00"
        )
    )

    val MEISHAN = Preset(
        id = "meishan",
        name = "梅山校区",
        note = "梅山校区综合教学楼作息，比主校区整体后移 30 分钟",
        periods = listOf(
            "08:30" to "09:15",
            "09:20" to "10:05",
            "10:20" to "11:05",
            "11:10" to "11:55",
            "13:40" to "14:25",
            "14:30" to "15:15",
            "15:20" to "16:05",
            "16:10" to "16:55",
            "18:00" to "18:45",
            "18:50" to "19:35",
            "19:40" to "20:25",
            "20:30" to "22:00"
        )
    )

    val ALL = listOf(HEADQUARTERS, MEISHAN)

    fun byId(id: String): Preset = ALL.firstOrNull { it.id == id } ?: HEADQUARTERS
}
