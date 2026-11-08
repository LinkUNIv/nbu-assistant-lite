package app.nbult.lite.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通道过滤的回归测试。
 *
 * 守的是一个真实踩过的坑：协作者在 Preview 之后连发 Debug 包，
 * 只取列表第一条就会把 `app.nbult.lite.debug` 推给 Preview 用户 ——
 * 包名与签名都不同，下完也装不上。
 */
class UpdateChannelTest {

    private fun refs(vararg pairs: Pair<String, Boolean>) =
        pairs.map { ReleaseRef(it.first, it.second) }

    @Test
    fun tag后缀映射到通道() {
        assertEquals("preview", UpdateChannel.ofTag("v0.37.7-Preview"))
        assertEquals("debug", UpdateChannel.ofTag("v0.37.6-Debug"))
        assertEquals("release", UpdateChannel.ofTag("v1.0.0"))
        // 历史上有既不带通道也不带前缀写法的标签，一律按正式版处理
        assertEquals("release", UpdateChannel.ofTag("v0.35.13"))
    }

    @Test
    fun 最新是Debug时仍挑出Preview() {
        val list = refs(
            "v0.37.9-Debug" to false,
            "v0.37.8-Debug" to false,
            "v0.37.7-Preview" to false,
            "v0.37.6-Debug" to false
        )
        assertEquals(2, UpdateChannel.pick(list, "preview"))
    }

    @Test
    fun 同通道取第一条即最新() {
        val list = refs("v0.37.9-Preview" to false, "v0.37.7-Preview" to false)
        assertEquals(0, UpdateChannel.pick(list, "preview"))
    }

    @Test
    fun 本通道没有发布时返回负一() {
        val list = refs("v0.37.9-Debug" to false, "v0.37.8-Debug" to false)
        assertEquals(-1, UpdateChannel.pick(list, "preview"))
    }

    @Test
    fun 跳过草稿() {
        val list = refs("v0.38.0-Preview" to true, "v0.37.7-Preview" to false)
        assertEquals(1, UpdateChannel.pick(list, "preview"))
    }

    @Test
    fun 正式通道只认无后缀标签() {
        val list = refs("v0.37.9-Debug" to false, "v1.0.0" to false)
        assertEquals(1, UpdateChannel.pick(list, "release"))
    }
}
