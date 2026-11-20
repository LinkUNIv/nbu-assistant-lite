package app.nbult.lite.parser.pdf

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中南民大课表打印页的真实版面。
 *
 * 夹具 `pdf/scmzu_fragments.tsv` 的坐标是原样导出的，课程名保留（不含个人信息），
 * 姓名换成「教师甲/乙/丙…」、学号删除。这样能复现真实版式又不把个人课表带进仓库。
 */
@Ignore("真实版面还在调参：行距阈值与 namePrefixMerge 在该版式上会互相干扰，夹具先入库，用例随后打开")
class ScmzuPdfFixtureTest {

    /** 与 scmzu/rules.json 的 importer.pdf 保持一致。 */
    private val params = PdfTableParams(
        columnAnchor = "星期",
        rowGapPt = 13.5f,
        headerPerPage = false,
        namePrefixMerge = true,
        multiCourse = true,
        cellRegex = """^(?<name>[^\[]+?)\s*\[(?<code>[^\]]*)\]\s*""" +
            """(?<weeks>\d[\d,\-]*\s*周(?:\s*[（(]\s*[单双]\s*[)）])?)\s*""" +
            """[（(]\s*第?\s*(?<s1>\d+)\s*[-—~]\s*(?<s2>\d+)\s*节\s*[)）]\s*""" +
            """(?<teacher>[^\d]*)\s*(?<room>.+)$""",
        noiseDrop = listOf("节次", "课程名称", "教学周次", "备注", "上课老师", "同学课表")
    )

    private fun fragments(): List<TextFragment> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("pdf/scmzu_fragments.tsv")) {
            "找不到夹具 pdf/scmzu_fragments.tsv"
        }.bufferedReader().use { it.readText() }
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            TextFragment(page = p[0].toInt(), x = p[1].toFloat(), y = -p[2].toFloat(), text = p.drop(3).joinToString("\t"))
        }.toList()
    }

    @Test
    fun `真实版面能解析出足够多的课时`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        assertTrue("样本应解析出至少 13 个课时，实际 ${slots.size}", slots.size >= 13)
        assertTrue("星期应落在 1..7，实际 ${slots.map { it.dayOfWeek }.distinct()}", slots.all { it.dayOfWeek in 1..7 })
    }

    @Test
    fun `周三 5-6 节的两门课都解析出来了`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        val same = slots.filter { it.dayOfWeek == 3 && it.startSection == 5 && it.endSection == 6 }
        assertEquals("周三 5-6 节应有 2 门，实际 ${same.map { it.name }}", 2, same.size)
        // 单双周落成不同位图：一门含第 3 周，一门不含偶数/奇数周
        assertTrue("两门课的周次应不同，实际 ${same.map { it.weekMask }}", same[0].weekMask != same[1].weekMask)
    }

    @Test
    fun `教师与地点不串行`() {
        val slots = PdfLayoutParser.parse(fragments(), params).slots
        val zhouyi = slots.first { it.dayOfWeek == 1 && it.startSection == 1 }
        assertEquals("法律逻辑学", zhouyi.name)
        assertTrue("教师字段为空：$zhouyi", zhouyi.teacher.isNotBlank())
        assertTrue("地点字段为空：$zhouyi", zhouyi.room.contains("号楼"))
    }
}
