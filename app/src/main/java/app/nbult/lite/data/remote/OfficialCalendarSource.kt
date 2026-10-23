package app.nbult.lite.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit
import app.nbult.lite.util.UserAgent

/**
 * 官方校历页（`www.nbu.edu.cn/xl.htm`）。
 *
 * 这个页面**只有图片**：每个学年一张 JPG，没有表格也没有文字日期。
 * 所以这里只做两件事：抓出「学年 → 图片地址」的映射，以及把结果缓存 7 天。
 * 结构化的周次/假期仍然走本地 `assets/calendar/nbu_calendar.json`，
 * 抓取失败或该学年未发布时，界面必须明确说"官方未发布"，不能拿旧数据冒充。
 */
object OfficialCalendarSource {

    const val PAGE_URL = "https://www.nbu.edu.cn/xl.htm"
    private const val CACHE_FILE = "official-calendar.json"
    private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    data class Entry(val year: String, val imageUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 先给缓存（有就立刻显示），过期再后台刷新由调用方决定；这里返回最新可用结果。 */
    suspend fun load(context: Context, forceRefresh: Boolean = false): Result<List<Entry>> =
        withContext(Dispatchers.IO) {
            val cached = readCache(context)
            val fresh = cached != null &&
                System.currentTimeMillis() - cached.first < TTL_MILLIS &&
                cached.second.isNotEmpty()
            if (fresh && !forceRefresh) return@withContext Result.success(cached!!.second)

            runCatching { fetch() }
                .onSuccess { writeCache(context, it) }
                .recoverCatching {
                    // 网络失败时退回旧缓存，仍然可用（界面上会标注抓取时间）
                    cached?.second?.takeIf { list -> list.isNotEmpty() }
                        ?: throw it
                }
        }

    private fun fetch(): List<Entry> {
        val request = Request.Builder()
            .url(PAGE_URL)
            .header("User-Agent", UserAgent.browserLike)
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("校历页返回 ${response.code}")
            response.body?.string().orEmpty()
        }
        val doc = Jsoup.parse(html, PAGE_URL)
        val entries = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val text = a.text().trim()
            if (!href.contains(".jpg", ignoreCase = true)) return@mapNotNull null
            if (!text.contains("学年")) return@mapNotNull null
            Entry(year = text, imageUrl = href)
        }.distinctBy { it.imageUrl }

        if (entries.isEmpty()) error("校历页结构已变化，未找到图片链接")
        // 页面按学年倒序；这里再排一次，保证最新学年在最前
        return entries.sortedByDescending { it.year.take(4).toIntOrNull() ?: 0 }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun readCache(context: Context): Pair<Long, List<Entry>>? = runCatching {
        val file = cacheFile(context)
        if (!file.isFile) return@runCatching null
        val root = JSONObject(file.readText())
        val at = root.optLong("fetchedAt")
        val array = root.optJSONArray("entries") ?: JSONArray()
        val list = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(Entry(o.optString("year"), o.optString("imageUrl")))
        }
        at to list
    }.getOrNull()

    private fun writeCache(context: Context, entries: List<Entry>) = runCatching {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().put("year", e.year).put("imageUrl", e.imageUrl))
        }
        cacheFile(context).writeText(
            JSONObject()
                .put("fetchedAt", System.currentTimeMillis())
                .put("entries", array)
                .toString()
        )
    }.getOrNull()
}
