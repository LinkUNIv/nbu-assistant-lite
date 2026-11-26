package app.nbult.lite.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单判定是更新链路唯一"会不会装错包"的关口。
 *
 * 这里直接构造 [AppUpdateChecker.ManifestData]，不经过 JSON —— 单测环境里
 * `org.json` 会撞上 Android 的桩实现（方法直接抛异常），本地与 CI 表现还不一致。
 */
class UpdateManifestTest {

    private fun data(
        versionCode: Int,
        channel: String = UpdateChannel.current,
        apkUrl: String = "https://example.com/a.apk",
        sha: String = "abc123"
    ) = AppUpdateChecker.ManifestData(
        versionCode = versionCode,
        versionName = "0.$versionCode",
        channel = channel,
        apkUrl = apkUrl,
        sha256 = sha,
        notes = "测试",
        size = 1024
    )

    @Test
    fun `更新的版本会给出可安装信息`() {
        val result = AppUpdateChecker.evaluateManifest(data(versionCode = 99999))
        assertTrue(result is UpdateState.Available)
        val info = (result as UpdateState.Available).info
        assertEquals(99999, info.versionCode)
        assertEquals("https://example.com/a.apk", info.apkUrl)
        assertEquals("abc123", info.sha256)
    }

    @Test
    fun `同版本或更旧一律视为已是最新`() {
        assertTrue(AppUpdateChecker.evaluateManifest(data(versionCode = 1)) is UpdateState.UpToDate)
        assertTrue(
            AppUpdateChecker.evaluateManifest(data(versionCode = app.nbult.lite.BuildConfig.VERSION_CODE))
                is UpdateState.UpToDate
        )
    }

    @Test
    fun `通道不符的清单直接忽略`() {
        val other = if (UpdateChannel.current.equals("debug", true)) "preview" else "debug"
        val result = AppUpdateChecker.evaluateManifest(data(versionCode = 99999, channel = other))
        assertTrue(result is UpdateState.UpToDate)
    }

    @Test
    fun `缺校验值的清单不认`() {
        assertTrue(AppUpdateChecker.evaluateManifest(data(versionCode = 99999, sha = "")) is UpdateState.Failed)
    }

    @Test
    fun `缺下载地址的清单不认`() {
        assertTrue(AppUpdateChecker.evaluateManifest(data(versionCode = 99999, apkUrl = "")) is UpdateState.Failed)
    }
}

/**
 * 静态清单的结论够不够用。
 *
 * 盯的是"最近没发版反而最慢"这件事：清单过期后旧逻辑直接弃用它去走 API，
 * 而 API 恰恰是校园网下最不稳的那条路。判定要保证"报着新版就用"，其余照旧。
 */
class UpdateStaticVerdictTest {

    private val available = UpdateState.Available(
        UpdateInfo(
            versionCode = 9999,
            versionName = "9.9.9",
            apkUrl = "https://example.com/a.apk",
            sha256 = "abc123",
            notes = "测试"
        )
    )

    @Test
    fun `清单报着新版时直接用_过期也信`() {
        assertEquals(false, AppUpdateChecker.needsApiCrossCheck(stale = true, state = available, fromCache = false))
        assertEquals(false, AppUpdateChecker.needsApiCrossCheck(stale = false, state = available, fromCache = true))
    }

    @Test
    fun `过期且说已最新时必须问 API`() {
        assertEquals(true, AppUpdateChecker.needsApiCrossCheck(stale = true, state = UpdateState.UpToDate, fromCache = false))
    }

    @Test
    fun `jsDelivr 说已最新要交叉核对`() {
        assertEquals(true, AppUpdateChecker.needsApiCrossCheck(stale = false, state = UpdateState.UpToDate, fromCache = true))
    }

    @Test
    fun `raw 且未过期说已最新时不必再问`() {
        assertEquals(false, AppUpdateChecker.needsApiCrossCheck(stale = false, state = UpdateState.UpToDate, fromCache = false))
    }
}
