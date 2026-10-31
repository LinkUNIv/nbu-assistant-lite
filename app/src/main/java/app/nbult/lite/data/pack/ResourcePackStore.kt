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
                failedVersion = o.optLong("failedVersion", 0L)
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
                failedVersion = 0L
            )
        )
        purgeOldVersions(context, keep = manifest.version)
        AppLog.log("Pack", "已应用 v${manifest.version}（${payload.size} 个文件）")
        return true
    }

    fun markChecked(context: Context, at: Long) {
        writeState(context, state(context).copy(lastCheckAt = at))
    }

    fun markFailed(context: Context, version: Long, reason: String) {
        writeState(context, state(context).copy(failedVersion = version))
        AppLog.log("Pack", "回退 版本=$version 原因=$reason")
    }

    /** 恢复出厂设置时清掉下载的包，重新回到内置资源。 */
    fun clear(context: Context) {
        runCatching { root(context).deleteRecursively() }
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
            put("failedVersion", state.failedVersion)
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
        root(context).listFiles()?.forEach { file ->
            if (file.isDirectory && file.name != keep.toString()) {
                runCatching { file.deleteRecursively() }
            }
        }
    }

    /** 与 [ResourcePackChecker] 共用，避免两处各写一份哈希。 */
    internal fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) append("%02x".format(b))
        }
    }
}

/** `202610311130` → `2026-10-31 11:30`。12 位连写没人愿意读。 */
fun formatPackVersion(version: Long): String {
    if (version <= 0L) return "内置"
    val s = version.toString()
    if (s.length != 12) return s
    return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)} " +
        "${s.substring(8, 10)}:${s.substring(10, 12)}"
}
