package app.nbult.lite.data.pack

/**
 * 资源包判定的纯函数部分。
 *
 * 抽出来的理由：`PackGuards` 必须拿 `Context` 读内置基线，而单测里没有 Context。
 * 判定本身（后缀匹配、edu.cn 约束、内置学校只增不改）与 Android 无关，
 * 放这里就能在 JVM 上直接验证 —— 见 `PackRulesTest`。
 */
internal object PackRules {

    /**
     * host 是否落在允许集合里。
     *
     * 必须是**后缀匹配**：`jwc.xtu.edu.cn` 要能被 `xtu.edu.cn` 放行，
     * 同时 `evailnbu.edu.cn` 不能被 `nbu.edu.cn` 放行 —— 所以补一个前导点。
     */
    fun hostMatches(host: String, allowed: Set<String>): Boolean {
        val h = host.trim().lowercase().removeSuffix(".")
        if (h.isEmpty()) return false
        return allowed.any { raw ->
            val d = raw.trim().lowercase()
            d.isNotEmpty() && (h == d || h.endsWith(".$d"))
        }
    }

    /** 新增学校的域名只许是 `edu.cn` 子域，且不许带路径/空格/占位符。 */
    fun isEduDomain(raw: String): Boolean {
        val d = raw.trim().lowercase()
        if (d.isEmpty()) return false
        if (d.contains('/') || d.contains(' ') || d.startsWith(".") || d.contains(":")) return false
        return d == "edu.cn" || d.endsWith(".edu.cn")
    }

    /** 入口链接是否可用：host 必须在允许集合里，且非 https 只放行学校自己的 http 站。 */
    fun urlProblem(
        host: String?,
        scheme: String?,
        allowed: Set<String>,
        plainHosts: Set<String>
    ): String? {
        if (host.isNullOrBlank()) return "链接无法解析"
        if (!hostMatches(host, allowed)) return "引入了新域名：$host"
        val s = scheme?.lowercase()
        if (s != "https" && !(s == "http" && hostMatches(host, plainHosts))) {
            return "使用了非 https：$host"
        }
        return null
    }

    /** 学校清单里的一条：id / 名称 / 规则路径。 */
    data class SchoolRef(val id: String, val name: String, val rulesPath: String)

    /**
     * 包内清单只能**增**，不能**改内置**。
     *
     * 返回 null 表示通过；否则是拒绝原因。命中任何一条都整包拒绝 ——
     * 把「宁波大学」指到别的规则文件上，比少一所学校危险得多。
     */
    fun indexProblem(incoming: List<SchoolRef>, builtin: List<SchoolRef>): String? {
        val byId = incoming.associateBy { it.id }
        for (b in builtin) {
            val hit = byId[b.id] ?: return "包内清单删掉了内置学校：${b.id}"
            if (hit.name != b.name) return "内置学校 ${b.id} 的名称被改动"
            if (hit.rulesPath != b.rulesPath) return "内置学校 ${b.id} 的规则路径被改动"
        }
        for (s in incoming) {
            if (!s.id.matches(Regex("^[a-z0-9]{2,16}$"))) return "学校 id 不合法：${s.id}"
        }
        return null
    }
}
