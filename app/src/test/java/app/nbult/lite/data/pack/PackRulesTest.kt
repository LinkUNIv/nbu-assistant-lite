package app.nbult.lite.data.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源包闸门的判定逻辑。
 *
 * 背景：0.38.4 起「新增学校 / 入口 / 校历」只发资源包，包因此能声明新域名。
 * 这里钉住三件事：**子域能过、冒充域不能过、内置学校只能增不能改**。
 */
class PackRulesTest {

    @Test
    fun `子域按后缀放行`() {
        assertTrue(PackRules.hostMatches("jwc.xtu.edu.cn", setOf("xtu.edu.cn")))
        assertTrue(PackRules.hostMatches("xtu.edu.cn", setOf("xtu.edu.cn")))
        assertTrue(PackRules.hostMatches("card.nbu.edu.cn", setOf("nbu.edu.cn", "xtu.edu.cn")))
    }

    @Test
    fun `后缀不能吃掉冒充域名`() {
        // 少了前导点就会把这几个放过去，这是白名单最经典的错
        assertFalse(PackRules.hostMatches("evailnbu.edu.cn", setOf("nbu.edu.cn")))
        assertFalse(PackRules.hostMatches("xtu.edu.cn.evil.com", setOf("xtu.edu.cn")))
        assertFalse(PackRules.hostMatches("", setOf("xtu.edu.cn")))
    }

    @Test
    fun `新增学校的域名只认 edu_cn 子域`() {
        assertTrue(PackRules.isEduDomain("xtu.edu.cn"))
        assertTrue(PackRules.isEduDomain("edu.cn"))
        assertFalse(PackRules.isEduDomain("xtu.com"))
        assertFalse(PackRules.isEduDomain("xtu.edu.cn/path"))
        assertFalse(PackRules.isEduDomain(".edu.cn"))
        assertFalse(PackRules.isEduDomain(""))
    }

    @Test
    fun `入口链接的 scheme 判定`() {
        val allowed = setOf("xtu.edu.cn")
        val plain = setOf("xtu.edu.cn")
        assertNull(PackRules.urlProblem("jwc.xtu.edu.cn", "https", allowed, plain))
        // 学校自己的站允许 http（一卡通那台机器只有 http）
        assertNull(PackRules.urlProblem("ecard.xtu.edu.cn", "http", allowed, plain))
        // 第三方域名的 http 一律不行
        assertEquals(
            "引入了新域名：example.com",
            PackRules.urlProblem("example.com", "https", allowed, plain)
        )
        // 学校域名的 http，但不在明文白名单里
        assertEquals(
            "使用了非 https：xtu.edu.cn",
            PackRules.urlProblem("xtu.edu.cn", "http", allowed, emptySet())
        )
    }

    @Test
    fun `包内清单删掉内置学校会被拒`() {
        val builtin = listOf(
            PackRules.SchoolRef("nbu", "宁波大学", "schools/nbu/rules.json"),
            PackRules.SchoolRef("xtu", "湘潭大学", "schools/xtu/rules.json")
        )
        val incoming = listOf(builtin[0])
        assertEquals("包内清单删掉了内置学校：xtu", PackRules.indexProblem(incoming, builtin))
    }

    @Test
    fun `内置学校的名称与规则路径不可改`() {
        val builtin = listOf(PackRules.SchoolRef("nbu", "宁波大学", "schools/nbu/rules.json"))
        val renamed = listOf(PackRules.SchoolRef("nbu", "宁波大学（假）", "schools/nbu/rules.json"))
        assertEquals("内置学校 nbu 的名称被改动", PackRules.indexProblem(renamed, builtin))

        val repointed = listOf(PackRules.SchoolRef("nbu", "宁波大学", "schools/evil/rules.json"))
        assertEquals("内置学校 nbu 的规则路径被改动", PackRules.indexProblem(repointed, builtin))
    }

    @Test
    fun `删掉非内置学校不会被拦`() {
        // 湘潭自 0.38.7 起只存在于资源包：包不再列出它，等于下架，必须放行
        val builtin = listOf(PackRules.SchoolRef("nbu", "宁波大学", "schools/nbu/rules.json"))
        assertNull(PackRules.indexProblem(builtin, builtin))
    }

    @Test
    fun `新增学校与保留内置可以同时通过`() {
        val builtin = listOf(PackRules.SchoolRef("nbu", "宁波大学", "schools/nbu/rules.json"))
        val incoming = builtin + PackRules.SchoolRef("csu", "中南大学", "schools/csu/rules.json")
        assertNull(PackRules.indexProblem(incoming, builtin))
    }

    @Test
    fun `学校 id 不合法会被拒`() {
        val incoming = listOf(PackRules.SchoolRef("CSU", "中南大学", "schools/CSU/rules.json"))
        assertEquals("学校 id 不合法：CSU", PackRules.indexProblem(incoming, emptyList()))
    }
}
