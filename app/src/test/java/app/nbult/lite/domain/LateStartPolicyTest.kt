package app.nbult.lite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「某些楼栋推迟上课」的判定。
 *
 * 湘潭的逸夫楼、信息科技大楼比作息表晚 10 分钟，作息表是全校一张不能改，
 * 于是按地点加偏移：卡片标一句，提醒顺延。这里钉住匹配口径与异常输入。
 */
class LateStartPolicyTest {

    private val xtuRules = listOf(
        LateStartRule(contains = listOf("逸夫楼", "信息科技大楼"), minutes = 10, label = "推迟 10 分钟上课")
    )

    @Test
    fun `逸夫楼与信息科技大楼命中`() {
        assertEquals(10, LateStartPolicy.minutesOf("逸夫楼A301", xtuRules))
        assertEquals(10, LateStartPolicy.minutesOf("信息科技大楼302", xtuRules))
        assertEquals(10, LateStartPolicy.minutesOf(" 信息科技 大楼 205 ", xtuRules))
    }

    @Test
    fun `其他楼不命中`() {
        assertEquals(0, LateStartPolicy.minutesOf("第一教学楼101", xtuRules))
        // 「信息楼」不能被「信息科技大楼」误命中
        assertEquals(0, LateStartPolicy.minutesOf("信息楼204", xtuRules))
        assertEquals(0, LateStartPolicy.minutesOf("", xtuRules))
    }

    @Test
    fun `规则为空或分钟数非法时不生效`() {
        assertEquals(0, LateStartPolicy.minutesOf("逸夫楼A301", emptyList()))
        val broken = listOf(LateStartRule(contains = listOf("逸夫楼"), minutes = 0))
        assertEquals(0, LateStartPolicy.minutesOf("逸夫楼A301", broken))
        val noKey = listOf(LateStartRule(contains = emptyList(), minutes = 10))
        assertEquals(0, LateStartPolicy.minutesOf("逸夫楼A301", noKey))
    }

    @Test
    fun `命中时给出完整规则用于展示`() {
        val hit = LateStartPolicy.of("逸夫楼A301", xtuRules)
        assertNotNull(hit)
        assertEquals(10, hit!!.minutes)
        assertEquals("推迟 10 分钟上课", hit.label)
        assertNull(LateStartPolicy.of("第一教学楼101", xtuRules))
    }

    @Test
    fun `全角空格与大小写归一`() {
        val rules = listOf(LateStartRule(contains = listOf("yifu building"), minutes = 5))
        assertEquals(5, LateStartPolicy.minutesOf("Yifu\u3000Building-3F", rules))
    }

    @Test
    fun `时刻顺延跨小时与跨天都算对`() {
        assertEquals("08:10", LateStartPolicy.shift("08:00", 10))
        assertEquals("09:05", LateStartPolicy.shift("08:55", 10))
        assertEquals("00:05", LateStartPolicy.shift("23:55", 10))
        // 0 或负数视为不推迟，原样返回
        assertEquals("08:00", LateStartPolicy.shift("08:00", 0))
        // 解析不了就原样返回，不能抛
        assertEquals("--", LateStartPolicy.shift("--", 10))
    }
}
