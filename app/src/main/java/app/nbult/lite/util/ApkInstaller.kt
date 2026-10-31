package app.nbult.lite.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

    /**
     * 下载的包与已安装版本的签名是否不同。
     *
     * 不同的话系统必定拒绝安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，界面只显示
     * 「安装失败(-7)」和一句「与已安装应用签名不同」）。与其让用户带着一个编号去猜，
     * 不如在调起安装器之前就把原因说清楚。
     *
     * 读不到信息时返回 false：宁可让系统去判，也不要因为解析失败挡掉正常的更新。
     */
    fun signatureMismatch(context: Context, apk: File): Boolean {
        val newSigners = signaturesOf(context, apk) ?: return false
        val installed = installedSignatures(context) ?: return false
        return !newSigners.contentEqualsByContent(installed)
    }

    /** 读 APK 文件的签名。 */
    private fun signaturesOf(context: Context, apk: File): Array<ByteArray>? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)?.let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }?.toTypedArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() }?.toTypedArray()
            }
        }
    }.getOrNull()

    /** 读当前已安装包的签名。 */
    private fun installedSignatures(context: Context): Array<ByteArray>? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }?.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }?.toTypedArray()
        }
    }.getOrNull()

    /**
     * 逐个比对证书内容。
     *
     * 不能用数组的 `contentEquals`：那是比「引用」与元素引用，
     * 这里每个元素都是 ByteArray，必须逐字节比。
     */
    private fun Array<ByteArray>.contentEqualsByContent(other: Array<ByteArray>): Boolean {
        if (size != other.size) return false
        val mine = map { it.toList() }.sortedBy { it.hashCode() }
        val theirs = other.map { it.toList() }.sortedBy { it.hashCode() }
        return mine == theirs
    }

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
