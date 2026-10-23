package app.nbult.lite.data.remote

import app.nbult.lite.data.db.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import app.nbult.lite.util.UserAgent

object NewsConfig {
    const val FEED_URL = "https://nbu-lite-feed.example.com/news.json"
    const val SOURCE_LABEL = "宁波大学新闻网"
}

class NewsSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetch(url: String = NewsConfig.FEED_URL): List<NewsItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgent.default)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            parse(body)
        }
    }

    fun parse(body: String): List<NewsItem> {
        val root = JSONObject(body)
        val items = root.optJSONArray("items") ?: return emptyList()
        val now = System.currentTimeMillis()
        return buildList {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                val rawId = o.optLong("id", 0L)
                add(
                    NewsItem(
                        id = if (rawId != 0L) rawId else url.hashCode().toLong(),
                        columnId = o.optInt("columnId", 1003),
                        columnName = o.optString("columnName", "宁大要闻"),
                        title = title,
                        url = url,
                        publishedAt = o.optString("publishedAt"),
                        summary = o.optString("summary"),
                        source = o.optString("source", NewsConfig.SOURCE_LABEL),
                        fetchedAt = now
                    )
                )
            }
        }
    }
}
