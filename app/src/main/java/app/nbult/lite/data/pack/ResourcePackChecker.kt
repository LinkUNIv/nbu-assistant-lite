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
 * App 版本低频、要跳安装器；资源包高频、静默生效。合成一个入口会让「检查更新」这句话
 * 有两种含义，用户无法判断点完是等下载还是等安装。
 *
 * 下载源**写死在代码里**，不接受远程包指定新源。
 *
 * 失败分两类，这是 0.35.1 的关键修正：
 *
 * - **网络类**（源不可达、连接中断、非 2xx）→ 不记版本，下次还能再试
 * - **内容类**（sha 不符、闸门拒绝、写入失败）→ 记下版本与原因，6 小时后才重试
 *
 * 0.34.x 把两者混在一起，于是 13:16 那版因为一次 CDN 抖动被永久拉黑 ——
 * 界面只剩「已跳过」，用户既拿不到包，也查不到原因。
 */
object ResourcePackChecker {

    /** 单个文件上限。资源包是几十 KB 级的 JSON，超过这个量级说明文件不对。 */
    private const val MAX_FILE_BYTES = 512 * 1024

    /** 内容不合格的版本，隔多久允许再试。 */
    private const val RETRY_AFTER_MS = 6L * 60 * 60 * 1000

    /** 自动检查的最小间隔。启动很频繁，不必每次都打一次 API。 */
    private const val MIN_INTERVAL_MS = 60L * 60 * 1000

    /**
     * 清单地址走 API，**不走 CDN**。
     *
     * 踩过一次：jsDelivr 对分支内容做长缓存（实测十几小时），新包 13:16 发布、
     * 14:45 检查仍拿到 11:35 那份清单，版本号与本地相同 → 界面显示「已是最新」。
     * 文件带版本号可以缓存，但「现在最新是第几版」必须实时。
     */
    private const val API_MANIFEST =
        "https://api.github.com/repos/LinkUNIv/nbu-assistant-lite/contents/latest.json?ref=packs"

    /**
     * 文件来源。都是同一仓库的不同边缘节点，路径里带版本号（`<version>/...`），
     * 内容不可变，缓存反而是好事。
     *
     * 一次检查里，某个源命中后剩余文件优先用它（见 [download]），不逐个从头试。
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

    /**
     * 检查并应用。
     *
     * @param force 手动重试：无视间隔限制与「此前失败」标记。界面上的「重试」用它。
     */
    suspend fun checkAndApply(
        context: Context,
        settings: app.nbult.lite.data.SettingsStore,
        force: Boolean = false
    ): PackCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val local = ResourcePackStore.state(context)

            if (!force && now - local.lastAttemptAt < MIN_INTERVAL_MS) {
                AppLog.log("Pack", "距上次检查不足 1 小时，本次跳过")
                return@runCatching PackCheckResult.Skipped
            }
            // 每次尝试都记时间：界面上的「上次检查」必须反映「试过」，而不是「成功过」
            ResourcePackStore.markAttempt(context, now)

            val manifestResult = fetchManifest()
            if (manifestResult is ManifestResult.Err) {
                return@runCatching networkFail(context, manifestResult.detail)
            }
            val manifest = (manifestResult as ManifestResult.Ok).manifest
            val preferredStart = manifestResult.preferred

            // 只升不降：比本地旧的包一律拒绝，防止旧包覆盖新数据
            if (manifest.version <= local.version) {
                ResourcePackStore.markChecked(context, now)
                AppLog.log("Pack", "已是最新 远端=${manifest.version} 本地=${local.version}")
                return@runCatching PackCheckResult.UpToDate
            }

            // 资源不向下兼容：整体跳过，不做部分应用。只记日志，不串到版本更新界面
            if (manifest.minAppVersion > BuildConfig.VERSION_CODE) {
                ResourcePackStore.markChecked(context, now)
                settings.setPackPending(manifest.version)
                AppLog.log(
                    "Pack",
                    "跳过 v${manifest.version}：要求 App ${manifest.minAppVersion}，" +
                        "当前 ${BuildConfig.VERSION_CODE}"
                )
                return@runCatching PackCheckResult.Blocked(manifest.minAppVersion)
            }

            // 此前判定为「内容不合格」的版本：等够时间再试；手动重试则立刻放行
            if (local.failedVersion == manifest.version && !force) {
                val waited = now - local.failedAt
                if (waited < RETRY_AFTER_MS) {
                    AppLog.log(
                        "Pack",
                        "跳过 v${manifest.version}：${waited / 60_000} 分钟前失败过" +
                            "（${local.failedReason}），未到重试时间"
                    )
                    return@runCatching PackCheckResult.Skipped
                }
                AppLog.log(
                    "Pack",
                    "失败已超过 ${RETRY_AFTER_MS / 3_600_000} 小时，重试 v${manifest.version}"
                )
                ResourcePackStore.clearFailure(context)
            }
            if (force) ResourcePackStore.clearFailure(context)

            // 先记 pending：只要发现了新包用户就该知道，哪怕后面没下成
            settings.setPackPending(manifest.version)
            applyNew(context, manifest, preferredStart, now)
        }.getOrElse {
            // 走到这里说明是代码层面的意外，按网络类处理，不拉黑版本
            ResourcePackStore.recordError(context, "检查异常：${it.javaClass.simpleName} ${it.message}")
            PackCheckResult.Failed(it.message ?: "检查异常", retryable = true)
        }
    }

    private fun applyNew(
        context: Context,
        manifest: PackManifest,
        preferredStart: Int,
        now: Long
    ): PackCheckResult {
        val payload = HashMap<String, String>(manifest.files.size)
        var preferred = preferredStart

        for (file in manifest.files) {
            when (val result = download(manifest.version, file.path, preferred)) {
                is DownloadResult.Err -> return networkFail(context, result.detail)

                is DownloadResult.Ok -> {
                    preferred = result.index
                    if (result.bytes.size > MAX_FILE_BYTES) {
                        return contentFail(context, manifest, "${file.path} 超出 ${MAX_FILE_BYTES} 字节")
                    }
                    if (ResourcePackStore.sha256(result.bytes) != file.sha256) {
                        return contentFail(context, manifest, "${file.path} 校验不符")
                    }
                    payload[file.path] = String(result.bytes, Charsets.UTF_8)
                }
            }
        }

        // 入口链接单独设卡：这是唯一能把用户带去站外的文件
        payload["portal/entries.json"]?.let { text ->
            PackGuards.validatePortal(context, text)?.let { reason ->
                return contentFail(context, manifest, reason)
            }
        }

        // 资讯配置与入口链接一样要过闸门：它能决定应用去请求哪些地址
        payload["news/sources.json"]?.let { text ->
            PackGuards.validateNewsSources(context, text)?.let { reason ->
                return contentFail(context, manifest, reason)
            }
        }

        if (!ResourcePackStore.apply(context, manifest, payload, now)) {
            return contentFail(context, manifest, "写入失败")
        }
        // 只有真正落地了才算「检查出结论」
        ResourcePackStore.markChecked(context, now)
        app.nbult.lite.data.news.NewsSources.invalidate()
        AppLog.log("Pack", "已应用 v${manifest.version}")
        return PackCheckResult.Applied(manifest.version)
    }

    /** 网络类失败：不记版本，只留原因。 */
    private fun networkFail(context: Context, detail: String): PackCheckResult {
        ResourcePackStore.recordError(context, detail)
        return PackCheckResult.Failed(detail, retryable = true)
    }

    /** 内容不合格：记下版本与原因，6 小时后才再试。 */
    private fun contentFail(
        context: Context,
        manifest: PackManifest,
        reason: String
    ): PackCheckResult {
        PackGuards.logReject(manifest.version, reason)
        ResourcePackStore.markFailed(context, manifest.version, reason, System.currentTimeMillis())
        return PackCheckResult.Failed(reason, retryable = false)
    }

    // ---- 取清单 ----

    private sealed interface ManifestResult {
        data class Ok(val manifest: PackManifest, val preferred: Int) : ManifestResult
        data class Err(val detail: String) : ManifestResult
    }

    /**
     * 取清单：先问 API（实时），再退镜像（可能延迟）。
     *
     * 返回的 `preferred` 只是「文件该从哪个源起试」，不参与版本判断 ——
     * 版本永远以清单里的 `version` 为准。
     */
    private fun fetchManifest(): ManifestResult {
        val fromApi = fetch(API_MANIFEST, raw = true)
        if (fromApi is ByteResult.Ok) {
            PackManifest.parse(String(fromApi.bytes, Charsets.UTF_8))?.let {
                return ManifestResult.Ok(it, 0)
            }
        }
        var lastDetail = (fromApi as? ByteResult.Err)?.detail ?: "API 清单格式错误"

        for ((index, base) in FILE_BASES.withIndex()) {
            when (val result = fetch("$base/latest.json")) {
                is ByteResult.Ok -> {
                    PackManifest.parse(String(result.bytes, Charsets.UTF_8))?.let {
                        return ManifestResult.Ok(it, index)
                    }
                    lastDetail = "清单格式错误 @${hostOf(base)}"
                }

                is ByteResult.Err -> lastDetail = result.detail
            }
        }
        return ManifestResult.Err("清单不可达：$lastDetail")
    }

    // ---- 下载 ----

    private sealed interface DownloadResult {
        data class Ok(val bytes: ByteArray, val index: Int) : DownloadResult
        data class Err(val detail: String) : DownloadResult
    }

    /** 从 [preferred] 起试，失败按顺序换源；全部失败才返回错误，带上最后一次的状态。 */
    private fun download(version: Long, path: String, preferred: Int): DownloadResult {
        val order = listOf(preferred) + FILE_BASES.indices.filter { it != preferred }
        var lastDetail = ""
        for (index in order) {
            when (val result = fetch("${FILE_BASES[index]}/$version/$path")) {
                is ByteResult.Ok -> return DownloadResult.Ok(result.bytes, index)
                is ByteResult.Err -> lastDetail = "${result.detail} [$path]"
            }
        }
        return DownloadResult.Err("下载失败：$lastDetail")
    }

    private sealed interface ByteResult {
        data class Ok(val bytes: ByteArray) : ByteResult
        data class Err(val detail: String) : ByteResult
    }

    /**
     * 取一个 URL。失败时把「哪个源、HTTP 码或异常类型」带回来 ——
     * 上次就是因为丢掉了这段信息，只能猜是下载还是写盘。
     */
    private fun fetch(url: String, raw: Boolean = false): ByteResult {
        val host = hostOf(url)
        return runCatching {
            val builder = Request.Builder().url(url)
            // API 默认返回 JSON 包装，加这个头才拿到文件原文
            if (raw) builder.header("Accept", "application/vnd.github.raw")
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return ByteResult.Err("HTTP ${response.code} @$host")
                }
                val declared = response.body?.contentLength() ?: -1L
                if (declared > MAX_FILE_BYTES) {
                    return ByteResult.Err("文件过大 ${declared}B @$host")
                }
                val bytes = response.body?.bytes() ?: return ByteResult.Err("响应为空 @$host")
                ByteResult.Ok(bytes)
            }
        }.getOrElse {
            ByteResult.Err("${it.javaClass.simpleName} @$host")
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/')
}
