package app.nbult.lite.data.update

import app.nbult.lite.BuildConfig
import app.nbult.lite.data.SettingsStore
import app.nbult.lite.util.AppLog

/**
 * 版本检查的「要不要查」与「查完记下来」。
 *
 * 这里只做调度与落盘，真正的网络请求仍在 [AppUpdateChecker]。
 * 分开的原因是调用点有三处（启动、关于页、红点），
 * 各自写一遍「查完写哪些字段」必然会漏掉某一个。
 */
object UpdateCenter {

    /** 自动检查间隔：24 小时。 */
    const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** 距上次检查是否已到期。从没查过也算到期。 */
    fun due(lastCheckAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        lastCheckAt <= 0L || now - lastCheckAt >= AUTO_INTERVAL_MS

    /**
     * 检查并落盘。
     *
     * 只有拿到确定结果才写 `updateCheckedAt`：失败不写，否则会被当成「刚查过、没有新版」，
     * 下一次启动要再等 24 小时才会重试。
     */
    suspend fun refresh(settings: SettingsStore): UpdateState {
        val result = AppUpdateChecker.check()
        val now = System.currentTimeMillis()
        when (result) {
            is UpdateState.Available -> {
                settings.setUpdateChecked(now, result.info.versionCode.toLong(), result.info.versionName)
                AppLog.log("Update", "发现新版本 ${result.info.versionName}（${result.info.versionCode}）")
            }

            UpdateState.UpToDate -> {
                settings.setUpdateChecked(now, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)
                AppLog.log("Update", "已是最新 ${BuildConfig.VERSION_NAME}")
            }

            is UpdateState.Failed -> AppLog.log("Update", "检查失败：${result.message}")
        }
        return result
    }
}
