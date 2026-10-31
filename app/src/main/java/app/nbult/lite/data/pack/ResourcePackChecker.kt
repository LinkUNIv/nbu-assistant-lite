package app.nbult.lite.data.pack

import android.content.Context
import app.nbult.lite.BuildConfig
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 资源包检查与应用。与 [app.nbult.lite.data.update.AppUpdateChecker] **互不调用**。
 *
 * 两者共用基础设施（OkHttp、sha256、版本比较），但不共享状态机：
 *
 * - App 版本：低频、用户主动点、装的时候要跳系统安装器
 * - 资源包：高频、启动后自动、静默生效
 *
 * 合成一个入口的后果很具体 —— 用户点了「检查更新」，结果只是资源变了、App 没变，
 * 会以为更新坏了；或者一个 200KB 的资源包让他以为要重装 2.8MB。
 *
 * 下载源**写死在代码里**，不接受远程包指定新源。两个地址都是同一个仓库：
 * `raw.githubusercontent.com` 直连，`jsDelivr` 作为国内更稳的镜像。
 */
object ResourcePackChecker {

    /** 单个文件上限。资源包是几十 KB 级的 JSON，超过这个量级说明文件不对。 */
    private const val MAX_FILE_BYTES = 512 * 1024

    /**
     * 清单地址走 API，**不走 CDN**。
     *
     * 这里踩过一次：jsDelivr 对分支内容做长缓存（实测十几小时），
     * 新包 13:16 就发布了，14:45 检查仍拿到 11:35 那份清单，
     * 版本号与本地相同 → 界面显示「已是最新」。文件带版本号可以缓存，
     * 但「现在最新是第几版」这件事必须实时。
     */
    private const val API_MANIFEST =
        "https://api.github.com/repos/LinkUNIv/nbu-assistant-lite/contents/latest.json?ref=packs"

    /**
     * 文件来源。都是同一仓库的不同边缘节点，路径里带版本号（`<version>/...`），
     * 内容不可变，缓存反而是好事。
     *
     * 一次检查里，某个源命中后剩余文件优先用它（见 [downloadFile]），
     * 不逐个文件从头试起。
     */
    private val FILE_BASES = listOf(
        "https://raw.githubusercontent.com/LinkUNIv/nbu-assistant-lite/packs",
        "https://fastly.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs",
        "https://gcore.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs",
        "https://cdn.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 检查并应用。任何异常都吞掉 —— 资源更新的失败不该影响启动。 */
    suspend fun checkAndApply(
        context: Context,
        settings: app.nbult.lite.data.SettingsStore
    ): PackCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val hit = fetchManifest() ?: return@runCatching fail(now, "清单不可达或格式错误")
            val (base, manifest) = hit
            val local = ResourcePackStore.state(context)

            when {
                // 只升不降：比本地旧的包一律拒绝，防止旧包覆盖新数据
                manifest.version <= local.version -> {
                    ResourcePackStore.markChecked(context, now)
                    AppLog.log("Pack", "已是最新 远端=${manifest.version} 本地=${local.version}")
                    PackCheckResult.UpToDate
                }

                local.failedVersion == manifest.version -> {
                    AppLog.log("Pack", "跳过 v${manifest.version}：此前应用失败过")
                    PackCheckResult.Skipped
                }

                // 资源不向下兼容：整体跳过，不做部分应用。只记日志，不串到版本更新界面
                manifest.minAppVersion > BuildConfig.VERSION_CODE -> {
                    ResourcePackStore.markChecked(context, now)
                    // 记下「有更新但没生效」，关于页据此亮红点
                    settings.setPackPending(manifest.version)
                    AppLog.log(
                        "Pack",
                        "跳过 v${manifest.version}：要求 App ${manifest.minAppVersion}，" +
                            "当前 ${BuildConfig.VERSION_CODE}"
                    )
                    PackCheckResult.Blocked(manifest.minAppVersion)
                }

                else -> {
                // 先记 pending：哪怕后面下载失败，用户也该知道「有更新」
                settings.setPackPending(manifest.version)
                applyNew(context, base, manifest, now)
            }
            }
        }.getOrElse {
            AppLog.log("Pack", "检查异常：${it.message}")
            PackCheckResult.Failed(it.message ?: "检查异常")
        }
    }

    private fun applyNew(
        context: Context,
        base: String,
        manifest: PackManifest,
        now: Long
    ): PackCheckResult {
        val payload = HashMap<String, String>(manifest.files.size)
        var preferred = FILE_BASES.indexOf(base).coerceAtLeast(0)
        for (file in manifest.files) {
            val hit = downloadFile(manifest.version, file.path, preferred)
                ?: return reject(context, manifest, "下载失败 ${file.path}")
            val (bytes, usedIndex) = hit
            preferred = usedIndex
            if (bytes.size > MAX_FILE_BYTES) {
                return reject(context, manifest, "${file.path} 超出 ${MAX_FILE_BYTES} 字节")
            }
            if (ResourcePackStore.sha256(bytes) != file.sha256) {
                return reject(context, manifest, "${file.path} 校验不符")
            }
            payload[file.path] = String(bytes, Charsets.UTF_8)
        }

        // 入口链接单独设卡：这是唯一能把用户带去站外的文件
        payload["portal/entries.json"]?.let { text ->
            PackGuards.validatePortal(context, text)?.let { reason ->
                PackGuards.logReject(manifest.version, reason)
                return reject(context, manifest, reason)
            }
        }

        // 资讯配置与入口链接一样要过闸门：它能决定应用去请求哪些地址
        payload["news/sources.json"]?.let { text ->
            PackGuards.validateNewsSources(context, text)?.let { reason ->
                PackGuards.logReject(manifest.version, reason)
                return reject(context, manifest, reason)
            }
        }

        ResourcePackStore.markChecked(context, now)
        return if (ResourcePackStore.apply(context, manifest, payload, now)) {
            // 配置换了就要让资讯那边丢掉缓存，否则要等下次冷启动才生效
            app.nbult.lite.data.news.NewsSources.invalidate()
            PackCheckResult.Applied(manifest.version)
        } else {
            reject(context, manifest, "写入失败")
        }
    }

    /** 内容不合格：记下版本，同一个包不再重试。 */
    private fun reject(context: Context, manifest: PackManifest, reason: String): PackCheckResult {
        ResourcePackStore.markFailed(context, manifest.version, reason)
        return PackCheckResult.Failed(reason)
    }

    /** 网络类失败：不记版本，下次启动还可以再试。 */
    private fun fail(now: Long, reason: String): PackCheckResult {
        AppLog.log("Pack", "失败：$reason")
        return PackCheckResult.Failed(reason)
    }

    /**
     * 取清单：先问 API（实时），再退镜像（可能延迟）。
     *
     * 返回的 String 只是「文件该从哪个源起试」的提示，不参与版本判断 ——
     * 版本永远以清单里的 `version` 为准。
     */
    private fun fetchManifest(): Pair<String, PackManifest>? {
        getText(API_MANIFEST, raw = true)?.let { text ->
            PackManifest.parse(text)?.let { return FILE_BASES.first() to it }
        }
        AppLog.log("Pack", "API 清单不可用，退回镜像（可能延迟）")
        for (base in FILE_BASES) {
            val text = getText("$base/latest.json") ?: continue
            PackManifest.parse(text)?.let { return base to it }
        }
        return null
    }

    /** 下一个文件从 [preferred] 起试，失败再按顺序换源。 */
    private fun downloadFile(version: Long, path: String, preferred: Int): Pair<ByteArray, Int>? {
        val order = listOf(preferred) + FILE_BASES.indices.filter { it != preferred }
        for (index in order) {
            val bytes = getBytes("${FILE_BASES[index]}/$version/$path") ?: continue
            return bytes to index
        }
        return null
    }

    private fun getText(url: String, raw: Boolean = false): String? =
        getBytes(url, raw)?.let { String(it, Charsets.UTF_8) }

    private fun getBytes(url: String, raw: Boolean = false): ByteArray? = runCatching {
        val builder = Request.Builder().url(url)
        // API 默认返回 JSON 包装，加这个头才拿到文件原文
        if (raw) builder.header("Accept", "application/vnd.github.raw")
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val declared = response.body?.contentLength() ?: -1L
            if (declared > MAX_FILE_BYTES) return null
            response.body?.bytes()
        }
    }.getOrNull()
}
