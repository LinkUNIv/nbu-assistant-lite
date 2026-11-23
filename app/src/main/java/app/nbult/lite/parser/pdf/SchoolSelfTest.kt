package app.nbult.lite.parser.pdf

import android.content.Context
import app.nbult.lite.data.pack.ResourcePackStore
import app.nbult.lite.school.SchoolCatalog
import app.nbult.lite.school.SchoolPreference
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 资源包自检：拿包里的脱敏片段跑一遍解析，看结果对不对。
 *
 * 为什么需要：规则从此可以由资源包下发，**改了没人编译拦住你**。
 * 三次事故（缺 events、校历路径指错学校、地点尾数）都是发布后才发现，
 * 这里有了一份"期望值"，装机就能自问一句"这份规则还能跑吗"。
 *
 * 数据来源（都在资源包里）：
 * ```
 * schools/<id>/selftest.json   { fixture, expectSlots, mustContain, mustNotContain }
 * fixtures/<id>.tsv            page \t x \t y \t text（姓名已脱敏）
 * ```
 */
object SchoolSelfTest {

    data class Result(
        val schoolId: String,
        /** 包里有 selftest.json 才算跑过；没有则跳过，不算失败。 */
        val ran: Boolean,
        val ok: Boolean = false,
        val expected: Int = 0,
        val actual: Int = 0,
        val missing: List<String> = emptyList(),
        val forbidden: List<String> = emptyList(),
        val detail: String = ""
    )

    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val schoolId = SchoolPreference.schoolId(context)
        val metaText = ResourcePackStore.readText(context, "schools/$schoolId/selftest.json")
            ?: return@withContext Result(schoolId, ran = false, detail = "该学校未提供自检样例")

        val meta = runCatching { JSONObject(metaText) }.getOrElse {
            return@withContext Result(schoolId, ran = true, ok = false, detail = "selftest.json 不是合法 JSON")
        }
        val fixturePath = meta.optString("fixture")
        val expect = meta.optInt("expectSlots", 0)
        val mustContain = meta.optJSONArray("mustContain").toStringList()
        val mustNotContain = meta.optJSONArray("mustNotContain").toStringList()

        val fixture = ResourcePackStore.readText(context, fixturePath)
            ?: return@withContext Result(schoolId, ran = true, ok = false, detail = "找不到样例 $fixturePath")

        val rules = SchoolCatalog.rules(context, schoolId)
        val pdf = rules?.importer?.pdf
            ?: return@withContext Result(schoolId, ran = true, ok = false, detail = "该校没有 PDF 规则")

        val fragments = parseFixture(fixture)
        val slots = runCatching {
            PdfLayoutParser.parse(
                fragments,
                PdfTableParams(
                    columnAnchor = pdf.columnAnchor,
                    columnEdge = pdf.columnEdge,
                    cellSource = pdf.cellSource,
                    rowGapPt = pdf.rowGapPt,
                    headerPerPage = pdf.headerPerPage,
                    cellRegex = pdf.cellRegex,
                    multiCourse = pdf.multiCourse,
                    noiseDrop = pdf.noiseDrop,
                    namePrefixMerge = pdf.namePrefixMerge,
                    weekSyntax = rules.weekSyntax,
                    groupByCourseCode = pdf.groupByCourseCode,
                    dropAboveHeader = pdf.dropAboveHeader
                ),
                rules = emptyList()
            ).slots
        }.getOrElse {
            return@withContext Result(schoolId, ran = true, ok = false, detail = "解析抛异常：${it.message}")
        }

        val names = slots.map { it.name }
        val missing = mustContain.filter { want -> names.none { it.contains(want) } }
        val forbidden = mustNotContain.filter { bad -> names.any { it.contains(bad) } }
        // 用 >= 而不是 ==：规则改进后条数可能变多，等号会误报
        val ok = slots.size >= expect && expect > 0 && missing.isEmpty() && forbidden.isEmpty()

        AppLog.log(
            "SelfTest",
            "自检 ${if (ok) "通过" else "失败"}：$schoolId 期望≥$expect 实得${slots.size}" +
                if (missing.isEmpty() && forbidden.isEmpty()) "" else
                    " | 缺=${missing.joinToString("/")} 不该出现=${forbidden.joinToString("/")}"
        )
        Result(schoolId, ran = true, ok = ok, expected = expect, actual = slots.size,
            missing = missing, forbidden = forbidden)
    }

    /** 夹具格式与单测一致：`page \t x \t y \t text`，y 自上而下，这里取反。 */
    private fun parseFixture(text: String): List<TextFragment> =
        text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            val raw = parts.drop(3).joinToString("\t")
            TextFragment(
                page = parts[0].toIntOrNull() ?: 0,
                x = parts[1].toFloatOrNull() ?: return@mapNotNull null,
                y = -(parts[2].toFloatOrNull() ?: return@mapNotNull null),
                text = raw,
                // 样例不带宽度，按字宽估：中文 9.4pt、其余 0.55 倍（实测值）
                width = raw.sumOf { c ->
                    if (c.code > 0x2E80) 9.4 else 9.4 * 0.55
                }.toFloat()
            )
        }.toList()

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
