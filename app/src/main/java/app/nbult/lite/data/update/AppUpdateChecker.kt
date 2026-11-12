package app.nbult.lite.data.update

import android.content.Context
import app.nbult.lite.BuildConfig
import app.nbult.lite.net.HttpClients
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
    val notes: String,
    /** 安装包字节数，只用于在弹窗里给出「下载约 23 MB」这类预告。0 表示清单没提供。 */
    val size: Long = 0L
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

    /**
     * 列最近的 Release。
     *
     * 不能用 `releases/latest`：那个端点只返回最新的**非预发布**版本，
     * 而本项目全部发布都是 prerelease，它会一直 404。
     *
     * 取 5 条而不是 1 条：三种通道共用这一个仓库，最新一条未必属于当前通道。
     * Preview 之后协作者可能发 Debug，只取 1 条就会把 Debug 包推给 Preview 用户。
     */
    private const val RELEASES_API =
        "https://api.github.com/repos/LinkUNIv/nbu-assistant-lite/releases?per_page=5"

    /**
     * 静态清单的两个来源。raw 实时但偶尔连不上，jsDelivr 稳但缓存久 —— 顺序即优先级。
     * 这两个域与资源包共用，已经在实际网络里验证过可用。
     */
    private val STATIC_BASES = listOf(
        "https://raw.githubusercontent.com/LinkUNIv/nbu-assistant-lite/packs",
        "https://cdn.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs"
    )

    /** 静态清单的失效期：7 天。 */
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    /** 安装包与续传临时文件的命名。UpdateInstaller 也按这套规则找文件。 */
    fun apkName(versionCode: Int) = "nbu-lite-$versionCode.apk"
    fun partName(versionCode: Int) = "nbu-lite-$versionCode.apk.part"

    /** 断连重试次数。三次足够覆盖偶发 reset，再多用户等不起。 */
    private const val MAX_ATTEMPTS = 3

    private val client: OkHttpClient by lazy {
        HttpClients.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 读静态清单专用的客户端：连接 8 秒。
     *
     * 旧的 15 秒 × 重试正是那 60 秒卡顿的来源。清单只有几 KB，8 秒连不上就是连不上，
     * 没必要让用户盯着「检查更新」转一分钟。
     */
    private val staticClient: OkHttpClient by lazy {
        HttpClients.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 检查是否有比当前安装版本更新的包。
     *
     * 优先读**静态清单镜像**（raw / jsDelivr 上的 `release/latest-<channel>.json`）：
     * 实测 `github.com` 与 `release-assets.githubusercontent.com` 在校园网下时通时断，
     * 一次失败要卡 60 秒（15 秒连接超时 × 重试）。静态清单只有几 KB，走的是资源包
     * 已经在用的那两条通路，1 秒内就有结果。
     *
     * 清单过期（超过 7 天没更新，说明 CI 没写成功）或读不到时，才回落到 Release API。
     */
    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        runCatching {
            val static = readStaticManifest()
            if (static != null && !isStale(static)) {
                AppLog.log("Update", "清单来自静态镜像 v${static.optInt("versionCode")}")
                return@withContext evaluateManifest(static)
            }
            val legacy = legacyCheck()
            if (legacy is UpdateState.Failed && static != null) {
                AppLog.log("Update", "API 链路失败，改用过期静态清单（v${static.optInt("versionCode")}）")
                return@withContext evaluateManifest(static)
            }
            legacy
        }.getOrElse {
            AppLog.log("Update", "检查更新失败: ${it.message}")
            UpdateState.Failed(it.message ?: "网络错误")
        }
    }

    /** 旧的 API 链路。保留作为静态清单读不到时的兜底。 */
    private fun legacyCheck(): UpdateState {
        return runCatching {
            // 第一步：找到最新 Release 里的 manifest.json 资产地址
            val releases = getText(RELEASES_API)?.let { JSONArray(it) }
                ?: return UpdateState.Failed("无法获取版本信息")
            if (releases.length() == 0) {
                return UpdateState.Failed("没有可用的版本")
            }
            // 按通道挑：装不上的包，提示了也没用
            val refs = (0 until releases.length()).map { i ->
                val obj = releases.optJSONObject(i)
                ReleaseRef(
                    tag = obj?.optString("tag_name").orEmpty(),
                    draft = obj?.optBoolean("draft", false) ?: true
                )
            }
            val pickedIndex = UpdateChannel.pick(refs, UpdateChannel.current)
            if (pickedIndex < 0) {
                AppLog.log("Update", "本通道（${UpdateChannel.current}）最近没有发布")
                return UpdateState.UpToDate
            }
            val release = releases.optJSONObject(pickedIndex)
                ?: return UpdateState.Failed("版本信息不完整")
            val assets = release.optJSONArray("assets")
                ?: return UpdateState.Failed("版本信息不完整")
            var manifestUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") == "manifest.json") {
                    manifestUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (manifestUrl.isBlank()) {
                return UpdateState.Failed("版本信息不完整")
            }

            // 第二步：读清单
            val text = getText(manifestUrl)
                ?: return UpdateState.Failed("无法获取版本信息")

            return evaluateManifest(JSONObject(text))
        }.getOrElse {
            AppLog.log("Update", "API 链路失败: ${it.message}")
            UpdateState.Failed(it.message ?: "网络错误")
        }
    }

    /**
     * 清单判定。静态镜像与 API 链路共用这一份 —— 两条路拿到的 JSON 结构相同
     * （CI 写的静态文件就是 `apk/manifest.json` 的副本），判定逻辑不该有两套。
     */
    internal fun evaluateManifest(json: JSONObject): UpdateState = evaluateManifest(
        ManifestData(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            channel = json.optString("channel"),
            apkUrl = json.optString("apkUrl"),
            sha256 = json.optString("sha256"),
            notes = json.optString("notes", ""),
            size = json.optLong("size", 0L)
        )
    )

    /**
     * 判定的**纯数据**入口。
     *
     * 拆出这一层是因为单测里 `org.json` 会撞上 Android 的桩实现（方法直接抛
     * RuntimeException），本地能过、CI 上挂 —— 与其赌类路径顺序，不如让判定不碰 JSON。
     */
    internal fun evaluateManifest(data: ManifestData): UpdateState {
        // 二次核对：tag 与清单里的 channel 应当一致，不一致说明 CI 或人工哪边出了错
        if (data.channel.isNotBlank() && !data.channel.equals(UpdateChannel.current, ignoreCase = true)) {
            AppLog.log("Update", "清单通道 ${data.channel} 与安装包 ${UpdateChannel.current} 不符，已忽略")
            return UpdateState.UpToDate
        }
        if (data.versionCode <= BuildConfig.VERSION_CODE) return UpdateState.UpToDate

        // 缺下载地址或校验值的清单一律不认：装一个没有校验的包，
        // 不如不装 —— 这是唯一能挡住「下到半截的包」的东西
        if (data.apkUrl.isBlank() || data.sha256.isBlank()) return UpdateState.Failed("版本信息不完整")

        return UpdateState.Available(
            UpdateInfo(
                versionCode = data.versionCode,
                versionName = data.versionName,
                apkUrl = data.apkUrl,
                sha256 = data.sha256,
                notes = data.notes,
                size = data.size
            )
        )
    }

    /** 清单的可比较字段。解析只在 [evaluateManifest] 的 JSON 重载里做一次。 */
    internal data class ManifestData(
        val versionCode: Int,
        val versionName: String,
        val channel: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
        val size: Long
    )

    /**
     * 读静态清单：raw 优先，jsDelivr 兜底。
     *
     * 顺序不能反 —— jsDelivr 对分支内容有长缓存（实测十几小时），新包发布后它还在发旧的；
     * raw 是实时的，只是偶尔连不上。
     */
    private fun readStaticManifest(): JSONObject? {
        val channel = UpdateChannel.current.lowercase()
        val path = "release/latest-$channel.json"
        for (base in STATIC_BASES) {
            val text = getText("$base/$path", staticClient) ?: continue
            runCatching { return JSONObject(text) }
                .onFailure { AppLog.log("Update", "静态清单不是合法 JSON：$base") }
        }
        return null
    }

    /** 清单超过 7 天没更新，说明 CI 的同步那一步没跑成功，不该再信它。 */
    private fun isStale(json: JSONObject): Boolean {
        val generatedAt = json.optLong("generatedAt", 0L)
        if (generatedAt <= 0L) return true
        val age = System.currentTimeMillis() - generatedAt
        if (age > STALE_MS) {
            AppLog.log("Update", "静态清单已过期 ${age / 86_400_000} 天")
            return true
        }
        return false
    }

    /**
     * 下载到缓存目录并校验 sha256。
     *
     * 校验不通过就删掉文件并抛错：半截的包交给系统安装器只会得到一个含义不明的失败，
     * 不如在下载阶段就把原因说清楚。
     */
    /**
     * 下载并校验 APK。
     *
     * GitHub 的下载链路会中途断（实测 `Connection reset`），而一次断开就作废整个更新 —
     * 用户看到的是「更新失败」，只能自己去网页下。所以这里做两件事：
     * **断点续传**（`Range: bytes=`）能接着上次的字节继续，**有限重试**兜住偶发断连。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return@withContext downloadOnce(context, info, onProgress)
            } catch (t: Throwable) {
                lastError = t
                val done = runCatching {
                    File(context.cacheDir, "update/nbu-lite-${info.versionCode}.apk").length()
                }.getOrDefault(0L)
                AppLog.log(
                    "Update",
                    "下载中断（第 ${attempt + 1}/$MAX_ATTEMPTS 次，已下 ${done / 1024}KB）：${t.message}"
                )
                if (attempt < MAX_ATTEMPTS - 1) kotlinx.coroutines.delay(1_000L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    /** 一次下载尝试。目标文件已存在时用 Range 续传；服务端若忽略 Range 则从头重下。 */
    private fun downloadOnce(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, apkName(info.versionCode))
        // 先写 .part：最终文件名必须**只代表完整且校验过的包**。
        // 之前直接写最终名，进程被中途杀掉就留下半截文件，重启后被当成"已下好"交给安装器，
        // 系统报「解析软件包时出现问题 (33) packageInfo is null」。
        val part = File(dir, partName(info.versionCode))
        if (target.exists()) {
            if (sha256Of(target).equals(info.sha256, ignoreCase = true)) {
                AppLog.log("Update", "已有完整包，跳过下载 v${info.versionName}")
                markVerified(target, info)
                return target
            }
            target.delete()
        }
        val existing = if (part.exists()) part.length() else 0L

        val builder = Request.Builder().url(info.apkUrl)
        if (existing > 0L) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val body = response.body ?: error("下载失败：响应为空")
            val resumed = response.code == 206
            if (!resumed && existing > 0L) part.delete()   // 服务端不支持续传
            val base = if (resumed) existing else 0L
            val total = if (resumed) base + body.contentLength() else body.contentLength()
            var read = base

            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }

        val actual = sha256Of(part)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            // 续传拼出的文件可能是坏的：丢掉重来一次，而不是把坏包交出去
            part.delete()
            error("校验失败，已删除重下")
        }
        // 校验通过才落成最终名字。rename 失败就当这次没下成，不留半成品
        if (!part.renameTo(target)) {
            part.delete()
            error("无法写入安装包")
        }
        markVerified(target, info)
        AppLog.log("Update", "下载完成 v${info.versionName} ${target.length() / 1024}KB")
        return target
    }

    /**
     * 写「已校验」标记。
     *
     * 只有带这个标记的文件才会被认作可安装 —— 光看文件存在与大小是不够的：
     * 半截包同样「存在且非空」。标记里记下大小，读取时能便宜地挡掉明显不符的文件。
     */
    /**
     * 文件是否真的可用：大小与标记一致，且 sha256 与标记里记的一致。
     *
     * 没有标记的文件一律不认 —— 下载只有在**校验通过之后**才写标记，
     * 所以"有标记"本身就代表"这份包是完整且经过校验的"。
     */
    internal fun isVerifiedApk(apk: File): Boolean {
        if (!apk.exists() || apk.length() <= 0L) return false
        val mark = File(apk.parentFile, apk.name + ".ok")
        if (!mark.exists()) return false
        val parts = runCatching { mark.readText().split('|') }.getOrNull() ?: return false
        val sha = parts.getOrNull(1).orEmpty()
        if (sha.length != 64) return false
        if (parts.getOrNull(2)?.toLongOrNull() != apk.length()) return false
        return runCatching { sha256Of(apk) }.getOrNull()?.equals(sha, ignoreCase = true) == true
    }

    private fun markVerified(apk: File, info: UpdateInfo) {
        runCatching {
            File(apk.parentFile, apk.name + ".ok")
                .writeText("${info.versionCode}|${info.sha256}|${apk.length()}")
        }.onFailure { AppLog.log("Update", "写校验标记失败：${it.message}") }
    }

    /** 取一个 URL 的文本内容；非 2xx 返回 null，由调用方决定文案。 */
    /**
     * 取文本。失败必须留下**能定位的原因** —— 之前这里静默返回 null，
     * 用户只看到「无法获取版本信息」，分不清是超时、403 还是 DNS 挂了。
     */
    private fun getText(url: String, http: OkHttpClient = client): String? {
        val host = url.substringAfter("//").substringBefore("/")
        val started = System.currentTimeMillis()
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                // 显式 UA：GitHub 对没有标识的请求更容易限流
                .header("User-Agent", "nbu-lite/${app.nbult.lite.BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    AppLog.log("Update", "请求失败 http=${response.code} @$host 耗时${System.currentTimeMillis() - started}ms")
                    null
                }
            }
        }.onFailure {
            AppLog.log(
                "Update",
                "请求异常 ${it.javaClass.simpleName} @$host 耗时${System.currentTimeMillis() - started}ms 原因=${it.message}"
            )
        }.getOrNull()
    }

    internal fun sha256Of(file: File): String = app.nbult.lite.util.Digests.sha256(file)
}
