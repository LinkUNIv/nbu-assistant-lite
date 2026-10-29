package app.nbult.lite.data.remote

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import app.nbult.lite.util.UserAgent

/**
 * 抓取栏目。
 *
 * @param source 展示用的来源名（列表里以「栏目」标签呈现，详情页署名用它）
 * @param idOffset 主键偏移：新闻网与教务处的文章地址都是 `/info/{栏}/{数字}.htm`，
 *   两边的数字会撞车。给不同站点各留一段号段，既不动历史数据，也不会互相覆盖。
 */
data class NewsColumn(
    val id: Int,
    val name: String,
    val listUrl: String,
    val source: String = "宁波大学新闻网",
    val idOffset: Long = 0L
)

object NewsColumns {
    val ALL = listOf(
        NewsColumn(1003, "宁大要闻", "https://news.nbu.edu.cn/ndyw.htm"),
        NewsColumn(1004, "校园动态", "https://news.nbu.edu.cn/xydt.htm"),
        NewsColumn(1005, "媒体宁大", "https://news.nbu.edu.cn/mtnd.htm"),
        NewsColumn(1008, "风华人物", "https://news.nbu.edu.cn/fhrw.htm"),
        // 教务处通知公告：与新闻网混合成一条时间线，教务类信息（教学任务、考试、培养方案）
        // 才是学生真正要盯的，之前完全缺席。
        NewsColumn(
            id = 2001,
            name = "教务处通告",
            listUrl = "https://jwc.nbu.edu.cn/tzgg1.htm",
            source = "宁波大学教务处",
            idOffset = 3_000_000_000L
        )
    )

    /** 列表页顶部展示的来源名（去重后按出现顺序）。 */
    val sources: List<String> = ALL.map { it.source }.distinct()
}

data class CrawledItem(
    val id: Long,
    val columnId: Int,
    val columnName: String,
    val title: String,
    val url: String,
    val publishedAt: String,
    val source: String
)

data class ContentBlock(val type: String, val value: String)

data class ArticleContent(
    val blocks: List<ContentBlock>,
    val coverUrl: String,
    val publishedAt: String
)

class NewsCrawler(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    suspend fun fetchList(column: NewsColumn): List<CrawledItem> = withContext(Dispatchers.IO) {
        val html = get(column.listUrl)
        val doc = Jsoup.parse(html, column.listUrl)

        val result = LinkedHashMap<Long, CrawledItem>()
        // 两种文章地址都要认：`info/1129/18266.htm` 与
        // `content.jsp?urltype=news.NewsContentUrl&wbtreeid=..&wbnewsid=18266`。
        // 后者是同一批文章在栏目页里的另一种写法，漏了会少抓一批。
        // 属性值含 "=" 时必须加引号，否则 CSS 选择器解析会有歧义
        for (link in doc.select("""a[href*="info/"], a[href*="wbnewsid="]""")) {
            val abs = link.absUrl("href")
            if (abs.isBlank()) continue
            val raw = articleId(abs) ?: continue
            val id = column.idOffset + raw
            val title = link.text().trim()
            if (title.length < 4) continue

            val candidate = CrawledItem(
                id = id,
                columnId = column.id,
                columnName = column.name,
                title = title,
                url = abs,
                publishedAt = dateNear(link),
                source = column.source
            )
            // 页面顶部常有「最新文章」侧栏，同样的文章在那里没有日期。
            // 若已存在记录但日期为空，而这一条带日期，就用带日期的覆盖 ——
            // 否则教务类条目会因为"先遇到侧栏"而全部没有时间，时间线直接失效。
            val existing = result[id]
            if (existing == null ||
                (existing.publishedAt.isBlank() && candidate.publishedAt.isNotBlank())
            ) {
                result[id] = candidate
            }
        }
        result.values.sortedByDescending { it.id }
    }

    suspend fun fetchArticle(url: String): ArticleContent = withContext(Dispatchers.IO) {
        val html = get(url)
        val doc = Jsoup.parse(html, url)

        val root = pickContentRoot(doc)
        val blocks = mutableListOf<ContentBlock>()
        val images = mutableListOf<String>()

        if (root != null) {
            for (element in root.select("h2, h3, p, img, div.img-desc")) {
                when (element.tagName()) {
                    "img" -> imageUrl(element)?.let {
                        blocks += ContentBlock("img", it)
                        images += it
                    }
                    else -> {
                        val text = element.text().trim()
                        if (text.length >= 2) blocks += ContentBlock("p", text)
                        element.select("img").forEach { img ->
                            imageUrl(img)?.let {
                                blocks += ContentBlock("img", it)
                                images += it
                            }
                        }
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            doc.select("meta[name=description]").firstOrNull()?.attr("content")?.takeIf { it.isNotBlank() }
                ?.let { blocks += ContentBlock("p", it.trim()) }
        }

        val publishedAt = Regex(
            """发布(?:日期|时间)[：: ]*\s*(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})(?:\s*(\d{1,2}):(\d{2}))?"""
        )
            .find(doc.text())
            ?.let { formatStamp(it) }
            ?: ""

        ArticleContent(
            blocks = blocks.distinctBy { it.type + it.value },
            coverUrl = images.firstOrNull().orEmpty(),
            publishedAt = publishedAt
        )
    }

    private fun pickContentRoot(doc: Document): Element? {
        val preferred = listOf(
            "#vsb_content",
            "div.v_news_content",
            "div.conlis div.cen",
            "div.cen",
            "div.conlis",
            "article"
        )
        for (selector in preferred) {
            val element = doc.selectFirst(selector) ?: continue
            if (element.text().trim().length >= 30 || element.select("img").isNotEmpty()) {
                return element
            }
        }

        val candidates: List<Element> = doc.select("div, article, section").toList()
        return candidates
            .filter { it.select("p").size >= 2 }
            .filterNot { isNoiseBlock(it) }
            .maxWithOrNull(
                compareBy<Element> { element ->
                    element.select("p").sumOf { paragraph -> paragraph.text().length }
                }.thenByDescending { element -> element.getAllElements().size }
            )
    }

    private fun isNoiseBlock(element: Element): Boolean {
        val marker = (element.id() + " " + element.className()).lowercase()
        return NOISE_KEYWORDS.any { marker.contains(it) }
    }

    private fun isNoiseAncestor(element: Element): Boolean =
        element.parents().any { isNoiseBlock(it) }

    private fun imageUrl(element: Element): String? {
        if (isNoiseAncestor(element)) return null

        val src = listOf("src", "data-src", "data-original")
            .firstNotNullOfOrNull { attr ->
                val raw = element.absUrl(attr).ifBlank { element.attr(attr) }
                raw.takeIf { it.isNotBlank() && (it.startsWith("http") || it.startsWith("//")) }
                    ?.let { if (it.startsWith("//")) "https:$it" else it }
            } ?: return null

        if (isDecorativeImage(element, src)) return null
        return src
    }

    private fun isDecorativeImage(element: Element, src: String): Boolean {
        val width = element.attr("width").toIntOrNull() ?: 0
        val height = element.attr("height").toIntOrNull() ?: 0
        if (width in 1..160 || height in 1..80) return true

        if (!src.contains("/__local/")) {
            val path = src.substringAfter("nbu.edu.cn", src)
            if (path.contains("/images/") || DECORATIVE_NAMES.any { path.contains(it) }) return true
        }

        val alt = element.attr("alt")
        if (alt.contains("二维码") || alt.contains("logo", ignoreCase = true)) return true
        return false
    }

    private fun dateNear(link: Element): String {
        val candidates = listOfNotNull(
            link.nextElementSibling()?.text(),
            link.parent()?.text(),
            link.parent()?.nextElementSibling()?.text()
        )
        val pattern = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:\s+(\d{1,2}):(\d{2}))?""")
        for (text in candidates) {
            pattern.find(text)?.let { return formatStamp(it) }
        }
        return ""
    }

    /**
     * 把正则命中结果格式化为 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm`。
     *
     * 时分不是每个栏目都提供，缺失时只写日期 —— 不补零时刻，避免把"不知道几点"
     * 写成"00:00"。两种格式共用 `publishedAt` 字段，读取方按长度判断即可。
     */
    private fun formatStamp(m: MatchResult): String {
        val date = "%s-%02d-%02d".format(
            m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt()
        )
        val hour = m.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() } ?: return date
        val minute = m.groupValues.getOrNull(5)?.toIntOrNull() ?: return date
        return "%s %02d:%02d".format(date, hour.toInt(), minute)
    }

    private fun articleId(url: String): Long? =
        Regex("""/info/\d+/(\d+)\.htm""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""[?&]wbnewsid=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()

    companion object {
        private val NOISE_KEYWORDS = listOf(
            "cenr", "side", "right", "recommend", "related", "share", "fx",
            "footer", "foot", "nav", "crumb", "dqwz", "banner", "tjlist"
        )

        private val DECORATIVE_NAMES = listOf(
            "index_", "ad_", "foot_logo", "logo", "qq2017", "qrcode", "share"
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgent.default)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: return ""
            return decode(bytes)
        }
    }

    private fun decode(bytes: ByteArray): String {
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
