package app.nbult.lite.data.remote

import app.nbult.lite.domain.DateUtil
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import app.nbult.lite.util.UserAgent

data class TermStartGuess(
    val startEpochDay: Long,
    val sourceTitle: String,
    val sourceUrl: String,
    val evidence: String
)

class TermStartResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun resolve(): Result<TermStartGuess?> = withContext(Dispatchers.IO) {
        runCatching {
            val today = DateUtil.todayEpochDay()
            val lowerBound = today - 160
            val upperBound = today + 30

            val candidates = mutableListOf<Triple<String, String, String>>()

            for (column in NewsColumns.ALL) {
                val html = runCatching { get(column.listUrl) }.getOrNull() ?: continue
                val doc = Jsoup.parse(html, column.listUrl)
                for (link in doc.select("a[href*=info/]")) {
                    val title = link.text().trim()
                    if (title.length < 6) continue
                    if (KEYWORDS.none { title.contains(it) }) continue
                    val url = link.absUrl("href")
                    if (url.isBlank()) continue
                    val date = Regex("""\d{4}-\d{2}-\d{2}""")
                        .find(link.parent()?.text().orEmpty())?.value.orEmpty()
                    candidates += Triple(title, url, date)
                    if (candidates.size >= 8) break
                }
            }

            for ((title, url, listDate) in candidates) {
                val html = runCatching { get(url) }.getOrNull() ?: continue
                val text = Jsoup.parse(html, url).text()

                explicitDate(text)?.let { epochDay ->
                    if (epochDay in lowerBound..upperBound) {
                        return@runCatching TermStartGuess(
                            startEpochDay = DateUtil.mondayOfWeek(epochDay),
                            sourceTitle = title,
                            sourceUrl = url,
                            evidence = "正文日期 ${DateUtil.formatEpochDay(epochDay)}"
                        )
                    }
                }

                if (title.contains("开学") || title.contains("开课") || title.contains("上课")) {
                    parseDate(listDate)?.let { epochDay ->
                        if (epochDay in lowerBound..upperBound) {
                            return@runCatching TermStartGuess(
                                startEpochDay = DateUtil.mondayOfWeek(epochDay),
                                sourceTitle = title,
                                sourceUrl = url,
                                evidence = "文章日期 ${DateUtil.formatEpochDay(epochDay)}"
                            )
                        }
                    }
                }
            }
            null
        }
    }

    private fun explicitDate(text: String): Long? {
        FULL_DATE.find(text)?.let { match ->
            val y = match.groupValues[1].toInt()
            val m = match.groupValues[2].toInt()
            val d = match.groupValues[3].toInt()
            return toEpochDay(y, m, d)
        }
        SHORT_DATE.find(text)?.let { match ->
            val m = match.groupValues[1].toInt()
            val d = match.groupValues[2].toInt()
            val year = guessYear(m)
            return toEpochDay(year, m, d)
        }
        return null
    }

    private fun guessYear(month: Int): Int {
        val today = DateUtil.todayEpochDay()
        val calendar = java.util.Calendar.getInstance(app.nbult.lite.domain.AppClock.ZONE)
        calendar.timeInMillis = today * 86_400_000L
        val currentYear = calendar.get(java.util.Calendar.YEAR)
        val currentMonth = calendar.get(java.util.Calendar.MONTH) + 1
        return if (month > currentMonth + 4) currentYear - 1 else currentYear
    }

    private fun parseDate(text: String): Long? {
        val match = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(text) ?: return null
        return toEpochDay(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt()
        )
    }

    private fun toEpochDay(year: Int, month: Int, day: Int): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        val calendar = java.util.Calendar.getInstance(app.nbult.lite.domain.AppClock.ZONE)
        calendar.clear()
        calendar.set(year, month - 1, day, 0, 0, 0)
        val millis = calendar.timeInMillis
        return DateUtil.epochDayOf(millis)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgent.default)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: return ""
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("GBK"))
            }
        }
    }

    companion object {
        private val KEYWORDS = listOf("开学", "开课", "新学期", "校历", "上课", "报到")

        private val FULL_DATE = Regex(
            """(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日[^。；！\n]{0,14}(?:开始)?(?:上课|开课|行课)"""
        )

        private val SHORT_DATE = Regex(
            """(\d{1,2})\s*月\s*(\d{1,2})\s*日[^。；！\n]{0,10}(?:开始)?(?:上课|开课|行课)"""
        )
    }
}
