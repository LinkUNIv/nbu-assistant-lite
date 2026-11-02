package app.nbult.lite.browser

/**
 * 访问学校页面时使用的 User-Agent。
 *
 * 前半段是设备与内核标识，后半段 `cpdaily/… wisedu/…` 是学校侧用来识别「今日校园」客户端的标记，
 * 考勤页依赖它按移动端渲染。
 *
 * 整串写死在代码里有两个麻烦：官方 App 每升一次版本号，这里就得跟着发一次 APK；
 * 而整串外置又等于把「我们自称是谁」交给远程包决定。
 * 折中做法：**固定前缀留在代码里，只让版本号段可变**，且变化必须过格式校验。
 */
internal object BrowserIdentity {

    private const val DEFAULT_VERSION = "9.9.10"

    private const val PREFIX =
        "Mozilla/5.0 (Linux; Android 15; PKR110 Build/AP3A.240617.008; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/155.0.8059.43 Mobile Safari/537.36"

    private val VERSION_PATTERN = Regex("""\d{1,3}(\.\d{1,3}){0,3}""")

    /** 官方客户端版本号；`cpdaily` 与 `wisedu` 两处必须一致，否则学校侧可能认不出来。 */
    @Volatile
    var clientVersion: String = DEFAULT_VERSION
        private set

    val ua: String get() = "$PREFIX cpdaily/$clientVersion wisedu/$clientVersion"

    /**
     * 覆盖版本号段。只接受点分数字、长度 ≤ 20；
     * 不合法就保持原值并返回 false —— 宁可继续用旧版本号，也不让 UA 变成畸形字符串。
     */
    fun setClientVersion(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.isEmpty() || v.length > 20 || !VERSION_PATTERN.matches(v)) return false
        clientVersion = v
        return true
    }
}
