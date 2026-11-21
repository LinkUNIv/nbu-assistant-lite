package app.nbult.lite.parser.pdf

import org.junit.Ignore
import org.junit.Test

/**
 * 临时探测：把几种参数组合跑一遍，看哪种能还原真实版面。
 *
 * 不是回归用例（跑完就删）；输出直接放在断言消息里，因为 gradle 只在失败时展示文本。
 */
@Ignore("探测用：跑一次打印参数组合结果，结论记在 docs/dev/PENDING 的 scmzu 条目里；不做回归")
class ScmzuTuneProbeTest {

    private fun fragments(): List<TextFragment> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("pdf/scmzu_fragments.tsv")) {
            "找不到夹具"
        }.bufferedReader().use { it.readText() }
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            TextFragment(page = p[0].toInt(), x = p[1].toFloat(), y = -p[2].toFloat(), text = p.drop(3).joinToString("\t"))
        }.toList()
    }

    @Test
    fun `跑一组参数`() {
        val cellRegex = """^(?<name>[^\[]+?)\s*\[(?<code>[^\]]*)\]\s*""" +
            """(?<weeks>\d[\d,\-]*\s*周(?:\s*[（(]\s*[单双]\s*[)）])?)\s*""" +
            """[（(]\s*第?\s*(?<s1>\d+)\s*[-—~]\s*(?<s2>\d+)\s*节\s*[)）]\s*""" +
            """(?<teacher>[^\d]*)\s*(?<room>.+)$"""
        val noise = listOf("节次", "课程名称", "教学周次", "备注", "上课老师", "同学课表")

        val sb = StringBuilder()
        for (gap in listOf(10f, 12f, 13.5f, 15f, 18f, 22f)) {
            for (merge in listOf(true, false)) {
                for (anchor in listOf("星期", "星期")) {
                    val params = PdfTableParams(
                        columnAnchor = anchor,
                        rowGapPt = gap,
                        headerPerPage = false,
                        namePrefixMerge = merge,
                        multiCourse = true,
                        cellRegex = cellRegex,
                        noiseDrop = noise
                    )
                    val r = PdfLayoutParser.parse(fragments(), params)
                    val d356 = r.slots.count { it.dayOfWeek == 3 && it.startSection == 5 && it.endSection == 6 }
                    val names = r.slots.take(4).joinToString(",") { it.name.take(6) }
                    sb.append("gap=$gap merge=$merge → ${r.slots.size} 条 / 周3-5-6=$d356 / 前四:$names\n")
                }
            }
        }
        org.junit.Assert.fail(sb.toString())
    }
}
