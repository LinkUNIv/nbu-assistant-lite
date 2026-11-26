package app.nbult.lite.parser.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宁大教务 PDF 的版式回归。
 *
 * 夹具 `pdf/nbu_fragments.tsv` 由真实导出件生成（页/坐标原样，姓名与学号已脱敏）。
 *
 * 这份版式有两个坑，都在这里钉住：
 * 1. 表头左上角有一个**单独的「星期」**，它只是角标。把它当成列锚会让锚点整体多一个，
 *    所有课右移一天 —— 而且解析"成功"，只是全错。
 * 2. 格子里写的是 `1-17周,星期2,3-4节,北区2号教学楼103（白板2）`，没有 `(3-4节)` 括号，
 *    按老的节次锚点回溯切不开"一格多课"，第二门会静默消失。
 */
class NbuPdfFixtureTest {

    /** 与 `schools/nbu/rules.json` 的 `importer.pdf` 保持一致。 */
    private val params = PdfTableParams(
        columnAnchor = "星期",
        rowGapPt = 14f,
        headerPerPage = true,
        multiCourse = true,
        cellRegex = "^(?:【(?<category>[^】]*)】)?" +
            "(?<name>[0-9A-Za-z]{5,12}-[^\\[［]+?)\\s*[\\[［](?<code>[^\\]］]{1,8})[\\]］]\\s*" +
            "(?<teacher>[^,，]*?(?:[,，][^,，]*?)*?)" +
            "(?<weeks>(?:[1-9]|1[0-9]|20)[-—~]\\d{1,2}周(?:[（(][单双][)）])?)" +
            ",星期(?<day>\\d),(?<s1>\\d+)-(?<s2>\\d+)节[,，]?" +
            "(?<room>.*)$",
        tuning = app.nbult.lite.school.PdfTuning(
            courseStartMarker = "【(?:理论|实验|上机|实训|实践)】"
        )
    )

    private fun fragments(): List<TextFragment> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("pdf/nbu_fragments.tsv")) {
            "找不到夹具 pdf/nbu_fragments.tsv"
        }.bufferedReader().use { it.readText() }

        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            TextFragment(
                page = parts[0].toInt(),
                x = parts[1].toFloat(),
                y = -parts[2].toFloat(),
                text = parts.drop(3).joinToString("\t")
            )
        }.toList()
    }

    private fun parse() = PdfLayoutParser.parse(fragments(), params)

    @Test
    fun `整份样本至少取出 18 个时段`() {
        val result = parse()
        assertTrue("实际 ${result.slots.size} 条", result.slots.size >= 18)
    }

    @Test
    fun `裸星期角标不算一列_周一不能被挤到周二`() {
        val slots = parse().slots
        val monday = slots.filter { it.dayOfWeek == 1 }
        assertTrue("周一应当有课（表头多算一列时这里会是空的）", monday.isNotEmpty())

        // 概率统计A 在样本里排了两次：周二 3-4 节、周四 5-6 节（双周）
        val prob = slots.filter { it.name.contains("概率统计A") }
        assertTrue("没解析出概率统计A", prob.isNotEmpty())
        assertTrue(
            "周二 3-4 节那条丢了，实际 ${prob.map { it.dayOfWeek to it.startSection }}",
            prob.any { it.dayOfWeek == 2 && it.startSection == 3 && it.endSection == 4 }
        )
        assertTrue(
            "周四 5-6 节那条丢了，实际 ${prob.map { it.dayOfWeek to it.startSection }}",
            prob.any { it.dayOfWeek == 4 && it.startSection == 5 && it.endSection == 6 }
        )
    }

    @Test
    fun `一格多课按课类型前缀切开`() {
        val slots = parse().slots
        // 周二最后一格同时排了「Matlab与数值计算」与「复变函数与积分变换」
        assertTrue(slots.any { it.name.contains("Matlab与数值计算") })
        assertTrue("同一格里的第二门被吞掉了", slots.any { it.name.contains("复变函数与积分变换") })
        // 周五 5-6 节有两个不同的实验室，两门都要留
        val labs = slots.filter { it.name.contains("Matlab与数值计算") && it.dayOfWeek == 5 }
        assertTrue("周五两个实验室应各成一条，实际 ${labs.map { it.room }}", labs.size == 2)
    }

    @Test
    fun `没解析成课的格子会进 unmatched`() {
        val result = parse()
        // 页眉「2026-2027学年」「1学期课表」与脱敏后的学生姓名都不是课程，
        // 应被记录而不是静默丢弃
        val texts = result.unmatched.map { it.text }
        assertTrue("实际 $texts", texts.any { it.contains("学年") })
        assertTrue("实际 $texts", texts.any { it.contains("学期课表") })
    }
}
