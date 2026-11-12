package app.nbult.lite.parser.pdf

import app.nbult.lite.domain.WeekMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标归列的回归测试。
 *
 * 用**构造的坐标**而不是真 PDF：归列是最容易写错的一环（半列宽偏移、行聚类阈值、
 * 续表沿用表头），而它完全可以脱离 PDF 库验证。真 PDF 只能人工看结果。
 *
 * 数值取自 `docs/parser/xtu-pdf.md` 的实测：列宽 103.9pt、行距 12pt、格间距 15.5pt。
 */
class PdfLayoutParserTest {

    private val step = 103.9f
    private val headerY = 700f
    private val cellRegex =
        "^(?<name>.+?)\\((?<s1>\\d+)-(?<s2>\\d+)节\\)(?<weeks>[^/]+)/校区[:：](?<campus>.+?)" +
            "/场地[:：](?<room>.+?)/教师[:：](?<teacher>.+?)/考核方式[:：](?<assess>.+?)/学分[:：](?<credit>[\\d.]+)$"

    private val params = PdfTableParams(
        columnAnchor = "星期",
        rowGapPt = 14f,
        headerPerPage = true,
        cellRegex = cellRegex,
        noiseDrop = listOf("理论学时")
    )

    private fun header(page: Int = 0): List<TextFragment> = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"
    ).mapIndexed { i, name -> TextFragment(page, 100f + i * step, headerY, name) }

    /** 列 center 换算成 x：正文左对齐，取列 center 即可落在该列。 */
    private fun x(col: Int): Float = 100f + col * step

    @Test
    fun `一个单元格的多行按 12pt 行距拼回一段`() {
        val fragments = header() + listOf(
            TextFragment(0, x(0), 640f, "政治经济学"),
            TextFragment(0, x(0), 628f, "(1-2节)2-3周/校区:校本部"),
            TextFragment(0, x(0), 616f, "/场地:信息科技大楼北-"),
            TextFragment(0, x(0), 604f, "401/教师:肖薇薇/考核方式:考试/学分:3")
        )
        val result = PdfLayoutParser.parse(fragments, params)
        assertEquals("应合成一个格子，实际 ${result.slots.size}", 1, result.slots.size)
        val slot = result.slots.first()
        assertEquals("政治经济学", slot.name)
        assertEquals(1, slot.dayOfWeek)
        assertEquals(1, slot.startSection)
        assertEquals(2, slot.endSection)
        // 中文换行处不加分隔符，否则「大楼北-」与「401」之间会多出空格
        assertEquals("信息科技大楼北-401", slot.room)
        assertEquals("肖薇薇", slot.teacher)
        assertEquals(WeekMask.range(2, 3), slot.weekMask)
    }

    @Test
    fun `间距 15pt 的相邻块算两个格子`() {
        val fragments = header() + listOf(
            TextFragment(0, x(0), 640f, "课程甲(1-2节)1-16周/校区:校本部/场地:A101/教师:甲/考核方式:考试/学分:2"),
            TextFragment(0, x(0), 624.5f, "课程乙(3-4节)1-16周/校区:校本部/场地:A102/教师:乙/考核方式:考试/学分:2")
        )
        val result = PdfLayoutParser.parse(fragments, params)
        assertEquals(2, result.slots.size)
        assertEquals(setOf("课程甲", "课程乙"), result.slots.map { it.name }.toSet())
        assertEquals(setOf(1, 3), result.slots.map { it.startSection }.toSet())
    }

    @Test
    fun `续表页没有表头时沿用上一页的列锚`() {
        val fragments = header(0) + listOf(
            TextFragment(0, x(3), 640f, "周四课(1-2节)1-8周/校区:校本部/场地:C301/教师:丙/考核方式:考查/学分:1"),
            // 第 2 页从页顶就是内容，没有表头
            TextFragment(1, x(4), 690f, "周五课(5-6节)1-8周/校区:校本部/场地:C302/教师:丁/考核方式:考查/学分:1")
        )
        val result = PdfLayoutParser.parse(fragments, params)
        assertEquals(2, result.slots.size)
        assertNotNull(result.slots.firstOrNull { it.dayOfWeek == 5 && it.name == "周五课" })
    }

    @Test
    fun `噪声行与表头本身不产出格子`() {
        val fragments = header() + listOf(
            TextFragment(0, x(0), 690f, "理论学时"),   // 噪声
            TextFragment(0, x(0), 640f, "课程甲(1-2节)1-16周/校区:校本部/场地:A101/教师:甲/考核方式:考试/学分:2")
        )
        val result = PdfLayoutParser.parse(fragments, params)
        assertEquals(1, result.slots.size)
    }

    @Test
    fun `单元格不符合模板时跳过而不是瞎猜`() {
        val fragments = header() + listOf(
            TextFragment(0, x(0), 640f, "实践课程"),        // 页脚标签，没有节次周次
            TextFragment(0, x(0), 610f, "课程甲(1-2节)1-16周/校区:校本部/场地:A101/教师:甲/考核方式:考试/学分:2")
        )
        val result = PdfLayoutParser.parse(fragments, params)
        assertEquals(1, result.slots.size)
        assertEquals("课程甲", result.slots.first().name)
    }

    @Test
    fun `单周会被正确展开`() {
        val fragments = header() + listOf(
            TextFragment(0, x(1), 640f, "实验(1-2节)3-16周(单)/校区:校本部/场地:实验中心/教师:戊/考核方式:考查/学分:1")
        )
        val result = PdfLayoutParser.parse(fragments, params)
        assertEquals(1, result.slots.size)
        val mask = result.slots.first().weekMask
        assertTrue("单周应只包含奇数周", mask and WeekMask.bit(3) != 0)
        assertTrue("不应包含第 4 周", mask and WeekMask.bit(4) == 0)
    }
}
