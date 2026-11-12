package app.nbult.lite.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则是运行时数据，坏了不能等到解析课表才发现。
 * 这里盯三件事：**能加载**、**正则能编译**、**坏包会被拒且给出原因**。
 */
class SchoolRulesTest {

    private fun readAsset(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "找不到 $path" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `内置宁大规则能加载且字段齐全`() {
        val json = readAsset("schools/nbu/rules.json")
        val result = SchoolRules.parse(json)
        assertTrue("应解析成功，实际：$result", result is SchoolRulesResult.Ok)
        val rules = (result as SchoolRulesResult.Ok).rules

        assertEquals("nbu", rules.id)
        assertEquals("宁波大学", rules.name)
        assertTrue(rules.signature.isNotEmpty())
        // 七个星期一个都不能缺，否则某天的课会静默消失
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), rules.tokens.dayWords.values.toSet())
        assertTrue(rules.maxWeeks in 10..30)
        assertTrue(rules.maxSections in 8..16)
    }

    @Test
    fun `内置规则的关键正则能匹配真实写法`() {
        val rules = (SchoolRules.parse(readAsset("schools/nbu/rules.json")) as SchoolRulesResult.Ok).rules

        assertNotNull("周次区间", rules.patterns.weekRange.find("2-16周"))
        assertNotNull("周次列表", rules.patterns.weekList.find("1,3,5,7周"))
        assertNotNull("单周标记", rules.patterns.weekOdd.find("(单)"))
        assertNotNull("节次区间", rules.patterns.sectionRange.find("第1-2节"))
        assertNotNull("星期", rules.patterns.dayPattern.find("星期一"))
        assertNotNull("教师", rules.patterns.teacherLabelled.find("教师：张三"))
    }

    @Test
    fun `宁大规则带四段附属配置且指向真实文件`() {
        val rules = (SchoolRules.parse(readAsset("schools/nbu/rules.json")) as SchoolRulesResult.Ok).rules
        assertEquals("calendar/nbu_calendar.json", rules.calendarPath)
        assertEquals("news/sources.json", rules.newsPath)
        assertTrue(rules.capabilities.attendance)
        assertTrue(rules.importer.supports("xlsx"))
        assertNotNull("PDF 参数应存在", rules.importer.pdf)
        // 引用的文件必须真的存在，否则线上只能靠日志发现
        assertNotNull("缺作息文件", readAsset(rules.calendarPath!!))
        assertNotNull("缺资讯源文件", readAsset(rules.newsPath!!))
    }

    @Test
    fun `湘潭规则关闭签到且只认 PDF`() {
        val rules = (SchoolRules.parse(readAsset("schools/xtu/rules.json")) as SchoolRulesResult.Ok).rules
        assertEquals("xtu", rules.id)
        assertFalse("湘潭没有签到系统，必须关闭", rules.capabilities.attendance)
        assertTrue(rules.importer.supports("pdf"))
        assertFalse("湘潭不走 Excel 导入", rules.importer.supports("xlsx"))
        // 作息已按《湘潭大学夏季作息时间表》落地，且文件必须真实存在
        assertEquals("schools/xtu/calendar.json", rules.calendarPath)
        assertNotNull("缺作息文件", readAsset(rules.calendarPath!!))
        assertNotNull("资讯源应指向文件", rules.newsPath)
    }

    @Test
    fun `两所学校的域名各自独立`() {
        val nbu = (SchoolRules.parse(readAsset("schools/nbu/rules.json")) as SchoolRulesResult.Ok).rules
        val xtu = (SchoolRules.parse(readAsset("schools/xtu/rules.json")) as SchoolRulesResult.Ok).rules
        assertEquals(listOf("nbu.edu.cn"), nbu.domains)
        assertEquals(listOf("xtu.edu.cn"), xtu.domains)
    }

    @Test
    fun `湘潭的资讯源文件存在且有两个栏目`() {
        val rules = (SchoolRules.parse(readAsset("schools/xtu/rules.json")) as SchoolRulesResult.Ok).rules
        val news = readAsset(rules.newsPath!!)
        assertTrue("应含综合新闻", news.contains("news.xtu.edu.cn"))
        assertTrue("应含教务处", news.contains("jwc.xtu.edu.cn"))
    }

    @Test
    fun `缺少 capabilities 时默认全开`() {
        // 老规则文件没有这一段，行为要和过去一致
        val json = readAsset("schools/nbu/rules.json")
            .replace(Regex(",\\s*\"capabilities\": \\{[^}]*\\}"), "")
        val rules = (SchoolRules.parse(json) as SchoolRulesResult.Ok).rules
        assertTrue(rules.capabilities.attendance)
        assertTrue(rules.capabilities.login)
    }

    @Test
    fun `schema 版本不符会被拒绝`() {
        val json = readAsset("schools/nbu/rules.json").replace("\"schema\": 1", "\"schema\": 99")
        val result = SchoolRules.parse(json)
        assertTrue(result is SchoolRulesResult.Failed)
        assertTrue((result as SchoolRulesResult.Failed).reason.contains("schema"))
    }

    @Test
    fun `坏正则会指出是哪一条`() {
        // 未闭合的括号
        val json = readAsset("schools/nbu/rules.json")
            .replace("\"weekRange\": \"", "\"weekRange\": \"(")
        val result = SchoolRules.parse(json)
        assertTrue(result is SchoolRulesResult.Failed)
        val reason = (result as SchoolRulesResult.Failed).reason
        assertTrue("原因应点名 weekRange，实际：$reason", reason.contains("weekRange"))
    }

    @Test
    fun `缺星期映射会被拒绝`() {
        // 删「三」而不是「天」：日与天都映射到 7，删天不会让 7 缺失
        val json = readAsset("schools/nbu/rules.json").replace("\"三\": 3", "\"x\": 8")
        val result = SchoolRules.parse(json)
        assertTrue(result is SchoolRulesResult.Failed)
        assertTrue((result as SchoolRulesResult.Failed).reason.contains("dayWords"))
    }

    @Test
    fun `清单里内置学校指向的规则文件存在`() {
        val index = readAsset("schools/index.json")
        assertTrue(index.contains("\"nbu\""))
        assertNotNull(readAsset("schools/nbu/rules.json"))
    }
}
