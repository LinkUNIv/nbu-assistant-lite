package app.nbult.lite.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资讯主键的命名空间。
 *
 * 背景：两所学校的栏目 idOffset 都是 0 时，同一篇文章号会算出同一个主键，
 * 后抓的 REPLACE 掉先抓的 —— 界面表现为「宁大的列表里混进湘潭的文章」。
 * 这里钉住「跨校不撞号」与「越界号段直接拒绝」。
 */
class NewsKeysTest {

    @Test
    fun `两所学校同号文章不会撞主键`() {
        val nbu = NewsKeys.articleKey(1003, 18266L)
        val xtu = NewsKeys.articleKey(2101, 18266L)
        assertNotEquals(nbu, xtu)
    }

    @Test
    fun `同一栏目同一文章号稳定`() {
        assertEquals(NewsKeys.articleKey(1003, 18266L), NewsKeys.articleKey(1003, 18266L))
    }

    @Test
    fun `相邻号段不会串味`() {
        // 栏目 1003 的最大号与 1004 的最小号必须仍然不等，且前者更小
        val last = NewsKeys.articleKey(1003, 999_999_999L)
        val first = NewsKeys.articleKey(1004, 0L)
        assertTrue("$last 应小于 $first", last < first)
    }

    @Test
    fun `越界文章号被拒绝`() {
        assertFalse(NewsKeys.fits(1_000_000_000L))
        assertFalse(NewsKeys.fits(-1L))
        assertTrue(NewsKeys.fits(0L))
        assertTrue(NewsKeys.fits(18_266L))
    }
}
