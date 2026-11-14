package app.nbult.lite.data.repo

import app.nbult.lite.data.db.AppDatabase
import app.nbult.lite.data.db.NewsItem
import app.nbult.lite.data.remote.CrawledItem
import app.nbult.lite.data.remote.NewsColumns
import app.nbult.lite.data.remote.NewsCrawler
import app.nbult.lite.data.remote.NewsSource
import app.nbult.lite.net.NetworkMonitor
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class NewsRepository(
    private val context: android.content.Context,
    database: AppDatabase,
    private val source: NewsSource,
    private val crawler: NewsCrawler,
    private val monitor: NetworkMonitor,
    /**
     * 当前学校。**必须是取值函数而不是构造时的常量** —— 这个仓储在容器里是单例，
     * 切学校时不会重建，传死值会让过滤条件永远停在第一所学校（0.38.5 的跨校混排就是这么来的）。
     */
    private val schoolId: () -> String
) {

    private val dao = database.newsDao()

    fun observe(): Flow<List<NewsItem>> = dao.observeAll(schoolId())

    suspend fun refresh(): Result<Int> = runCatching {
        var total = 0
        var succeeded = 0
        for (column in NewsColumns.all(context)) {
            val run = runCatching { crawler.fetchList(column) }
            run.onSuccess { items ->
                if (items.isNotEmpty()) {
                    dao.upsert(items.map { it.toEntity() })
                    total += items.size
                }
                succeeded++
            }
        }
        AppLog.log("News", "抓取完成：成功栏目=$succeeded, 条目=$total")
        if (succeeded == 0) error("所有栏目均抓取失败")
        monitor.reportSourceResult(true)
        total
    }.onFailure {
        monitor.reportSourceResult(false)
    }

    suspend fun loadContent(id: Long): Result<Unit> = runCatching {
        val item = dao.findById(id) ?: return@runCatching
        if (item.contentFetched && item.contentBlocks.isNotBlank()) return@runCatching

        val article = crawler.fetchArticle(item.url)
        if (article.blocks.isEmpty()) return@runCatching

        val array = JSONArray()
        article.blocks.forEach { block ->
            array.put(JSONObject().put("t", block.type).put("v", block.value))
        }
        dao.saveContent(id, array.toString(), article.coverUrl)
        AppLog.log("News", "正文解析 id=$id 段落=${article.blocks.count { it.type == "p" }} 图片=${article.blocks.count { it.type == "img" }}")
        monitor.reportSourceResult(true)
    }.onFailure {
        monitor.reportSourceResult(false)
    }

    suspend fun importLocal(items: List<NewsItem>) {
        val current = schoolId()
        dao.upsert(items.map { it.copy(schoolId = current) })
    }

    suspend fun markRead(id: Long) = dao.markRead(id, System.currentTimeMillis())

    suspend fun clear() = dao.clear(schoolId())

    suspend fun count(): Int = dao.count(schoolId())

    private fun CrawledItem.toEntity() = NewsItem(
        id = id,
        schoolId = schoolId(),
        columnId = columnId,
        columnName = columnName,
        title = title,
        url = url,
        publishedAt = publishedAt,
        summary = "",
        source = source,
        fetchedAt = System.currentTimeMillis()
    )
}
