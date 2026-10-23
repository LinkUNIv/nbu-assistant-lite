package app.nbult.lite.util

import app.nbult.lite.BuildConfig

/**
 * 对外统一的 User-Agent。
 *
 * 此前 5 处请求各自硬编码 `NBUAssistantLite/0.1` ~ `/0.4`，与实际发布版本完全脱节：
 * 站点侧日志里既看不出真实版本，出问题时也无法判断是哪个版本在抓。
 * 这里收成一处，跟随 `BuildConfig.VERSION_NAME`。
 */
object UserAgent {

    private val version: String = BuildConfig.VERSION_NAME
        .removeSuffix("-debug")
        .removeSuffix("-bench")

    /** 标准 UA：明确标识自己是谁、什么版本。 */
    val default: String = "NBUAssistantLite/$version (Android)"

    /**
     * 类浏览器 UA。
     *
     * 官网部分页面（如校历 `xl.htm`）对不含浏览器前缀的 UA 会返回空内容，
     * 因此这一条保留 `Mozilla/5.0` 前缀，但仍在末尾标明真实身份与版本。
     */
    val browserLike: String = "Mozilla/5.0 (Linux; Android) NbuLite/$version"
}
