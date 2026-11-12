package app.nbult.lite.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单是「数据不外发」的唯一技术边界，写错一个后缀匹配就等于没做。
 * 这里把最容易犯的四种错都钉住：后缀前缀、只放行主域、大小写、校域放行的范围。
 */
class HostWhitelistTest {

    @Test
    fun `校内域名及其子域放行`() {
        assertTrue(HostWhitelist.allows("nbu.edu.cn"))
        assertTrue(HostWhitelist.allows("attendance.nbu.edu.cn"))
        assertTrue(HostWhitelist.allows("news.nbu.edu.cn"))
        assertTrue(HostWhitelist.allows("a.b.nbu.edu.cn"))
    }

    @Test
    fun `校外只放行明确列出的`() {
        assertTrue(HostWhitelist.allows("api.github.com"))
        assertTrue(HostWhitelist.allows("raw.githubusercontent.com"))
        assertTrue(HostWhitelist.allows("cdn.jsdelivr.net"))
    }

    @Test
    fun `不相关的域名一律拒绝`() {
        assertFalse(HostWhitelist.allows("example.com"))
        assertFalse(HostWhitelist.allows("gist.github.com"))
        assertFalse(HostWhitelist.allows("evil.jsdelivr.net"))
    }

    @Test
    fun `后缀伪装不能被放过`() {
        // 少了前导点，这两个都会被 endsWith("nbu.edu.cn") 误放
        assertFalse(HostWhitelist.allows("evailnbu.edu.cn"))
        assertFalse(HostWhitelist.allows("nbu.edu.cn.evil.com"))
        assertFalse(HostWhitelist.allows("fakegithub.com"))
    }

    @Test
    fun `github_com 只放行本仓库路径`() {
        assertTrue(
            HostWhitelist.allows(
                "github.com",
                "/LinkUNIv/nbu-assistant-lite/releases/download/v1/manifest.json"
            )
        )
        assertFalse(HostWhitelist.allows("github.com", "/someone-else/repo/releases"))
        assertFalse(HostWhitelist.allows("github.com", "/"))
    }

    @Test
    fun `资产重定向域名放行`() {
        assertTrue(HostWhitelist.allows("objects.githubusercontent.com", "/x"))
        assertTrue(HostWhitelist.allows("release-assets.githubusercontent.com", "/x"))
    }

    @Test
    fun `当前学校的域名会临时放行`() {
        HostWhitelist.registerSchoolDomains(listOf("xtu.edu.cn"))
        try {
            assertTrue(HostWhitelist.allows("news.xtu.edu.cn"))
            assertTrue(HostWhitelist.allows("jwc.xtu.edu.cn"))
            // 放了 xtu 不等于放开所有学校
            assertFalse(HostWhitelist.allows("news.abc.edu.cn"))
        } finally {
            HostWhitelist.registerSchoolDomains(emptyList())
        }
        assertFalse("注销后应恢复拒绝", HostWhitelist.allows("news.xtu.edu.cn"))
    }

    @Test
    fun `大小写与末尾点归一`() {
        assertTrue(HostWhitelist.allows("Attendance.NBU.EDU.CN"))
        assertTrue(HostWhitelist.allows("api.github.com."))
        assertFalse(HostWhitelist.allows(""))
    }
}
