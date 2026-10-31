package app.nbult.lite.data.update

import android.content.Context
import app.nbult.lite.BuildConfig
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 一次可用的更新。字段与 CI 生成并上传到 Release 的 `manifest.json` 一一对应。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)

sealed interface UpdateState {
    /** 已经是最新版本 */
    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Failed(val message: String) : UpdateState
}

/**
 * 应用更新检查与下载。
 *
 * 数据源是 GitHub Release 里的 `manifest.json`。
 * 用 `releases/latest/download/` 这个路径而不是 Releases API：它不消耗 API 配额、
 * 不需要 token，且永远指向最新一版 —— main 分支上的改动只要没出包就不会被读到。
 */
object AppUpdateChecker {

    private const val MANIFEST_URL =
        "https://github.com/LinkUNIv/nbu-assistant-lite/releases/latest/download/manifest.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 检查是否有比当前安装版本更新的包。 */
    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .header("Accept", "application/json")
                .build()
            val text = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }
                response.body?.string()
            } ?: return@withContext UpdateState.Failed("无法获取版本信息")

            val json = JSONObject(text)
            val remoteCode = json.optInt("versionCode", 0)
            val apkUrl = json.optString("apkUrl")
            val digest = json.optString("sha256")

            if (remoteCode <= BuildConfig.VERSION_CODE) {
                return@withContext UpdateState.UpToDate
            }
            // 缺下载地址或校验值的清单一律不认：装一个没有校验的包，
            // 不如不装 —— 这是唯一能挡住「下到半截的包」的东西
            if (apkUrl.isBlank() || digest.isBlank()) {
                return@withContext UpdateState.Failed("版本信息不完整")
            }

            UpdateState.Available(
                UpdateInfo(
                    versionCode = remoteCode,
                    versionName = json.optString("versionName", ""),
                    apkUrl = apkUrl,
                    sha256 = digest,
                    notes = json.optString("notes", "")
                )
            )
        }.getOrElse {
            AppLog.log("Update", "检查更新失败: ${it.message}")
            UpdateState.Failed(it.message ?: "网络错误")
        }
    }

    /**
     * 下载到缓存目录并校验 sha256。
     *
     * 校验不通过就删掉文件并抛错：半截的包交给系统安装器只会得到一个含义不明的失败，
     * 不如在下载阶段就把原因说清楚。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "nbu-lite-${info.versionCode}.apk")
        if (target.exists()) target.delete()

        val request = Request.Builder().url(info.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val body = response.body ?: error("下载失败：响应为空")
            val total = body.contentLength()
            var read = 0L

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }

        val actual = sha256Of(target)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            target.delete()
            error("校验失败，已取消安装")
        }
        AppLog.log("Update", "下载完成 v${info.versionName} ${target.length() / 1024}KB")
        target
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
