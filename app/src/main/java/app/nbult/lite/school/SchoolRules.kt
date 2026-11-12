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
    val maxSections: Int,
    /** 作息与学年：`schools/<id>/calendar.json`。为空表示沿用内置作息。 */
    val calendarPath: String? = null,
    /** 资讯源：`schools/<id>/news.json`。为空表示没有资讯模块。 */
    val newsPath: String? = null,
    /** 导入器参数。不同学校的表格差异写在这里，而不是写进代码。 */
    val importer: SchoolImporter = SchoolImporter(),
    /** 该校具备哪些模块。湘潭没有签到系统，这里必须关掉，否则入口永远打不开。 */
    val capabilities: SchoolCapabilities = SchoolCapabilities(),
    /** 该校的域名。网络白名单要靠它放行，否则湘潭的资讯一条都取不到。 */
    val domains: List<String> = emptyList(),
    /** 周次写法。学校换一种写法时改这里，不用改算法。 */
    val weekSyntax: app.nbult.lite.parser.WeekSyntax = app.nbult.lite.parser.WeekSyntax()
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
                maxSections = root.optJSONObject("limits")?.optInt("maxSections", 12) ?: 12,
                calendarPath = root.optString("calendar").takeIf { it.isNotBlank() },
                newsPath = root.optString("news").takeIf { it.isNotBlank() },
                importer = root.optJSONObject("importer")?.toImporter() ?: SchoolImporter(),
                capabilities = root.optJSONObject("capabilities")?.toCapabilities() ?: SchoolCapabilities(),
                domains = root.optJSONArray("domains").toStringList(),
                weekSyntax = root.optJSONObject("weekSyntax")?.let { w ->
                    app.nbult.lite.parser.WeekSyntax(
                        separators = w.optString("separators", ",，、"),
                        range = w.optString("range", app.nbult.lite.parser.WeekSyntax().range),
                        single = w.optString("single", app.nbult.lite.parser.WeekSyntax().single),
                        odd = w.optString("odd", app.nbult.lite.parser.WeekSyntax().odd),
                        even = w.optString("even", app.nbult.lite.parser.WeekSyntax().even)
                    )
                } ?: app.nbult.lite.parser.WeekSyntax()
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

        private fun JSONObject.toImporter(): SchoolImporter {
            val formats = optJSONArray("formats").toStringList()
            val pdf = optJSONObject("pdf")?.let {
                PdfImportParams(
                    columnAnchor = it.optString("columnAnchor", "星期"),
                    rowGapPt = it.optDouble("rowGapPt", 14.0).toFloat(),
                    headerPerPage = it.optBoolean("headerPerPage", true),
                    cellRegex = it.optString("cellRegex").takeIf { s -> s.isNotBlank() },
                    noiseDrop = it.optJSONArray("noiseDrop").toStringList()
                )
            }
            return SchoolImporter(
                formats = formats.ifEmpty { listOf("xlsx", "docx", "txt", "html") },
                pdf = pdf
            )
        }

        private fun JSONObject.toCapabilities(): SchoolCapabilities = SchoolCapabilities(
            // 默认全开：老规则文件没有这一段，行为要与过去一致
            attendance = optBoolean("attendance", true),
            login = optBoolean("login", true),
            widget = optBoolean("widget", true)
        )

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

/** 导入器配置：支持哪些格式、PDF 怎么切。 */
internal data class SchoolImporter(
    val formats: List<String> = listOf("xlsx", "docx", "txt", "html"),
    val pdf: PdfImportParams? = null
) {
    fun supports(extension: String): Boolean = formats.any { it.equals(extension, ignoreCase = true) }
}

/**
 * PDF 课表的取格参数。
 *
 * 这三条来自湘潭那份样本的实测（见 `docs/parser/xtu-pdf.md`）：
 * 不能按节次数字切行、表头要逐页判断、周次支持 (单)/(双)。
 */
internal data class PdfImportParams(
    /** 列锚：表头里出现这个前缀的单元格，其 x 作为该列的起点。 */
    val columnAnchor: String = "星期",
    /** 同列纵向间距超过这个值才算新单元格，否则是同格的换行。 */
    val rowGapPt: Float = 14f,
    /** 每页都重新找表头：续表没有表头，固定阈值会整页丢失。 */
    val headerPerPage: Boolean = true,
    /** 单元格文本的模板正则（命名分组 name/s1/s2/weeks/teacher/room）。 */
    val cellRegex: String? = null,
    /** 噪声行：整段等于这些词的片段直接丢掉（学时统计等）。 */
    val noiseDrop: List<String> = emptyList()
)

/** 模块开关。关掉的模块在界面上不出现，路由也拦住。 */
internal data class SchoolCapabilities(
    val attendance: Boolean = true,
    val login: Boolean = true,
    val widget: Boolean = true
)
