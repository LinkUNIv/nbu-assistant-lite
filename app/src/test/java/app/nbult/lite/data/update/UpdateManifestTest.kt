package app.nbult.lite.data.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单判定是更新链路唯一"会不会装错包"的关口。
 *
 * 静态镜像与 Release API 两条路共用 [AppUpdateChecker.evaluateManifest]，
 * 所以这里测的就是两条路共同的判定：通道不符要拒绝、版本不新要放弃、
 * 缺校验值要报错。
 */
class UpdateManifestTest {

    private fun manifest(
        versionCode: Int,
        channel: String = UpdateChannel.current,
        apkUrl: String = "https://example.com/a.apk",
        sha: String = "abc123"
    ): JSONObject = JSONObject(
        """
        {
          "versionCode": $versionCode,
          "versionName": "0.${versionCode}",
          "channel": "$channel",
          "apkUrl": "${apkUrl}",
          "sha256": "$sha",
          "notes": "测试",
          "size": 1024,
          "generatedAt": ${System.currentTimeMillis()}
        }
        """.trimIndent()
    )

    @Test
    fun `更新的版本会给出可安装信息`() {
        val result = AppUpdateChecker.evaluateManifest(manifest(versionCode = 99999))
        assertTrue(result is UpdateState.Available)
        val info = (result as UpdateState.Available).info
        assertEquals(99999, info.versionCode)
        assertEquals("https://example.com/a.apk", info.apkUrl)
        assertEquals("abc123", info.sha256)
    }

    @Test
    fun `同版本或更旧一律视为已是最新`() {
        assertTrue(AppUpdateChecker.evaluateManifest(manifest(versionCode = 1)) is UpdateState.UpToDate)
        assertTrue(
            AppUpdateChecker.evaluateManifest(manifest(versionCode = app.nbult.lite.BuildConfig.VERSION_CODE))
                is UpdateState.UpToDate
        )
    }

    @Test
    fun `通道不符的清单直接忽略`() {
        val other = if (UpdateChannel.current.equals("debug", true)) "preview" else "debug"
        val result = AppUpdateChecker.evaluateManifest(manifest(versionCode = 99999, channel = other))
        // 装不上的包，提示了也没用 —— 这里必须是"无更新"，不是"有更新"
        assertTrue(result is UpdateState.UpToDate)
    }

    @Test
    fun `缺校验值的清单不认`() {
        val result = AppUpdateChecker.evaluateManifest(manifest(versionCode = 99999, sha = ""))
        assertTrue(result is UpdateState.Failed)
    }

    @Test
    fun `缺下载地址的清单不认`() {
        val result = AppUpdateChecker.evaluateManifest(manifest(versionCode = 99999, apkUrl = ""))
        assertTrue(result is UpdateState.Failed)
    }
}
