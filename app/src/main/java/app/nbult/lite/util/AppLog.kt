package app.nbult.lite.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量工作日志。
 *
 * 体积控制有三道闸：
 * 1. 单条最大 500 字符，超出截断；
 * 2. 内存保留 3 天、最多 500 条，超期/超量自动丢弃；
 * 3. 导出文件只保留最近 5 个、且不超过 3 天，其余自动删除。
 */
object AppLog {

    private const val RETAIN_DAYS = 3
    private const val MAX_ENTRIES = 500
    private const val MAX_ENTRY_CHARS = 500
    private const val MAX_FILES = 5
    private const val DAY_MILLIS = 86_400_000L

    private data class Entry(val at: Long, val text: String)

    private val entries = ArrayDeque<Entry>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 最近一次崩溃的完整堆栈；由 [CrashHandler] 写入，导出日志时附在末尾。 */
    @Volatile
    private var crashRecord: String? = null

    @Synchronized
    fun log(tag: String, message: String) {
        val now = System.currentTimeMillis()
        val body = if (message.length > MAX_ENTRY_CHARS) {
            message.take(MAX_ENTRY_CHARS) + "…（截断）"
        } else {
            message
        }
        entries.addLast(Entry(now, "${timeFormat.format(Date(now))}  [$tag]  $body"))
        trimLocked(now)
    }

    private fun trimLocked(now: Long) {
        val cutoff = now - RETAIN_DAYS * DAY_MILLIS
        while (entries.isNotEmpty() && entries.first().at < cutoff) entries.removeFirst()
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun entries(): List<String> {
        trimLocked(System.currentTimeMillis())
        return entries.map { it.text }
    }

    @Synchronized
    fun count(): Int {
        trimLocked(System.currentTimeMillis())
        return entries.size
    }

    @Synchronized
    fun clear() {
        entries.clear()
        crashRecord = null
    }

    /** 记录一次崩溃：内存里留一行摘要，完整堆栈随日志一起导出。 */
    @Synchronized
    fun recordCrash(report: String) {
        crashRecord = report
        val summary = report.lineSequence()
            .firstOrNull { it.startsWith("异常：") }
            ?.removePrefix("异常：")
            ?: "未知异常"
        log("Crash", "上次运行异常退出 · $summary（完整堆栈见日志末尾）")
    }

    @Synchronized
    fun hasCrashRecord(): Boolean = crashRecord != null

    @Synchronized
    fun dump(): String = buildString {
        appendLine("宁大助手 Lite 工作日志")
        appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("设备时区：${app.nbult.lite.domain.AppClock.deviceZoneId()}")
        appendLine("条目数：${entries.size}（保留 ${RETAIN_DAYS} 天 / 上限 $MAX_ENTRIES 条）")
        appendLine("-".repeat(40))
        if (entries.isEmpty()) appendLine("（暂无记录）")
        entries.forEach { appendLine(it.text) }
        val crash = crashRecord
        if (crash != null) {
            appendLine()
            appendLine("=".repeat(40))
            appendLine("最近一次崩溃堆栈")
            appendLine("=".repeat(40))
            appendLine(crash)
        }
    }

    fun export(context: Context): File {
        val dir = logDir(context)
        if (!dir.exists()) dir.mkdirs()
        val stamp = fileFormat.format(Date())
        val file = File(dir, "nbu-lite-log-$stamp.txt")
        file.writeText(dump())
        pruneFiles(context)
        return file
    }

    fun logDir(context: Context): File =
        context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")

    /** 删除过期/超量的导出文件，保证目录体积始终很小。 */
    fun pruneFiles(context: Context) {
        val dir = logDir(context)
        val files = runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith("nbu-lite-log-") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        }.getOrDefault(emptyList())

        val cutoff = System.currentTimeMillis() - RETAIN_DAYS * DAY_MILLIS
        files.forEachIndexed { index, file ->
            if (index >= MAX_FILES || file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    /** 启动时调用：清理上次遗留的过期文件。 */
    fun houseKeeping(context: Context) {
        runCatching { pruneFiles(context) }
    }
}
