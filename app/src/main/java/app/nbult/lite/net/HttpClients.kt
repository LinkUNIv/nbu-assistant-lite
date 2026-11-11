package app.nbult.lite.net

import okhttp3.OkHttpClient

/**
 * 应用内请求的统一出口。
 *
 * 各模块的超时、重定向策略不同，没必要共用同一个 `OkHttpClient`；但**门禁必须共用**，
 * 所以这里只提供「已装好白名单拦截器」的 Builder。直接 `OkHttpClient.Builder()` 新建的
 * 客户端会绕过白名单 —— 新增请求时请用这里。
 */
internal object HttpClients {

    fun newBuilder(): OkHttpClient.Builder {
        val gate = HostWhitelistInterceptor()
        return OkHttpClient.Builder()
            // 应用拦截器：第一跳在建连之前就拦掉，连 DNS 都不会发出去
            .addInterceptor(gate)
            // 网络拦截器：重定向之后的每一跳也过一遍（更新包靠 302 跳到资产域名）
            .addNetworkInterceptor(gate)
    }
}
