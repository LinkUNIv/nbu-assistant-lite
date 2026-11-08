package app.nbult.lite.data.update

import app.nbult.lite.BuildConfig

/** 列表接口里我们关心的两个字段。抽出来是为了让选择逻辑不依赖 `org.json`（本地单测里它是桩）。 */
data class ReleaseRef(val tag: String, val draft: Boolean)

/**
 * 发布通道。
 *
 * 本项目三种包同时存在：`app.nbult.lite`（release）、`.debug`、`.preview`。
 * 它们的包名与签名都不相同，**互相装不上** —— 所以「只挑同通道的版本」不是优化，
 * 是正确性：在 Preview 之后发一个 Debug，若不过滤，Preview 用户会被提示升级到一个
 * 装不进去的包，还得先下几十兆。
 *
 * 通道从 tag 读，而不是从 manifest 读：列表接口一次就带回了 tag，
 * 而 manifest 是每个 release 各一份资源，为了判断通道去拉五份清单不值当。
 */
object UpdateChannel {

    /** tag → 通道。`v0.37.7-Preview` → `preview`；无后缀视为 release。 */
    fun ofTag(tag: String): String = when {
        tag.endsWith("-Preview", ignoreCase = true) -> "preview"
        tag.endsWith("-Debug", ignoreCase = true) -> "debug"
        else -> "release"
    }

    /** 当前安装包的通道，取值与 [ofTag] 对齐（`BuildConfig.BUILD_KIND` 是 debug / preview / release）。 */
    val current: String get() = BuildConfig.BUILD_KIND

    /**
     * 返回**与当前通道一致**的最新一条的下标；没有则返回 -1。
     *
     * 列表本身按创建时间倒序，所以第一个命中就是最新。返回 -1 的含义是
     * 「本通道最近没有发布」，调用方应视为「已是最新」，而不是「检查失败」。
     */
    fun pick(releases: List<ReleaseRef>, channel: String): Int {
        releases.forEachIndexed { index, ref ->
            if (ref.draft) return@forEachIndexed
            if (ofTag(ref.tag) == channel) return index
        }
        return -1
    }
}
