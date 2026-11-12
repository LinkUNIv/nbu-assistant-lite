package app.nbult.lite.data.update

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 0.37.21 的恶性 bug：下载到一半进程被杀，重启后那个半截文件被当成「已下好」，
 * 直接交给系统安装器，用户只看到「解析软件包时出现问题 (33) packageInfo is null」。
 *
 * 这里钉住不变量：**只有校验通过并写下标记的文件才算可安装**。
 */
class ApkVerificationTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "apk-verify-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun apk(content: String): File = File(dir, "nbu-lite-99999.apk").apply { writeText(content) }

    private fun mark(content: String, sha: String, size: Long): File =
        File(dir, "nbu-lite-99999.apk.ok").apply { writeText("99999|$sha|$size") }

    @Test
    fun `没有标记的文件不算可安装`() {
        // 正是线上那次：文件存在、非空，但没有经过校验
        val file = apk("half-downloaded")
        assertFalse(AppUpdateChecker.isVerifiedApk(file))
    }

    @Test
    fun `标记与内容一致时可安装`() {
        val content = "a complete apk"
        val file = apk(content)
        mark(content, sha256(content.toByteArray()), file.length())
        assertTrue(AppUpdateChecker.isVerifiedApk(file))
    }

    @Test
    fun `内容被截断后不可安装`() {
        val content = "a complete apk"
        val file = apk(content)
        mark(content, sha256(content.toByteArray()), file.length())
        // 模拟被截断：大小与 sha 都对不上了
        file.writeText("a comp")
        assertFalse(AppUpdateChecker.isVerifiedApk(file))
    }

    @Test
    fun `大小与标记不符时不可安装`() {
        val content = "a complete apk"
        val file = apk(content)
        mark(content, sha256(content.toByteArray()), 99999L)   // 标记写的是别的大小
        assertFalse(AppUpdateChecker.isVerifiedApk(file))
    }

    @Test
    fun `空文件与缺失文件都不可安装`() {
        val empty = File(dir, "nbu-lite-99998.apk").apply { writeText("") }
        assertFalse(AppUpdateChecker.isVerifiedApk(empty))
        assertFalse(AppUpdateChecker.isVerifiedApk(File(dir, "not-exist.apk")))
    }
}
