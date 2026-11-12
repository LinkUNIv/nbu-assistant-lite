package app.nbult.lite.parser

import app.nbult.lite.domain.WeekMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次解析的回归测试。
 *
 * 这组用例源自一次线上事故：湘潭课表第 10 周应有 17 门课，实际只显示 2 门。
 * 原因是旧实现用 `find()` 只取第一段 `2-5周`，后面的 `,10-15周` 被静默丢弃。
 */
class WeekParserTest {

    private fun mask(text: String) = WeekParser.mask(text)

    private fun weeks(text: String): List<Int> {
        val m = mask(text) ?: return emptyList()
        return (1..WeekMask.MAX_WEEKS).filter { WeekMask.isOn(m, it) }
    }

    @Test
    fun `单段区间`() {
        assertEquals((2..5).toList(), weeks("2-5周"))
        assertEquals((1..16).toList(), weeks("第1-16周"))
    }

    @Test
    fun `多段区间必须全部合并`() {
        // 事故用例：只取第一段就会丢掉 10-15
        assertEquals(listOf(2, 3, 4, 5) + (10..15).toList(), weeks("2-5周,10-15周"))
        assertEquals(listOf(2, 3, 4) + (7..18).toList(), weeks("2-5周,7-18周").filter { it != 5 })
    }

    @Test
    fun `四段混合写法`() {
        val got = weeks("3-4周,10-11周,13-17周(单),18周")
        assertEquals(listOf(3, 4, 10, 11, 13, 15, 17, 18), got)
    }

    @Test
    fun `奇偶只作用于所在段`() {
        // 若奇偶作用于整串，10-11 会被吞掉
        val got = weeks("2-4周,10-11周,13-17周(单)")
        assertTrue("10、11 必须保留", got.containsAll(listOf(10, 11)))
        assertTrue("13-17 只留奇数", got.containsAll(listOf(13, 15, 17)))
        assertTrue("不该出现 14、16", got.none { it == 14 || it == 16 })
    }

    @Test
    fun `双周标记`() {
        assertEquals(listOf(4, 6, 8), weeks("3-8周(双)"))
        assertEquals(listOf(4, 6, 8), weeks("第3-8周（双）"))
    }

    @Test
    fun `单周与区间不要互相误伤`() {
        // "1-16周" 不能被 single 规则抓成 16
        assertEquals((1..16).toList(), weeks("1-16周"))
        assertEquals(listOf(7), weeks("7周"))
    }

    @Test
    fun `中文逗号和顿号都算分隔`() {
        assertEquals((2..5).toList() + (10..15).toList(), weeks("2-5周，10-15周"))
        assertEquals((2..5).toList() + (10..15).toList(), weeks("2-5周、10-15周"))
    }

    @Test
    fun `没有周次时返回 null 而不是猜`() {
        assertNull(mask("逸夫楼-206"))
        assertNull(mask(""))
        assertNull(mask("教师:张三"))
    }

    @Test
    fun `超出上限的周次被裁掉`() {
        assertTrue(weeks("1-99周").none { it > WeekMask.MAX_WEEKS })
    }
}
