package app.nbult.lite.data.pack

import android.content.Context
import app.nbult.lite.util.AppLog
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 资源包的落地与读取。
 *
 * 目录结构（都在 `filesDir` 下，不进 APK）：
 *
 * ```
 * pack/
 *   state.json              当前生效的版本与检查时间
 *   202610311130/           一个版本一个目录，互不覆盖
 *     calendar/nbu_calendar.json
 *     portal/entries.json
 * ```
 *
 * 读文件一律走 [readText]：先看有没有生效的远程包，没有再退回 `assets/`。
 * 内置的那份永远能跑，这是底线 —— 远程包坏掉时应用必须还能正常启动。
 */
object ResourcePackStore {

    private const val ROOT = "pack"
    private const val STATE = "state.json"

    /** 最近一次失败原因的落盘文件名。 */
    private const val ERROR_FILE = "last-error.txt"

    /**
     * 状态变更的版本号，供 UI 观察。
     *
     * `state.json` 本身是文件，Compose 看不见它的变化。界面读的是「当前生效哪一版」，
     * 所以每次写入后把计数加一，UI 以此为 key 重读一次 —— 比把整份状态塞进 DataStore 轻。
     */
    private val revisionsFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions: kotlinx.coroutines.flow.StateFlow<Long> = revisionsFlow

    fun state(context: Context): PackState {
        val file = stateFile(context)
        if (!file.isFile) return PackState()
        val text = runCatching { file.readText() }.getOrNull() ?: return PackState()
        return runCatching {
            val o = JSONObject(text)
            PackState(
                version = o.optLong("version", 0L),
                source = o.optString("source", "builtin"),
                appliedAt = o.optLong("appliedAt", 0L),
                lastCheckAt = o.optLong("lastCheckAt", 0L),
                notes = o.optString("notes", ""),
                lastAttemptAt = o.optLong("lastAttemptAt", 0L),
                failedVersion = o.optLong("failedVersion", 0L),
                failedAt = o.optLong("failedAt", 0L),
                failedReason = o.optString("failedReason", "")
            )
        }.getOrDefault(PackState())
    }

    /**
     * 读一份资源文本。远程包优先，没有则读内置。
     *
     * 返回 null 表示两处都没有 —— 调用方自己决定是空列表还是报错，
     * 这里吞掉异常不抛，免得一个坏文件把整个页面带崩。
     */
    fun readText(context: Context, path: String): String? {
        val current = state(context)
        if (current.isRemote) {
            val file = File(versionDir(context, current.version), path)
            if (file.isFile && file.length() > 0L) {
                runCatching { file.readText() }.getOrNull()?.let { return it }
            }
        }
        return runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /**
     * 某路径当前生效的来源：`pack@<版本>` / `assets` / `missing`。
     *
     * 验收「内容确实来自资源包」时用它 —— 光看界面分不清用的是包还是内置，
     * 而这一版正是要证明「APK 里没有内容也能跑起来」。
     */
    fun sourceOf(context: Context, path: String): String {
        val current = state(context)
        if (current.isRemote) {
            val file = File(versionDir(context, current.version), path)
            if (file.isFile && file.length() > 0L) return "pack@${current.version}"
        }
        val inAssets = runCatching {
            context.assets.open(path).use { it.readBytes() }
        }.isSuccess
        return if (inAssets) "assets" else "missing"
    }

    /**
     * 把校验过的一组文件写成一个新版本并设为生效。
     *
     * 先整目录写完、再改 `state.json`：中途被杀最多留下一个没人引用的目录，
     * 不会出现「state 指向半个包」这种既读不全又退不回的状态。
     */
    internal fun apply(
        context: Context,
        manifest: PackManifest,
        payload: Map<String, String>,
        now: Long
    ): Boolean {
        val dir = versionDir(context, manifest.version)
        if (dir.exists() && !dir.deleteRecursively()) return false
        if (!dir.mkdirs()) return false

        for ((path, text) in payload) {
            val target = File(dir, path)
            if (target.parentFile?.exists() == false && target.parentFile?.mkdirs() != true) {
                return false
            }
            if (!runCatching { target.writeText(text) }.isSuccess) return false
        }

        val previous = state(context)
        writeState(
            context,
            previous.copy(
                version = manifest.version,
                source = "remote",
                appliedAt = now,
                lastCheckAt = now,
                notes = manifest.notes,
                // 应用成功才清掉失败记录，否则同版本还会被跳过
                failedVersion = 0L,
                failedAt = 0L,
                failedReason = ""
            )
        )
        purgeOldVersions(context, keep = manifest.version)
        revisionsFlow.value += 1
        AppLog.log("Pack", "已应用 v${manifest.version}（${payload.size} 个文件）")
        return true
    }

    fun markChecked(context: Context, at: Long) {
        writeState(context, state(context).copy(lastCheckAt = at))
    }

    /**
     * 记录「这个版本的内容不合格」。
     *
     * 只有内容问题才走这里（sha 不符、闸门拒绝、写入失败）。网络失败不许调 ——
     * 那会把一次抖动变成永久拉黑，0.34.x 就是这么把 13:16 那版钉死的。
     */
    fun markFailed(context: Context, version: Long, reason: String, at: Long) {
        writeState(
            context,
            state(context).copy(failedVersion = version, failedAt = at, failedReason = reason)
        )
        recordError(context, "版本=$version 原因=$reason")
        revisionsFlow.value += 1
    }

    /** 每次尝试检查都记一次，界面上的「上次检查」读它。 */
    fun markAttempt(context: Context, at: Long) {
        writeState(context, state(context).copy(lastAttemptAt = at))
    }

    /** 手动重试前调用：清掉失败标记，让同一个版本号还能再试。 */
    fun clearFailure(context: Context) {
        writeState(context, state(context).copy(failedVersion = 0L, failedAt = 0L, failedReason = ""))
        revisionsFlow.value += 1
    }

    /**
     * 把最近一次失败原因落成一个文件。
     *
     * `AppLog` 是内存环形缓冲，进程一重启就没了 —— 上次就是这样丢掉了唯一的定位线索。
     * 这一行跨进程可读，用户在反馈里贴出来就能直接看出是哪一步断的。
     */
    fun recordError(context: Context, text: String) {
        AppLog.log("Pack", text)
        runCatching {
            val root = root(context)
            if (!root.exists() && !root.mkdirs()) return@runCatching
            File(root, ERROR_FILE).writeText("${System.currentTimeMillis()}  $text\n")
        }
    }

    /** 恢复出厂设置时清掉下载的包，重新回到内置资源。 */
    fun clear(context: Context) {
        runCatching { root(context).deleteRecursively() }
        revisionsFlow.value += 1
        AppLog.log("Pack", "已清除下载的资源包")
    }

    // ---- 内部 ----

    private fun root(context: Context): File = File(context.filesDir, ROOT)

    private fun versionDir(context: Context, version: Long): File = File(root(context), version.toString())

    private fun stateFile(context: Context): File = File(root(context), STATE)

    private fun writeState(context: Context, state: PackState) {
        val root = root(context)
        if (!root.exists() && !root.mkdirs()) return
        val json = JSONObject().apply {
            put("version", state.version)
            put("source", state.source)
            put("appliedAt", state.appliedAt)
            put("lastCheckAt", state.lastCheckAt)
            put("notes", state.notes)
            put("lastAttemptAt", state.lastAttemptAt)
            put("failedVersion", state.failedVersion)
            put("failedAt", state.failedAt)
            put("failedReason", state.failedReason)
        }.toString()

        // 先写临时文件再改名：改名是原子的，进程被杀也不会留下半截 JSON
        val tmp = File(root, "$STATE.tmp")
        if (!runCatching { tmp.writeText(json) }.isSuccess) return
        if (!tmp.renameTo(stateFile(context))) {
            runCatching { stateFile(context).writeText(json) }
            tmp.delete()
        }
    }

    private fun purgeOldVersions(context: Context, keep: Long) {
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return
        // 留当前版 + 上一版：新包出问题时才有东西可退。再往前的一律删，避免磁盘无限长
        val previous = pickPreviousVersion(keep, dirs.mapNotNull { it.name.toLongOrNull() })
        dirs.forEach { file ->
            val version = file.name.toLongOrNull() ?: return@forEach
            if (version != keep && version != previous) {
                runCatching { file.deleteRecursively() }
            }
        }
    }

    /** 设备上已落地的版本号，供调试面板与回退判定使用。 */
    fun availableVersions(context: Context): List<Long> =
        root(context).listFiles()?.filter { it.isDirectory }
            ?.mapNotNull { it.name.toLongOrNull() }
            ?.sortedDescending()
            .orEmpty()

    /** 可回退的那一版；没有则 null（还在用内置资源，或只有一版）。 */
    fun previousVersion(context: Context): Long? =
        pickPreviousVersion(state(context).version, availableVersions(context))

    /**
     * 回退到上一版。
     *
     * **必须同时把被放弃的版本记进 `failedVersion`**：否则下次自动检查（1 小时窗口）
     * 会看到远端版本更高，把用户又拉回那个坏包 —— 用户会以为按钮没生效。
     * 记成"内容不合格"复用现有的 24 小时重试窗口，手动检查仍可强制放行。
     *
     * 返回回退后的版本；没有可回退的返回 null。
     */
    fun rollbackToPrevious(context: Context, reason: String = "手动回退"): Long? {
        val current = state(context)
        val target = pickPreviousVersion(current.version, availableVersions(context)) ?: return null
        val now = System.currentTimeMillis()
        writeState(
            context,
            current.copy(
                version = target,
                source = "remote",
                appliedAt = now,
                notes = "已回退：$reason"
            )
        )
        markFailed(context, current.version, reason, now)
        revisionsFlow.value += 1
        AppLog.log("Pack", "已回退到 v$target（放弃 v${current.version}）")
        return target
    }

    /** 哈希实现收敛在 [app.nbult.lite.util.Digests]，这里只保留入口。 */
    internal fun sha256(bytes: ByteArray): String = app.nbult.lite.util.Digests.sha256(bytes)
}

/** `202610311130` → `2026-10-31 11:30`。12 位连写没人愿意读。 */
fun formatPackVersion(version: Long): String {
    if (version <= 0L) return "内置"
    val s = version.toString()
    if (s.length != 12) return s
    return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} " +
        "${s.substring(8, 10)}:${s.substring(10, 12)}"
}
