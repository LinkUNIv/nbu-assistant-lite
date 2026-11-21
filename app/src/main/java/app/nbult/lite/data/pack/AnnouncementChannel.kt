package app.nbult.lite.data.pack

import android.content.Context
import app.nbult.lite.BuildConfig
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 公告的**独立通道**。
 *
 * 为什么要把公告从资源包里拆出来：
 * 资源包有整包级 `minAppVersion`，一旦为了某个学校抬高门槛，**公告会跟着对旧版失效**；
 * 反过来，公告想用新字段（如 `pinOrder`）又不敢用，怕旧版误读。
 * 拆开之后两者互不牵连：公告有自己的一份文件、自己的版本号，旧版干脆不请求它。
 *
 * 数据位置：`packs` 分支的 `release/announcements.json`
 * ```json
 * { "version": 202611220200, "announcements": [ ... ] }
 * ```
 * 版本号只升不降，与本地缓存比较后才应用，避免 CDN 回源到旧副本时把公告退回去。
 *
 * 回退顺序：独立通道缓存 → 资源包里的旧副本 → 空。最后一级保证「拆通道」这一步
 * 不会让已经在用的公告凭空消失。
 */
object AnnouncementChannel {

    private const val CACHE_FILE = "announcements-channel.json"
    private const val PACK_COPY = "announcements.json"

    private val FILE_BASES = listOf(
        "https://raw.githubusercontent.com/LinkUNIv/nbu-assistant-lite/packs",
        "https://fastly.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs",
        "https://gcore.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs",
        "https://cdn.jsdelivr.net/gh/LinkUNIv/nbu-assistant-lite@packs"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val _items = MutableStateFlow<List<Announcement>>(emptyList())
    val items: StateFlow<List<Announcement>> = _items.asStateFlow()

    /** 启动时先读缓存（本地即可，不等网络），保证首屏就有公告。 */
    fun loadCached(context: Context) {
        _items.value = readCached(context) ?: readPackCopy(context)
    }

    /** 拉取远端。失败什么都不做——保缓存，不让「今天连不上」变成「公告清空」。 */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        var remote: Pair<Long, String>? = null
        for (base in FILE_BASES) {
            val text = fetch("$base/release/announcements.json") ?: continue
            val version = runCatching { JSONObject(text).optLong("version") }.getOrDefault(0L)
            if (version <= 0L) continue
            remote = version to text
            break
        }
        val (version, body) = remote ?: run {
            AppLog.log("Announce", "公告通道：取不到远端，沿用缓存")
            return@withContext
        }
        val cachedVersion = readCacheVersion(context)
        if (version <= cachedVersion) {
            AppLog.log("Announce", "公告通道：远端 v$version 不比本地 v$cachedVersion 新，跳过")
            return@withContext
        }
        // 解析失败就不落盘：宁可继续用旧公告，也不要把空列表写进去
        val parsed = Announcements.parse(body, BuildConfig.VERSION_CODE, now)
        if (parsed.isEmpty()) {
            AppLog.log("Announce", "公告通道：远端 v$version 解析为空，保留本地")
            return@withContext
        }
        File(context.filesDir, CACHE_FILE).writeText(body)
        _items.value = parsed
        AppLog.log("Announce", "公告通道：已更新到 v$version，共 ${parsed.size} 条")
    }

    private fun readCached(context: Context): List<Announcement>? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val now = System.currentTimeMillis() / 1000
        return Announcements.parse(text, BuildConfig.VERSION_CODE, now).ifEmpty { null }
    }

    private fun readCacheVersion(context: Context): Long {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return 0L
        return runCatching {
            JSONObject(file.readText()).optLong("version")
        }.getOrDefault(0L)
    }

    /** 拆通道前的旧路径：公告还放在资源包里。保留一个版本周期用于平滑过渡。 */
    private fun readPackCopy(context: Context): List<Announcement> {
        val text = ResourcePackStore.readText(context, PACK_COPY) ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        return Announcements.parse(text, BuildConfig.VERSION_CODE, now)
    }

    private fun fetch(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "nbu-lite").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrElse {
        AppLog.log("Announce", "公告通道：$url 失败（${it.javaClass.simpleName}）")
        null
    }
}
