package app.nbult.lite.parser.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一格多课。
 *
 * 中南民大的课表打印页里，周三 5-6 节同时排着「3-18 周(双)」与「5-20 周(单)」两门。
 * 旧实现用 `find` 只取第一个匹配，第二门静默消失 —— 用户不会知道课表少了一节。
 */
class PdfMultiCourseCellTest {

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
            """(?<teacher>[^\d]*)\s*(?<room>.+)$"""
    )

    private fun slotsOf(cell: String, day: Int = 3) =
        PdfLayoutParser.parse(
            fragments = listOf(
                TextFragment(page = 0, x = 300f, y = 700f, text = "星期一"),
                TextFragment(page = 0, x = 400f, y = 700f, text = "星期二"),
                TextFragment(page = 0, x = 300f, y = 690f, text = cell)
            ),
            params = params
        ).slots

    @Test
    fun `一格两门课都要解析出来`() {
        val cell = "人工智能与计算思维[02]3-18周(双)(第5-6节)李芸16号楼S160211" +
            "人工智能与计算思维(实验)[02]5-20周(单)(第5-6节)李芸,李作主16号楼S160211"
        val slots = slotsOf(cell)
        assertEquals("应当解析出两门，实际 ${slots.map { it.name }}", 2, slots.size)
        assertEquals(5, slots[0].startSection)
        assertEquals(6, slots[0].endSection)
        // 单双周互补：两门的周次位图不应完全相同
        assertTrue("单双周应落成不同的位图", slots[0].weekMask != slots[1].weekMask)
    }

    /** 先确认正则本身能吃下这串文本，避免把"正则写错"误诊成"解析流程有问题"。 */
    @Test
    fun `正则能匹配构造出来的单元格文本`() {
        val cell = "法律逻辑学[01]4-19周(第1-2节)教师甲 15号楼15609"
        val m = Regex(params.cellRegex!!).find(cell)
        assertTrue("cellRegex 没匹配上：$cell", m != null)
        assertEquals("法律逻辑学", m!!.groups["name"]?.value?.trim())
        assertEquals("1", m.groups["s1"]?.value)
        assertEquals("2", m.groups["s2"]?.value)
    }

    @Test
    fun `一格一门课时行为不变`() {
        val cell = "法律逻辑学[01]4-19周(第1-2节)教师甲 15号楼15609"
        val slots = slotsOf(cell)
        assertEquals(1, slots.size)
        assertEquals("法律逻辑学", slots[0].name)
        assertEquals(1, slots[0].startSection)
        assertEquals(2, slots[0].endSection)
        assertTrue(slots[0].teacher.contains("教师甲"))
    }

    @Test
    fun `教师多人用逗号分隔时不丢人`() {
        val cell = "习近平法治思想概论[01]4-19周(第3-4节)教师甲,教师乙,教师丙 1号楼1-301"
        val slots = slotsOf(cell)
        assertEquals(1, slots.size)
        assertEquals("教师甲,教师乙,教师丙", slots[0].teacher.substringBefore(' ').trim())
    }
}
