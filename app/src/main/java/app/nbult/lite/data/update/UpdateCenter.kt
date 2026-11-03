package app.nbult.lite.data.update

import app.nbult.lite.BuildConfig
import app.nbult.lite.data.SettingsStore
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.flow.first

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
                // 与下载、安装完全解耦：这里落盘，后面用户取消、装失败、杀掉进程都还在。
                // 装成功之后靠版本号对上再弹「本版更新了什么」。
                settings.setPendingApp(result.info.versionCode.toLong(), result.info.notes)
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

    /**
     * 启动时调用：待装记录的版本号就是当前正在跑的版本，说明「装上了」。
     *
     * 返回需要展示给用户的更新说明，没有则返回 null。
     * 判据用 `versionCode` 而不是 `versionName` —— 后者带通道后缀
     * （`0.37.0-Preview`），而 manifest 里写的是不带后缀的 `0.37.0`，
     * 字符串比对必然对不上；版本码是两边唯一的共同语言。
     */
    suspend fun consumeInstalledNotes(settings: SettingsStore): String? {
        val pendingCode = settings.pendingAppCode.first()
        val notes = settings.pendingAppNotes.first()
        if (pendingCode <= 0L || notes.isBlank()) return null
        if (pendingCode != BuildConfig.VERSION_CODE.toLong()) return null
        settings.promotePendingApp()
        AppLog.log("Update", "已装 ${BuildConfig.VERSION_NAME}，展示更新内容 ${notes.length} 字")
        return notes
    }
}
