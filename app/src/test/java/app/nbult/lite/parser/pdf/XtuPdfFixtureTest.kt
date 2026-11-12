package app.nbult.lite.parser.pdf

import app.nbult.lite.domain.WeekMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真实 PDF 的坐标**跑归列。
 *
 * 夹具 `pdf/xtu_fragments.tsv` 是从湘潭那份课表导出的：页号、x、y 全部原样保留，
 * 课程名 / 教师 / 楼栋替换为序号，学号整行删除。这样既能复现真实版面，
 * 又不把个人课表带进仓库。
 *
 * 关键数字来自样本实测（`docs/parser/xtu-pdf.md`）：26 个单元格、0 条未解析。
 */
class XtuPdfFixtureTest {

    /** 与 xtu/rules.json 里的 importer.pdf 保持一致。 */
    private val params = PdfTableParams(
        columnAnchor = "星期",
        rowGapPt = 14f,
        headerPerPage = true,
        cellRegex = "^(?<name>.+?)\\((?<s1>\\d+)-(?<s2>\\d+)节\\)(?<weeks>[^/]+)/校区[:：](?<campus>.+?)" +
            "/场地[:：](?<room>.+?)/教师[:：](?<teacher>.+?)/考核方式[:：](?<assess>.+?)/学分[:：](?<credit>[\\d.]+)$",
        noiseDrop = listOf("调查学时", "线上学时", "上机学时", "听力学时", "实验学时", "实践学时", "理论学时", "讲座学时")
    )

    private fun fragments(): List<TextFragment> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("pdf/xtu_fragments.tsv")) {
            "找不到夹具 pdf/xtu_fragments.tsv"
        }.bufferedReader().use { it.readText() }

        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            TextFragment(
                page = parts[0].toInt(),
                x = parts[1].toFloat(),
                // 夹具里存的是 PDFBox 的 yDirAdj（自上而下递增），
                // 而归列算法约定「y 越大越靠上」，与 PdfTextExtractor 一致地取反
                y = -parts[2].toFloat(),
                text = parts.drop(3).joinToString("\t")
            )
        }.toList()
    }

    @Test
    fun `真实版面上取出 26 个单元格`() {
        val result = PdfLayoutParser.parse(fragments(), params)
        assertEquals("样本应得 26 个格子，实际 ${result.slots.size}", 26, result.slots.size)
    }

    @Test
    fun `跨两页的续表都归到了正确的星期`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        // 两页都有课，且每页都落在周一到周日之间
        assertTrue(slots.all { it.dayOfWeek in 1..7 })
        val days = slots.map { it.dayOfWeek }.distinct()
        assertTrue("样本覆盖了多个星期，实际 $days", days.size >= 4)
    }

    @Test
    fun `节次与周次都落在合理范围`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        slots.forEach { s ->
            assertTrue("节次越界：${s.name} ${s.startSection}-${s.endSection}", s.startSection in 1..14)
            assertTrue("节次顺序反了：${s.name}", s.startSection <= s.endSection)
            assertTrue("周次为空：${s.name}", s.weekMask and WeekMask.range(1, 20) != 0)
        }
    }

    @Test
    fun `单元格里的教师与场地都取到了`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        assertTrue("应至少有一条带场地", slots.count { it.room.isNotBlank() } >= 24)
        assertTrue("应至少有一条带教师", slots.count { it.teacher.isNotBlank() } >= 24)
    }
}
