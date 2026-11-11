package app.nbult.lite.net

import app.nbult.lite.util.AppLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 应用内所有 OkHttp 请求的统一门禁。
 *
 * 「不采集、不外发」写在 README 上是承诺，在此之前它只是**代码约定** —— 任何一处
 * 新增的请求都可能把数据带到别处。这个拦截器把它变成可执行的边界：不在白名单里的
 * 主机，请求在建立连接前就失败。
 *
 * 用**网络拦截器**而不是应用拦截器：应用拦截器只看到最初那一条，重定向之后的每一跳
 * 都绕过去了。更新包正是靠 302 从 `github.com` 跳到 `objects.githubusercontent.com`，
 * 只查第一跳等于没查。
 */
internal class HostWhitelistInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host.lowercase()
        val path = request.url.encodedPath
        if (!HostWhitelist.allows(host, path)) {
            // 记主机与路径便于定位是谁在发请求，但不记查询串（可能带 token）
            AppLog.log("Net", "拒绝白名单外请求 $host$path")
            throw IOException("host not allowed: $host")
        }
        return chain.proceed(request)
    }
}

/**
 * 白名单判定本身放在这里，**不依赖 OkHttp**，便于单测。
 *
 * 两类最容易写错的地方：
 * 1. 后缀匹配不带前导点，`endsWith("nbu.edu.cn")` 会把 `evailnbu.edu.cn` 也放过去；
 * 2. 放行 `github.com` 整域 —— 那等于给整个 GitHub 开口子，所以限定到本仓库路径。
 */
internal object HostWhitelist {

    private const val SCHOOL = "nbu.edu.cn"

    /** 本仓库在 GitHub 上的路径前缀，`github.com` 只放行这一段。 */
    private const val REPO_PREFIX = "/LinkUNIv/nbu-assistant-lite/"

    /** 校外只放行这几个：更新检查与资产下载、资源包主源与三个镜像。 */
    private val EXTERNAL = setOf(
        "api.github.com",
        "raw.githubusercontent.com",
        // Release 资产的真实下载地址：github.com 会 302 到这里
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "fastly.jsdelivr.net",
        "gcore.jsdelivr.net",
        "cdn.jsdelivr.net"
    )

    fun allows(rawHost: String, path: String = "/"): Boolean {
        val host = rawHost.lowercase().removeSuffix(".")
        if (host.isEmpty()) return false
        if (host == SCHOOL || host.endsWith(".$SCHOOL")) return true
        if (host == "github.com") return path.startsWith(REPO_PREFIX)
        return host in EXTERNAL
    }
}
