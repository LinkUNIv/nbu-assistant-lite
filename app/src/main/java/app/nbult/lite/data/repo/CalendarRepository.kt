package app.nbult.lite.data.repo

import android.content.Context
import app.nbult.lite.domain.CalendarDates
import app.nbult.lite.domain.CalendarEvent
import app.nbult.lite.domain.CalendarEventType
import app.nbult.lite.domain.CalendarSemester
import app.nbult.lite.domain.CalendarYear
import app.nbult.lite.domain.PeriodTable
import app.nbult.lite.domain.PeriodTableItem
import app.nbult.lite.data.pack.ResourcePackStore
import app.nbult.lite.domain.SchoolCalendar
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 校历数据源。
 *
 * 数据来源有两处：APK 内置的 `assets/calendar/nbu_calendar.json`，
 * 以及下载到 `filesDir/pack/` 的资源包（路径相同，优先使用）。
 * 校历每学年必变，走资源包更新可以不必发版 —— 学校按学年发通知，改数据比改 APK 快得多。
 */
class CalendarRepository(private val context: Context) {

    @Volatile
    private var cached: SchoolCalendar? = null

    suspend fun load(): SchoolCalendar = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        // 先看有没有下载的资源包，没有再读内置。内置那份永远能跑，这是底线
        val text = ResourcePackStore.readText(context, ASSET_PATH)
            ?: error("校历数据缺失：$ASSET_PATH")
        val parsed = parse(text)
        cached = parsed
        AppLog.log("Calendar", "校历加载完成：${parsed.years.size} 个学年，${parsed.periods.size} 套作息")
        parsed
    }

    private fun parse(text: String): SchoolCalendar {
        val root = JSONObject(text)
        val years = root.getJSONArray("years").let { array ->
            (0 until array.length()).map { i -> parseYear(array.getJSONObject(i)) }
        }
        val periods = root.optJSONArray("periods")?.let { array ->
            (0 until array.length()).map { i -> parsePeriodTable(array.getJSONObject(i)) }
        }.orEmpty()
        return SchoolCalendar(
            years = years,
            periods = periods,
            updatedAt = root.optString("updatedAt"),
            notice = root.optString("notice")
        )
    }

    private fun parseYear(o: JSONObject): CalendarYear {
        val semesters = o.getJSONArray("semesters").let { array ->
            (0 until array.length()).map { i ->
                val s = array.getJSONObject(i)
                CalendarSemester(
                    name = s.optString("name"),
                    startDay = CalendarDates.parse(s.getString("startDate")),
                    endDay = CalendarDates.parse(s.getString("endDate")),
                    totalWeeks = s.optInt("totalWeeks", 19)
                )
            }
        }
        val events = o.getJSONArray("events").let { array ->
            (0 until array.length()).map { i ->
                val e = array.getJSONObject(i)
                CalendarEvent(
                    startDay = CalendarDates.parse(e.getString("start")),
                    endDay = CalendarDates.parse(e.optString("end", e.getString("start"))),
                    title = e.optString("title"),
                    type = typeOf(e.optString("type"))
                )
            }.sortedBy { it.startDay }
        }
        return CalendarYear(
            id = o.getString("id"),
            title = o.optString("title"),
            verified = o.optBoolean("verified", false),
            source = o.optString("source"),
            semesters = semesters,
            events = events
        )
    }

    private fun parsePeriodTable(o: JSONObject): PeriodTable {
        val items = o.getJSONArray("items").let { array ->
            (0 until array.length()).map { i ->
                val p = array.getJSONObject(i)
                PeriodTableItem(
                    idx = p.getInt("idx"),
                    start = p.getString("start"),
                    end = p.getString("end")
                )
            }
        }
        return PeriodTable(
            id = o.getString("id"),
            name = o.optString("name"),
            office = o.optString("office"),
            items = items,
            note = o.optString("note")
        )
    }

    private fun typeOf(raw: String): CalendarEventType = when (raw) {
        "holiday" -> CalendarEventType.HOLIDAY
        "register" -> CalendarEventType.REGISTER
        "class" -> CalendarEventType.CLASS
        "sport" -> CalendarEventType.SPORT
        "makeup" -> CalendarEventType.MAKEUP
        "exam" -> CalendarEventType.EXAM
        else -> CalendarEventType.OTHER
    }

    private companion object {
        const val ASSET_PATH = "calendar/nbu_calendar.json"
    }
}
