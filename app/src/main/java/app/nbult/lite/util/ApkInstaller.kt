package app.nbult.lite.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把已下载的 APK 交给系统安装器。
 *
 * 只走 `ACTION_VIEW` + FileProvider，不用 `PackageInstaller.Session`：
 * 后者在普通应用上没有静默安装权限，最终仍会弹同一个安装确认界面，
 * 却多出一个 PendingIntent 结果回调与一条容易失败的写入路径。
 * 既然省不掉用户点「安装」，就用最稳的那条。
 *
 * 下载好的包必须放在 `cacheDir` 下 —— FileProvider 只暴露这一处，
 * 避免把整个 filesDir 暴露给外部应用。
 */
object ApkInstaller {

    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() == 0L) {
            AppLog.log("Update", "安装失败：文件不存在或为空")
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.log("Update", "已交给系统安装器")
            true
        }.getOrElse {
            // 最常见的原因是用户没开「安装未知应用」：系统会直接抛 ActivityNotFound
            // 或静默拒绝，这里只记录，界面负责提示
            AppLog.log("Update", "调起安装失败: ${it.message}")
            false
        }
    }
}
