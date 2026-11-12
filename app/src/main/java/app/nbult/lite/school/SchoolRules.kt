package app.nbult.lite.school

import org.json.JSONObject

/**
 * 一所学校的解析规则。
 *
 * 规则从代码里搬出来，是为了**改一条正则不用发版**：学校教务改版、周次换个写法，
 * 推一个几 KB 的资源包即可。代价是规则可能在运行时是坏的，所以这里所有正则都在
 * 解析时编译一次 —— 坏包当场拒绝，而不是等到解析课表时才炸。
 *
 * 字段与 `assets/schools/<id>/rules.json` 一一对应；不认识的字段一律忽略，
 * 这样给包加新字段不必同时改老客户端。
 */
internal data class SchoolRules(
    val id: String,
    val name: String,
    val version: String,
    val signature: List<String>,
    val patterns: SchoolPatterns,
    val tokens: SchoolTokens,
    val maxWeeks: Int,
    val maxSections: Int
) {
    companion object {
        const val SUPPORTED_SCHEMA = 1

        fun parse(json: String): SchoolRulesResult {
          return try {
            val root = JSONObject(json)
            val schema = root.optInt("schema", 0)
            if (schema != SUPPORTED_SCHEMA) {
                return SchoolRulesResult.Failed("schema 不支持：$schema（当前支持 $SUPPORTED_SCHEMA）")
            }
            val id = root.optString("id").trim()
            val name = root.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) return SchoolRulesResult.Failed("缺少 id 或 name")

            val p = root.optJSONObject("patterns") ?: return SchoolRulesResult.Failed("缺少 patterns")
            val t = root.optJSONObject("tokens") ?: return SchoolRulesResult.Failed("缺少 tokens")

            val patterns = try {
                SchoolPatterns(
                    weekRange = p.regex("weekRange"),
                    weekList = p.regex("weekList"),
                    weekSingle = p.regex("weekSingle"),
                    weekOdd = p.regex("weekOdd"),
                    weekEven = p.regex("weekEven"),
                    sectionRange = p.regex("sectionRange"),
                    sectionSingle = p.regex("sectionSingle"),
                    dayPattern = p.regex("dayPattern"),
                    teacherLabelled = p.regex("teacherLabelled"),
                    teacherSuffix = p.regex("teacherSuffix"),
                    room = p.regex("room")
                )
            } catch (e: PatternError) {
                return SchoolRulesResult.Failed(e.message ?: "正则无效")
            }

            val dayWords = mutableMapOf<String, Int>()
            val dayObj = t.optJSONObject("dayWords") ?: return SchoolRulesResult.Failed("缺少 tokens.dayWords")
            dayObj.keys().forEach { key -> dayWords[key] = dayObj.optInt(key) }
            // 七个星期必须齐全，否则某一天的课会静默消失
            val missing = (1..7).filter { day -> dayWords.values.none { it == day } }
            if (missing.isNotEmpty()) return SchoolRulesResult.Failed("tokens.dayWords 缺星期 $missing")

            val rules = SchoolRules(
                id = id,
                name = name,
                version = root.optString("version", "0"),
                signature = root.optJSONArray("signature").toStringList(),
                patterns = patterns,
                tokens = SchoolTokens(
                    dayPrefixes = t.optJSONArray("dayPrefixes").toStringList(),
                    dayWords = dayWords,
                    weekUnit = t.optString("weekUnit", "周"),
                    sectionUnit = t.optString("sectionUnit", "节"),
                    teacherLabels = t.optJSONArray("teacherLabels").toStringList(),
                    roomLabels = t.optJSONArray("roomLabels").toStringList()
                ),
                maxWeeks = root.optJSONObject("limits")?.optInt("maxWeeks", 20) ?: 20,
                maxSections = root.optJSONObject("limits")?.optInt("maxSections", 12) ?: 12
            )
            SchoolRulesResult.Ok(rules)
          } catch (e: Exception) {
            SchoolRulesResult.Failed(e.message ?: "规则不是合法 JSON")
          }
        }

        private class PatternError(message: String) : Exception(message)

        private fun JSONObject.regex(key: String): Regex {
            val raw = optString(key)
            if (raw.isEmpty()) throw PatternError("patterns.$key 为空")
            return try {
                Regex(raw)
            } catch (e: Exception) {
                throw PatternError("patterns.$key 无法编译：${e.message}")
            }
        }

        private fun org.json.JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
        }
    }
}

/**
 * 解析结果。
 *
 * 失败必须给出**人话原因**：远端包坏了要能在日志里看出是哪条正则、哪一版包，
 * 否则只能靠猜。
 */
internal sealed interface SchoolRulesResult {
    data class Ok(val rules: SchoolRules) : SchoolRulesResult
    data class Failed(val reason: String) : SchoolRulesResult
}

internal data class SchoolPatterns(
    val weekRange: Regex,
    val weekList: Regex,
    val weekSingle: Regex,
    val weekOdd: Regex,
    val weekEven: Regex,
    val sectionRange: Regex,
    val sectionSingle: Regex,
    val dayPattern: Regex,
    val teacherLabelled: Regex,
    val teacherSuffix: Regex,
    val room: Regex
)

internal data class SchoolTokens(
    val dayPrefixes: List<String>,
    val dayWords: Map<String, Int>,
    val weekUnit: String,
    val sectionUnit: String,
    val teacherLabels: List<String>,
    val roomLabels: List<String>
)
