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

    // 顺序有讲究：GitHub raw 与 App 更新走的是同一家、同一个仓库，网络通的时候它最快；
    // jsDelivr 在国内通常更稳，但它按分支缓存（最长十几小时），刚发的包可能还没同步。
    // 所以先试 raw，失败再退镜像 —— 连接超时压到 6 秒，坏掉的那条不会让检查卡半分钟
    private val BASES = listOf(
        "https://raw.githubusercontent.com/LinkUNIv/nbu-assistant-lite/packs",
        "https://cdn.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 检查并应用。任何异常都吞掉 —— 资源更新的失败不该影响启动。 */
    suspend fun checkAndApply(context: Context): PackCheckResult = withContext(Dispatchers.IO) {
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
                    AppLog.log(
                        "Pack",
                        "跳过 v${manifest.version}：要求 App ${manifest.minAppVersion}，" +
                            "当前 ${BuildConfig.VERSION_CODE}"
                    )
                    PackCheckResult.Blocked(manifest.minAppVersion)
                }

                else -> applyNew(context, base, manifest, now)
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
        for (file in manifest.files) {
            val bytes = getBytes("$base/${manifest.version}/${file.path}")
                ?: return reject(context, manifest, "下载失败 ${file.path}")
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

        ResourcePackStore.markChecked(context, now)
        return if (ResourcePackStore.apply(context, manifest, payload, now)) {
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

    /** 逐个源试，第一个能解析的清单为准。 */
    private fun fetchManifest(): Pair<String, PackManifest>? {
        for (base in BASES) {
            val text = getText("$base/latest.json") ?: continue
            val manifest = PackManifest.parse(text) ?: continue
            return base to manifest
        }
        return null
    }

    private fun getText(url: String): String? = getBytes(url)?.let { String(it, Charsets.UTF_8) }

    private fun getBytes(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val declared = response.body?.contentLength() ?: -1L
            if (declared > MAX_FILE_BYTES) return null
            response.body?.bytes()
        }
    }.getOrNull()
}
