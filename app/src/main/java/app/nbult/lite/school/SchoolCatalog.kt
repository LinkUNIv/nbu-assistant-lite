package app.nbult.lite.school

import android.content.Context
import app.nbult.lite.util.AppLog

/**
 * 学校目录与规则加载。
 *
 * 只做两件事：读 `assets/schools/index.json` 拿到学校清单，读内置学校的规则文件。
 * 远端按校包（`schools/<id>/rules.json`）在 0.38.x 接进来时复用同一套校验 ——
 * 坏包在 [SchoolRules.parse] 那一步就会被拒，这里只需决定「用包」还是「用内置」。
 */
internal object SchoolCatalog {

    private const val INDEX = "schools/index.json"

    data class Entry(
        val id: String,
        val name: String,
        val builtin: Boolean,
        val rulesPath: String
    )

    /** 清单读不出来就当只有本校：宁可少列学校，也不要让首页因此崩掉。 */
    fun entries(context: Context): List<Entry> = runCatching {
        val json = context.assets.open(INDEX).bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(json)
        val array = root.optJSONArray("schools") ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            if (id.isEmpty()) return@mapNotNull null
            Entry(
                id = id,
                name = item.optString("name", id),
                builtin = item.optBoolean("builtin", false),
                rulesPath = item.optString("rules", "schools/$id/rules.json")
            )
        }
    }.getOrElse {
        AppLog.log("School", "读取学校清单失败：${it.message}")
        emptyList()
    }

    /**
     * 内置学校的规则。失败返回 null —— 调用方应退回代码里的硬编码规则，
     * 而不是让「课表导入」整体不可用。
     */
    fun builtinRules(context: Context, id: String = "nbu"): SchoolRules? {
        val entry = entries(context).firstOrNull { it.id == id && it.builtin }
        if (entry == null) {
            AppLog.log("School", "内置学校 $id 不在清单里")
            return null
        }
        return loadRules(context, entry)
    }

    /**
     * 规则里引用的附属文件是否存在。
     *
     * 只记日志不拦截：缺作息还能用内置模板，缺资讯源只是少一个模块 ——
     * 但这类"少了一半"的情况必须在日志里看得见，否则排查起来只能靠猜。
     */
    private fun checkReferences(context: Context, rules: SchoolRules) {
        listOfNotNull(
            rules.calendarPath?.let { "作息" to it },
            rules.newsPath?.let { "资讯源" to it }
        ).forEach { (label, path) ->
            val ok = runCatching { context.assets.open(path).close() }.isSuccess
            if (!ok) AppLog.log("School", "${rules.name} 的$label 不存在：$path")
        }
    }

    private fun loadRules(context: Context, entry: Entry): SchoolRules? = runCatching {
        val json = context.assets.open(entry.rulesPath).bufferedReader().use { it.readText() }
        when (val result = SchoolRules.parse(json)) {
            is SchoolRulesResult.Ok -> {
                AppLog.log("School", "规则已加载 ${result.rules.name} v${result.rules.version}")
                checkReferences(context, result.rules)
                result.rules
            }
            is SchoolRulesResult.Failed -> {
                AppLog.log("School", "规则被拒绝（${entry.rulesPath}）：${result.reason}")
                null
            }
        }
    }.getOrElse {
        AppLog.log("School", "读取规则失败（${entry.rulesPath}）：${it.message}")
        null
    }
}
