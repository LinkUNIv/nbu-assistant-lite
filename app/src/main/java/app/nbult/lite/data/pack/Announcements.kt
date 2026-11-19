package app.nbult.lite.data.pack

import android.content.Context
import app.nbult.lite.util.AppLog
import org.json.JSONObject

/**
 * 资源包下发的公告。
 *
 * 设计前提：**公告只能改内容，不能改行为**。所以这里只做文本与链接的白名单校验，
 * 任何想借公告让用户去装东西、填账号的内容都不可表达（见 [Announcements.parse] 的边界）。
 */
data class Announcement(
    val id: String,
    /** 置顶项：常驻列表首位，不可关闭，不计入未读红点。 */
    val pinned: Boolean,
    val level: Level,
    val title: String,
    val body: String,
    val link: String?,
    val since: Long,
    val until: Long,
    val minApp: Int,
    val maxApp: Int
) {
    enum class Level { INFO, IMPORTANT, CRITICAL }

    companion object {
        /** 正文上限：超长直接判该条无效，避免塞满屏幕做钓鱼。 */
        const val MAX_BODY = 500
        const val MAX_TITLE = 40
    }
}

object Announcements {

    private const val ASSET = "announcements.json"

    /**
     * 读包里的公告。读不到、格式不对、单条不合格，都只影响**那一条**。
     *
     * 与其它包的"整包原子"策略不同：公告是附加信息，一条写错不该把整包（连同学校的
     * 作息表、校历）一起拒掉。
     */
    fun load(context: Context, appVersionCode: Int, now: Long = System.currentTimeMillis() / 1000): List<Announcement> {
        val text = ResourcePackStore.readText(context, ASSET) ?: return emptyList()
        return parse(text, appVersionCode, now)
    }

    fun parse(text: String, appVersionCode: Int, now: Long): List<Announcement> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: run {
            AppLog.log("Pack", "公告：不是合法 JSON，忽略")
            return emptyList()
        }
        val array = root.optJSONArray("announcements") ?: return emptyList()

        val out = ArrayList<Announcement>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            if (o == null) {
                AppLog.log("Pack", "公告[$i]：不是对象，丢弃")
                continue
            }
            val item = parseOne(o, appVersionCode, now)
            if (item == null) {
                AppLog.log("Pack", "公告[$i]（id=${o.optString("id")}）：不合格，丢弃")
                continue
            }
            out += item
        }
        // 同一 id 只保留第一条，防止包内重复导致红点永远消不掉
        return out.distinctBy { it.id }
            .sortedWith(compareByDescending<Announcement> { it.pinned }.thenByDescending { it.level.ordinal })
    }

    private fun parseOne(o: JSONObject, appVersionCode: Int, now: Long): Announcement? {
        val id = o.optString("id").trim()
        val title = o.optString("title").trim()
        val body = o.optString("body").trim()
        if (id.isEmpty()) return null
        if (title.isEmpty() || title.length > Announcement.MAX_TITLE) return null
        if (body.isEmpty() || body.length > Announcement.MAX_BODY) return null

        val since = o.optLong("since", 0L)
        val until = o.optLong("until", 0L)
        // 未到生效时间 / 已过期：不是"不合格"，只是现在不该看到，直接过滤
        if (since > 0L && now < since) return null
        if (until > 0L && now > until) return null

        val minApp = o.optInt("minApp", 0)
        val maxApp = o.optInt("maxApp", 0)
        if (minApp > 0 && appVersionCode < minApp) return null
        if (maxApp > 0 && appVersionCode > maxApp) return null

        val link = o.optString("link").trim().ifEmpty { null }?.let { url ->
            // 只允许 https；并且**不许指向安装包** —— 公告不能成为绕过 sha256 校验的下载口
            if (!url.startsWith("https://")) {
                AppLog.log("Pack", "公告 $id：链接不是 https，已忽略该链接")
                null
            } else if (url.endsWith(".apk", true) || url.contains(".apk?")) {
                AppLog.log("Pack", "公告 $id：链接指向安装包，已忽略")
                null
            } else {
                url
            }
        }

        val level = when (o.optString("level").lowercase()) {
            "critical" -> Announcement.Level.CRITICAL
            "important" -> Announcement.Level.IMPORTANT
            else -> Announcement.Level.INFO
        }

        return Announcement(
            id = id,
            pinned = o.optBoolean("pinned", false),
            level = level,
            title = title,
            body = body,
            link = link,
            since = since,
            until = until,
            minApp = minApp,
            maxApp = maxApp
        )
    }
}
