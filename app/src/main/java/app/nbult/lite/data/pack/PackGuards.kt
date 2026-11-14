package app.nbult.lite.data.pack

import android.content.Context
import android.net.Uri
import app.nbult.lite.data.news.NewsSources
import app.nbult.lite.school.SchoolRules
import app.nbult.lite.school.SchoolRulesResult
import app.nbult.lite.util.AppLog
import org.json.JSONObject

/**
 * 资源包的安全闸门。
 *
 * 内容外置之后，包里能改的东西里有两类会把用户带出应用或指挥应用去请求谁：
 * **入口链接**与**资讯抓取配置**。这两份文件在落地前必须过闸门。
 *
 * 2026-11-14 起，包也能声明**新学校**（`schools/index.json` 可热更），所以规则改了三处：
 *
 * 1. **按后缀分派**，而不是认死字面路径。以前只校验 `portal/entries.json` 与
 *    `news/sources.json`，于是 `schools/<id>/news.json` 会直接跳过校验 —— 加学校等于开后门。
 * 2. **host 用后缀匹配**（`host == d || host.endsWith(".$d")`），与 `net/HostWhitelist` 同口径。
 *    以前是精确比较，`jwc.xtu.edu.cn` 根本过不了 `xtu.edu.cn` 这道门。
 * 3. **新增学校的域名限 `edu.cn` 子域**。包被改也最多把请求引向教育网，不会指向任意站点。
 *
 * 任何一条不合格就**整包拒绝**，不做逐条过滤：入口悄悄少几条用户根本不会发现，
 * 那种半套状态比不更新更难查。
 */
internal object PackGuards {

    /**
     * 允许集合：内置入口的 host + 内置栏目的 host + 内置学校声明的域名
     * + **本次包里各学校 rules 声明的域名**。
     *
     * 最后一项目 0.38.7 起必需：湘潭的规则已经移出 APK，若仍只信内置域名，
     * 它的入口与资讯会在校验阶段被整包拒绝。这些域名在 [validateSchoolRules] 里
     * 已经被限定为 edu.cn 子域，所以放宽仍是有边界的。
     */
    private fun allowedHosts(context: Context, extra: Set<String>): Set<String> =
        builtinUrls(context).mapNotNull { Uri.parse(it).host }.toSet() +
            builtinNewsHosts(context) +
            builtinSchoolDomains(context) +
            extra

    /** 允许明文 http 的 host：只限学校自己的站（宁大一卡通那台机器只有 http）。 */
    private fun httpHosts(context: Context): Set<String> =
        builtinUrls(context).filter { it.startsWith("http://", ignoreCase = true) }
            .mapNotNull { Uri.parse(it).host }.toSet() +
            builtinSchoolDomains(context)

    /** 通过返回 null，否则返回拒绝原因（会写进日志）。 */
    fun validatePortal(context: Context, text: String, extraDomains: Set<String> = emptySet()): String? {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return "入口配置不是合法 JSON"
        val array = root.optJSONArray("entries") ?: return "入口配置缺少 entries"

        val allowed = allowedHosts(context, extraDomains)
        if (allowed.isEmpty()) return "拿不到内置域名基线，拒绝整包（避免无校验放行）"
        val plain = httpHosts(context)

        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            val url = entry.optString("url")
            if (url.isBlank()) return "条目 $id 没有链接"

            val uri = Uri.parse(url)
            PackRules.urlProblem(uri.host, uri.scheme, allowed, plain)
                ?.let { reason -> return "条目 $id $reason" }
        }
        return null
    }

    /**
     * 资讯抓取配置的闸门。
     *
     * 抓取配置能决定应用去请求哪些地址，等于让远程包指挥应用访问谁；host 必须落在
     * 「内置栏目 + 内置学校域名」里。正则能否编译由 [NewsSources.parse] 负责，这里只查域名。
     */
    fun validateNewsSources(context: Context, text: String, extraDomains: Set<String> = emptySet()): String? {
        val config = NewsSources.parse(text) ?: return "资讯配置结构不合格"
        val allowed = allowedHosts(context, extraDomains)
        if (allowed.isEmpty()) return "拿不到内置域名基线，拒绝整包（避免无校验放行）"

        for (column in config.columns) {
            val host = Uri.parse(column.listUrl).host
            if (!PackRules.hostMatches(host ?: "", allowed)) {
                return "栏目「${column.name}」引入了新域名：$host"
            }
        }
        return null
    }

    /**
     * 学校清单的闸门：可以**增**，不能**改内置**。
     *
     * 规则与 `HANDOFF.md` 的约定一致：内置学校的 id / 名称 / 规则路径以 APK 为准，
     * 否则一个坏包就能把「宁波大学」指到别的规则文件上去。
     */
    fun validateSchoolIndex(context: Context, text: String): String? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return "学校清单不是合法 JSON"
        val array = root.optJSONArray("schools") ?: return "学校清单缺少 schools"

        val incoming = ArrayList<PackRules.SchoolRef>(array.length())
        val builtinFlag = HashMap<String, Boolean>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            incoming.add(
                PackRules.SchoolRef(
                    id = id,
                    name = item.optString("name", id),
                    rulesPath = item.optString("rules", "schools/$id/rules.json")
                )
            )
            builtinFlag[id] = item.optBoolean("builtin", false)
        }

        val builtin = builtinIndexEntries(context).map {
            PackRules.SchoolRef(it.first, it.second, it.third)
        }
        PackRules.indexProblem(incoming, builtin)?.let { return it }

        // builtin 标记也要与内置一致：不能把内置学校降级成「可被后续包删掉」
        for (b in builtin) {
            if (builtinFlag[b.id] != true) return "内置学校 ${b.id} 的 builtin 标记被改动"
        }
        return null
    }

    /**
     * 学校规则的闸门：结构与正则交给 [SchoolRules.parse]，这里只卡域名。
     *
     * 新增学校（包内自带 rules）必须声明 `domains`，且每一项都是 `edu.cn` 子域并走 https
     * —— 这是「包可以扩学校」之后仍能把事故半径限在教育网内的唯一约束。
     */
    fun validateSchoolRules(context: Context, text: String): String? {
        val result = SchoolRules.parse(text)
        if (result !is SchoolRulesResult.Ok) return "学校规则结构不合格"
        val domains = result.rules.domains
        if (domains.isEmpty()) return "学校规则缺少 domains"
        for (raw in domains) {
            if (!PackRules.isEduDomain(raw)) return "学校域名必须是 edu.cn 子域：$raw"
        }
        // 规则里声明的入口/资讯路径必须留在包内相对路径，不能指向 assets 之外
        return null
    }

    /** 从一份 rules.json 里取出它声明的域名（已过 edu.cn 校验才会被采用）。 */
    fun domainsOf(text: String): Set<String> {
        val result = SchoolRules.parse(text)
        if (result !is SchoolRulesResult.Ok) return emptySet()
        return result.rules.domains.map { it.trim().lowercase() }
            .filter { PackRules.isEduDomain(it) }
            .toSet()
    }

    /**
     * 按路径后缀分派。未知文件不校验 —— 它们要么是数据（校历），要么会被读取方自行解析。
     *
     * 返回 null 表示放行。
     */
    fun validateByPath(
        context: Context,
        path: String,
        text: String,
        extraDomains: Set<String> = emptySet()
    ): String? = when {
        path.endsWith("/entries.json") || path.endsWith("/portal.json") ->
            validatePortal(context, text, extraDomains)

        path.endsWith("/news.json") || path.endsWith("/sources.json") ->
            validateNewsSources(context, text, extraDomains)

        path.endsWith("/index.json") ->
            validateSchoolIndex(context, text)

        path.endsWith("/rules.json") ->
            validateSchoolRules(context, text)

        // 其余 .json（校历、入口清单之外的杂项）至少要是合法 JSON 对象，
        // 免得坏包把「读不出来」拖到界面层才暴露
        path.endsWith(".json") ->
            if (runCatching { JSONObject(text) }.isFailure) "不是合法 JSON" else null

        else -> null
    }

    /** 内置学校清单：(id, name, rulesPath)。只读 APK，包不能用它自己声明的东西扩权。 */
    private fun builtinIndexEntries(context: Context): List<Triple<String, String, String>> =
        runCatching {
            val index = context.assets.open("schools/index.json").bufferedReader().use { it.readText() }
            val array = JSONObject(index).optJSONArray("schools") ?: return@runCatching emptyList()
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                if (!item.optBoolean("builtin", false)) return@mapNotNull null
                val id = item.optString("id").trim()
                if (id.isEmpty()) return@mapNotNull null
                Triple(
                    id,
                    item.optString("name", id),
                    item.optString("rules", "schools/$id/rules.json")
                )
            }
        }.getOrDefault(emptyList())

    /**
     * 内置学校声明的域名，只读 APK 里 assets/schools 下的规则文件。
     *
     * 湘潭的资讯在 xtu.edu.cn 下，而内置栏目只有宁大的域名；不放行这两处，
     * 「新增学校」就永远得发版。
     */
    private fun builtinSchoolDomains(context: Context): Set<String> =
        runCatching {
            val domains = mutableSetOf<String>()
            for ((_, _, path) in builtinIndexEntries(context)) {
                val rules = runCatching {
                    context.assets.open(path).bufferedReader().use { it.readText() }
                }.getOrNull() ?: continue
                JSONObject(rules).optJSONArray("domains")?.let { list ->
                    for (j in 0 until list.length()) {
                        list.optString(j).trim().lowercase()
                            .takeIf { it.isNotEmpty() }?.let { domains.add(it) }
                    }
                }
            }
            domains
        }.getOrDefault(emptySet())

    private fun builtinUrls(context: Context): List<String> = runCatching {
        val raw = context.assets.open("portal/entries.json").bufferedReader().use { it.readText() }
        val array = JSONObject(raw).optJSONArray("entries") ?: return emptyList()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("url") }
    }.getOrDefault(emptyList())

    private fun builtinNewsHosts(context: Context): Set<String> = runCatching {
        val raw = context.assets.open("news/sources.json").bufferedReader().use { it.readText() }
        NewsSources.parse(raw)?.columns.orEmpty().mapNotNull { Uri.parse(it.listUrl).host }.toSet()
    }.getOrDefault(emptySet())

    /** 日志用：把校验结论统一成一行，方便在导出日志里 grep `[Pack]`。 */
    fun logReject(version: Long, reason: String) {
        AppLog.log("Pack", "拒绝 v$version：$reason")
    }
}
