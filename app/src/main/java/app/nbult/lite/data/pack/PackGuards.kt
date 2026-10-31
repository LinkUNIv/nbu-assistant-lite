package app.nbult.lite.data.pack

import android.content.Context
import android.net.Uri
import app.nbult.lite.util.AppLog
import org.json.JSONObject

/**
 * 资源包的安全闸门。
 *
 * 这是内容外置唯一比 MAA 那套更危险的地方：`portal/entries.json` 是一串**跳转链接**。
 * 远程包一旦被换成带钓鱼地址的版本，用户点一下就走。所以包落地之前，对这份文件单独设卡：
 *
 * - **不许引入新域名**。允许的 host 集合以 APK 内置的那份为准；
 *   学校换平台时应当发版更新白名单，而不是让远程包自己扩权
 * - **不许降级到 http**。内置里本来就是 http 的那一个除外（一卡通那台机器只有 http），
 *   这条也用「内置里存在同 host 的 http 条目」来判定，而不是写死域名
 *
 * 任何一条不合格就**整包拒绝**，不做逐条过滤：入口悄悄少几条，用户根本不会发现，
 * 那种半套状态比不更新更难查。
 */
internal object PackGuards {

    /** 通过返回 null，否则返回拒绝原因（会写进日志）。 */
    fun validatePortal(context: Context, text: String): String? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return "entries.json 不是合法 JSON"
        val array = root.optJSONArray("entries") ?: return "entries.json 缺少 entries"

        val builtin = builtinUrls(context)
        if (builtin.isEmpty()) return null // 内置清单读不出来时不叠加规则，避免误杀
        val allowedHosts = builtin.mapNotNull { Uri.parse(it).host }.toSet()
        val httpHosts = builtin.filter { it.startsWith("http://", ignoreCase = true) }
            .mapNotNull { Uri.parse(it).host }
            .toSet()

        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            val url = entry.optString("url")
            if (url.isBlank()) return "条目 $id 没有链接"

            val uri = Uri.parse(url)
            val host = uri.host ?: return "条目 $id 的链接无法解析：$url"
            if (host !in allowedHosts) return "条目 $id 引入了新域名：$host"

            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && !(scheme == "http" && host in httpHosts)) {
                return "条目 $id 使用了非 https：$url"
            }
        }
        return null
    }

    /**
     * 资讯抓取配置的闸门。
     *
     * 比入口宽松一点：这里不限制「必须是已有域名」之外的东西 —— 但域名仍然要卡。
     * 抓取配置能把请求指向任意地址，等于让远程包指挥应用去访问谁；把 host 收在内置集合里，
     * 至少保证它不会拿用户设备去扫内网或访问第三方。
     *
     * 正则能否编译由 [app.nbult.lite.data.news.NewsSources.parse] 负责，这里只查域名。
     */
    fun validateNewsSources(context: Context, text: String): String? {
        val config = app.nbult.lite.data.news.NewsSources.parse(text)
            ?: return "news/sources.json 结构不合格"
        val builtin = runCatching {
            val raw = context.assets.open("news/sources.json").bufferedReader().use { it.readText() }
            app.nbult.lite.data.news.NewsSources.parse(raw)?.columns.orEmpty()
        }.getOrDefault(emptyList())
        if (builtin.isEmpty()) return null

        val allowed = builtin.mapNotNull { Uri.parse(it.listUrl).host }.toSet()
        for (column in config.columns) {
            val host = Uri.parse(column.listUrl).host
                ?: return "栏目「${column.name}」的链接无法解析"
            if (host !in allowed) return "栏目「${column.name}」引入了新域名：$host"
        }
        return null
    }

    private fun builtinUrls(context: Context): List<String> = runCatching {
        val raw = context.assets.open("portal/entries.json").bufferedReader().use { it.readText() }
        val array = JSONObject(raw).optJSONArray("entries") ?: return emptyList()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("url") }
    }.getOrDefault(emptyList())

    /** 日志用：把校验结论统一成一行，方便在导出日志里 grep `[Pack]`。 */
    fun logReject(version: Long, reason: String) {
        AppLog.log("Pack", "拒绝 v$version：$reason")
    }
}
