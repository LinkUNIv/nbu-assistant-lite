package app.nbult.lite.parser.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中南民大课表打印页的真实版面（**精确字形框**，pymupdf `get_text("words")` 导出）。
 *
 * 这份 PDF 的文字是逐 token 绘制的：同一行里 `4-19周` 与 `(第1-2节)` 是两组独立定位的片段，
 * 且 y 相差 1~2pt。所以两条规则缺一不可：
 *   ① 同一视觉行内按 x 排序（否则拼成 `(第1-24-19周节)`）
 *   ② 一格多课按节次锚点切分（否则周三 5-6 的两门只剩一门）
 *
 * 夹具里的姓名已脱敏；宽度按实测 9.4pt/字补齐（原始 TSV 只存 page/x/y/text）。
 */
class ScmzuPdfFixtureTest {

    private companion object {
        /** 实测：3 个汉字宽 28.2pt → 9.4pt/字。 */
        const val CHAR_WIDTH = 9.4f
    }

    /** 与 scmzu/rules.json 的 importer.pdf 保持一致。 */
    private val params = PdfTableParams(
        columnAnchor = "星期",
        columnEdge = "center",
        rowGapPt = 18f,
        headerPerPage = false,
        namePrefixMerge = true,
        multiCourse = true,
        cellRegex = """^(?<name>[^\[]+?)\s*\[(?<code>[^\]]*)\]\s*""" +
            """(?<weeks>\d[\d,\-]*\s*周(?:\s*[（(]\s*[单双]\s*[)）])?)\s*""" +
            """[（(]\s*第?\s*(?<s1>\d+)\s*[-—~]\s*(?<s2>\d+)\s*节\s*[)）]\s*""" +
            """(?<teacher>[^\d]*?)\s*(?<room>.+)$""",
        noiseDrop = listOf("节次", "课程名称", "教学周次", "备注", "同学课表")
    )

    private fun fragments(): List<TextFragment> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("pdf/scmzu_fragments_exact.tsv")) {
            "找不到夹具 pdf/scmzu_fragments_exact.tsv"
        }.bufferedReader().use { it.readText() }
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            val content = p.drop(3).joinToString("\t")
            TextFragment(
                page = p[0].toInt(),
                x = p[1].toFloat(),
                y = -p[2].toFloat(),
                text = content,
                width = content.count { it.code > 0x2E80 } * CHAR_WIDTH +
                    content.count { it.code <= 0x2E80 } * CHAR_WIDTH * 0.55f
            )
        }.toList()
    }

    @Test
    fun `真实版面能解析出完整课表`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        assertTrue("应解析出至少 16 个时段，实际 ${slots.size}：${slots.map { it.name }}", slots.size >= 16)
        assertTrue("星期应落在 1..7", slots.all { it.dayOfWeek in 1..7 })
    }

    @Test
    fun `一格两门课都解析出来`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        val pair = slots.filter { it.startSection == 5 && it.endSection == 6 && it.name.startsWith("人工智能") }
        assertEquals("人工智能与计算思维应有两门，实际 ${pair.map { it.name }}", 2, pair.size)
        assertTrue("单双周应落成不同位图", pair[0].weekMask != pair[1].weekMask)
    }

    @Test
    fun `课名不串到上一格的房间`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        assertTrue("课名里不该出现「号楼/模拟法庭」这类房间片段：${slots.map { it.name }}",
            slots.none { it.name.contains("号楼") || it.name.contains("模拟法庭") })
        assertTrue("周一应有法学专业导论", slots.any { it.name == "法学专业导论" })
    }
}
