package app.nbult.lite.school

import org.junit.Assert.assertEquals
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
