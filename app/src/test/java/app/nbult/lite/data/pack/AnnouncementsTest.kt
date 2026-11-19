package app.nbult.lite.data.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公告解析的边界。
 *
 * 这一层是**唯一**的准入关卡：资源包由 CI 生成、内容来自人手写的 JSON，
 * 一旦某条写错，最坏结果是整包被拒（连带校历作息一起没了）。
 * 所以规则是「单条不合格只丢这一条」。
 */
class AnnouncementsTest {

    private fun json(vararg items: String) = """{"schema":1,"announcements":[${items.joinToString(",")}]}"""

    private fun one(
        id: String = "a1",
        title: String = "标题",
        body: String = "正文",
        severity: String = "banner",
        pinned: Boolean = false,
        link: String? = null,
        since: Long = 0,
        until: Long = 0,
        minApp: Int = 0,
        maxApp: Int = 0,
        revision: Int = 1
    ): String = buildString {
        append("""{"id":"$id","title":"$title","body":"$body","severity":"$severity","pinned":$pinned,"revision":$revision,"since":$since,"until":$until,"minApp":$minApp,"maxApp":$maxApp""")
        if (link != null) append(""","link":"$link"""")
        append("}")
    }

    private val now = 1_800_000_000L

    @Test
    fun `正文超长的那一条被丢掉，其它照常`() {
        val long = "字".repeat(Announcement.MAX_BODY + 1)
        val list = Announcements.parse(
            json(one(id = "bad", body = long), one(id = "ok")),
            appVersionCode = 3820,
            now = now
        )
        assertEquals(listOf("ok"), list.map { it.id })
    }

    @Test
    fun `过期的与未生效的都不显示`() {
        val list = Announcements.parse(
            json(
                one(id = "expired", until = now - 10),
                one(id = "future", since = now + 10),
                one(id = "alive", since = now - 10, until = now + 10)
            ),
            appVersionCode = 3820,
            now = now
        )
        assertEquals(listOf("alive"), list.map { it.id })
    }

    @Test
    fun `版本区间之外的不显示`() {
        val list = Announcements.parse(
            json(
                one(id = "old-only", maxApp = 3818),
                one(id = "new-only", minApp = 3900),
                one(id = "all")
            ),
            appVersionCode = 3820,
            now = now
        )
        assertEquals(listOf("all"), list.map { it.id })
    }

    @Test
    fun `链接指向安装包时只去掉链接，公告本身保留`() {
        val list = Announcements.parse(
            json(one(id = "apk", link = "https://example.com/app.apk")),
            appVersionCode = 3820,
            now = now
        )
        assertEquals(1, list.size)
        assertNull(list.first().link)
    }

    @Test
    fun `非 https 链接也去掉`() {
        val list = Announcements.parse(
            json(one(id = "http", link = "http://example.com/x")),
            appVersionCode = 3820,
            now = now
        )
        assertNull(list.first().link)
    }

    @Test
    fun `置顶排最前，其次按严重级别`() {
        val list = Announcements.parse(
            json(
                one(id = "silent", severity = "silent"),
                one(id = "blocking", severity = "blocking"),
                one(id = "pinned", pinned = true, severity = "silent")
            ),
            appVersionCode = 3820,
            now = now
        )
        assertEquals("pinned", list.first().id)
        assertEquals("blocking", list[1].id)
    }

    @Test
    fun `旧包的 level 字段仍能映射`() {
        val list = Announcements.parse(
            """{"announcements":[{"id":"legacy","title":"旧","body":"文","level":"critical"}]}""",
            appVersionCode = 3820,
            now = now
        )
        assertEquals(Announcement.Severity.BLOCKING, list.first().severity)
    }

    @Test
    fun `同一 id 只保留第一条`() {
        val list = Announcements.parse(
            json(one(id = "dup", title = "第一"), one(id = "dup", title = "第二")),
            appVersionCode = 3820,
            now = now
        )
        assertEquals(1, list.size)
        assertTrue(list.first().title == "第一")
    }
}
