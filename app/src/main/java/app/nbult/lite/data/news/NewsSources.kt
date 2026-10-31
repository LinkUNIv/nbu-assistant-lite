package app.nbult.lite.data.news

import android.content.Context
import app.nbult.lite.data.pack.ResourcePackStore
import app.nbult.lite.data.remote.NewsColumn
import app.nbult.lite.util.AppLog
import org.json.JSONObject

/**
 * 抓取规则。**只放「可调参数」，不放算法**。
 *
 * 选择器与正则属于会随网站改版失效的东西，放进来就能通过资源包更新；
 * 而「怎么挑正文根节点」「怎么去重」这类判断留在 [app.nbult.lite.data.remote.NewsCrawler] 里 ——
 * 远程包能改的只能是「往哪找」，不能是「怎么算」。
 */
data class NewsRules(
    val linkSelector: String = """a[href*="info/"], a[href*="wbnewsid="]""",
    val minTitleLength: Int = 4,
    val articleIdPatterns: List<String> = listOf("""/info/\d+/(\d+)\.htm""", """[?&]wbnewsid=(\d+)"""),
    val datePattern: String = """(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:\s+(\d{1,2}):(\d{2}))?""",
    val publishedPattern: String =
        """发布(?:日期|时间)[：: ]*\s*(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})(?:\s*(\d{1,2}):(\d{2}))?""",
    val contentRoots: List<String> = listOf(
        "#vsb_content",
        "div.v_news_content",
        "div.conlis div.cen",
        "div.cen",
        "div.conlis",
        "article"
    ),
    val contentBlockSelector: String = "h2, h3, p, img, div.img-desc",
    val bodyCandidateSelector: String = "div, article, section",
    val minRootChars: Int = 30,
    val minBodyParagraphs: Int = 2,
    val minBlockChars: Int = 2,
    val noiseKeywords: List<String> = listOf(
        "cenr", "side", "right", "recommend", "related", "share", "fx",
        "footer", "foot", "nav", "crumb", "dqwz", "banner", "tjlist"
    ),
    val decorativeNames: List<String> = listOf(
        "index_", "ad_", "foot_logo", "logo", "qq2017", "qrcode", "share"
    )
) {
    /** 预编译一次正则。抓取是热路径，不该每篇文章都重新编译。 */
    val articleIdRegex: List<Regex> by lazy { articleIdPatterns.map { Regex(it) } }
    val dateRegex: Regex by lazy { Regex(datePattern) }
    val publishedRegex: Regex by lazy { Regex(publishedPattern) }
}

/** 一份完整的资讯来源配置：栏目 + 规则。 */
data class NewsSourceConfig(val columns: List<NewsColumn>, val rules: NewsRules) {
    /** 列表页顶部展示的来源名（去重后按出现顺序）。 */
    val sources: List<String> get() = columns.map { it.source }.distinct()
}

/**
 * 资讯来源配置的读取。
 *
 * 与校历、入口一样走资源包通道：远程包里有 `news/sources.json` 就用它，没有就用内置的。
 * 学校把栏目改个名、换个 `.htm` 地址、或者新闻网改版换了正文容器的 class，
 * 都不必等发版。
 *
 * 这里比校历多一层保护：**任何一项读不懂就整份退回内置**。
 * 校历坏了大不了显示「未配置」，资讯规则坏了会让所有栏目都抓空 ——
 * 而抓空在界面上和「最近没新闻」长得一模一样，很难归因。
 */
object NewsSources {

    private const val ASSET = "news/sources.json"

    @Volatile
    private var cached: NewsSourceConfig? = null

    @Volatile
    private var cachedPackVersion: Long = Long.MIN_VALUE

    /**
     * 读配置。同一份资源包版本内复用缓存；包换了（版本号变了）自动重读。
     *
     * 返回的一定是可用的配置：解析失败、栏目为空、正则非法，都会退回内置，
     * 并在日志里留一行 `[News] 配置回退 ...`。
     */
    fun load(context: Context): NewsSourceConfig {
        val packVersion = ResourcePackStore.state(context).version
        cached?.let { if (cachedPackVersion == packVersion) return it }

        val text = ResourcePackStore.readText(context, ASSET)
        val parsed = text?.let { parse(it) }
        val config = parsed ?: builtin(context) ?: NewsSourceConfig(
            columns = emptyList(),
            rules = NewsRules()
        )
        if (parsed == null) {
            AppLog.log("News", "配置回退：$ASSET 无法解析，使用内置")
        }
        cached = config
        cachedPackVersion = packVersion
        return config
    }

    /** 资源包应用后调用，强制下次读到新配置。 */
    fun invalidate() {
        cached = null
    }

    private fun builtin(context: Context): NewsSourceConfig? = runCatching {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()?.let { parse(it) }

    /**
     * 解析并校验。**任一字段不合格返回 null**，由调用方退回内置。
     *
     * 校验的是「结构上能不能用」：栏目非空、id 不重复、链接是 https、
     * 每个正则都能编译。域名白名单不在这里 —— 那需要在应用包之前判，
     * 见 [app.nbult.lite.data.pack.PackGuards]。
     */
    fun parse(text: String): NewsSourceConfig? = runCatching {
        val root = JSONObject(text)
        val array = root.optJSONArray("columns") ?: return null
        if (array.length() == 0) return null

        val columns = ArrayList<NewsColumn>(array.length())
        val seenIds = HashSet<Int>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: return null
            val id = o.optInt("id", 0)
            val name = o.optString("name").trim()
            val url = o.optString("listUrl").trim()
            if (id <= 0 || name.isEmpty()) return null
            if (!url.startsWith("https://")) return null
            if (!seenIds.add(id)) return null
            columns.add(
                NewsColumn(
                    id = id,
                    name = name,
                    listUrl = url,
                    source = o.optString("source", "宁波大学新闻网"),
                    idOffset = o.optLong("idOffset", 0L)
                )
            )
        }

        val r = root.optJSONObject("rules") ?: JSONObject()
        val defaults = NewsRules()
        val rules = NewsRules(
            linkSelector = r.optString("linkSelector", defaults.linkSelector),
            minTitleLength = r.optInt("minTitleLength", defaults.minTitleLength),
            articleIdPatterns = r.optJSONArray("articleIdPatterns")?.toStringList()
                ?: defaults.articleIdPatterns,
            datePattern = r.optString("datePattern", defaults.datePattern),
            publishedPattern = r.optString("publishedPattern", defaults.publishedPattern),
            contentRoots = r.optJSONArray("contentRoots")?.toStringList() ?: defaults.contentRoots,
            contentBlockSelector = r.optString("contentBlockSelector", defaults.contentBlockSelector),
            bodyCandidateSelector = r.optString("bodyCandidateSelector", defaults.bodyCandidateSelector),
            minRootChars = r.optInt("minRootChars", defaults.minRootChars),
            minBodyParagraphs = r.optInt("minBodyParagraphs", defaults.minBodyParagraphs),
            minBlockChars = r.optInt("minBlockChars", defaults.minBlockChars),
            noiseKeywords = r.optJSONArray("noiseKeywords")?.toStringList() ?: defaults.noiseKeywords,
            decorativeNames = r.optJSONArray("decorativeNames")?.toStringList()
                ?: defaults.decorativeNames
        )

        if (rules.linkSelector.isBlank() || rules.contentBlockSelector.isBlank()) return null
        if (rules.articleIdPatterns.isEmpty()) return null
        // 正则必须能编译：一条写错就让整个包作废，而不是等到抓取时抛异常
        rules.articleIdRegex
        rules.dateRegex
        rules.publishedRegex

        NewsSourceConfig(columns = columns, rules = rules)
    }.getOrNull()

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
