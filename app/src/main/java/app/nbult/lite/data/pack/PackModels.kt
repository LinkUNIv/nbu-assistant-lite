package app.nbult.lite.data.pack

import org.json.JSONObject

/** 资源包里一个文件的声明。`sha256` 是该文件内容的哈希，落地前逐个核对。 */
data class PackFile(val path: String, val sha256: String)

/**
 * 资源包清单。
 *
 * `version` 是 `yyyyMMddHHmm` 的 Long（如 `202610311130`），**只用于比较大小**。
 * `updatedAt` 与 `notes` 只用于展示，不许拿来比大小 —— 服务端一换时区，
 * 用它比就会出现「版本倒退」这种查不出来的怪事。
 *
 * `minAppVersion` 对应 `versionCode`：包里如果用了旧版 App 读不懂的结构，
 * 就把这个值抬上去，旧版会整体跳过，而不是应用一半。
 */
data class PackManifest(
    val version: Long,
    val updatedAt: String,
    val notes: String,
    val minAppVersion: Int,
    val files: List<PackFile>
) {

    companion object {

        /**
         * 解析清单。任何一个字段不合格就整体作废 —— 资源包的失败必须是响亮的，
         * 不能「能读多少算多少」，那样坏包会以部分生效的方式留在机器上。
         */
        fun parse(text: String): PackManifest? = runCatching {
            val root = JSONObject(text)
            val array = root.optJSONArray("files") ?: return null
            val files = ArrayList<PackFile>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: return null
                val path = safePath(o.optString("path")) ?: return null
                val sha = o.optString("sha256").lowercase()
                if (!sha.matches(HEX64)) return null
                files.add(PackFile(path, sha))
            }
            if (files.isEmpty()) return null

            val version = root.optLong("version", 0L)
            if (version <= 0L) return null

            PackManifest(
                version = version,
                updatedAt = root.optString("updatedAt"),
                notes = root.optString("notes"),
                minAppVersion = root.optInt("minAppVersion", 0),
                files = files
            )
        }.getOrNull()

        /**
         * 只接受「相对、且不含 `..`」的路径。
         *
         * 远程包能往哪里写文件，必须在这里卡死：一个 `../../` 就能写到应用私有目录之外，
         * 而这里面没有任何东西值得为「灵活」冒这个险。
         */
        private fun safePath(raw: String): String? {
            val path = raw.trim().replace('\\', '/').removePrefix("/")
            if (path.isEmpty()) return null
            if (path.contains("..")) return null
            if (path.startsWith("/")) return null
            return path
        }

        private val HEX64 = Regex("[0-9a-f]{64}")
    }
}

/**
 * 当前生效的资源状态。字段都写在 `filesDir/pack/state.json`，与包目录放在一起 ——
 * 「哪个版本生效」本身就是文件系统的事实，没必要再拆到 DataStore 里。
 */
data class PackState(
    val version: Long = 0L,
    /** `builtin` 表示仍在用 APK 内置的那份，`remote` 表示已应用下载的包。 */
    val source: String = "builtin",
    val appliedAt: Long = 0L,
    val lastCheckAt: Long = 0L,
    val notes: String = "",
    /** 应用失败过的版本，同版本不再重试，避免每次启动都卡一次。 */
    val failedVersion: Long = 0L
) {
    val isRemote: Boolean get() = version > 0L && source == "remote"
}

/** 检索结果。UI 与日志都从这里取值，避免各自拼字符串。 */
sealed interface PackCheckResult {
    /** 远端不比本地新，什么都不做。 */
    data object UpToDate : PackCheckResult

    /** 已应用新包。 */
    data class Applied(val version: Long) : PackCheckResult

    /** 包要求的 App 版本高于当前，整体跳过；只记日志，不串到版本更新界面。 */
    data class Blocked(val minAppVersion: Int) : PackCheckResult

    /** 该版本此前失败过，本次不再尝试。 */
    data object Skipped : PackCheckResult

    data class Failed(val reason: String) : PackCheckResult
}
