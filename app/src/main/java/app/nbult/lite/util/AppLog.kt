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

    /**
     * 结构化事件：`[tag] 名称 key=value key=value`。
     *
     * 统一成 key=value 而不是自由文本，是为了让验收可以「读日志判定」而不是靠眼睛 ——
     * 例如 `[Nav] 请求 目标=8 来源=本周 方式=瞬移` 这一行就能证明按钮真的动了。
     * 值为 null 的字段直接跳过，避免日志里出现 `key=null`。
     */
    fun event(tag: String, name: String, vararg fields: Pair<String, Any?>) {
        val body = buildString {
            append(name)
            fields.forEach { (key, value) ->
                if (value != null) append(' ').append(key).append('=').append(value)
            }
        }
        log(tag, body)
    }

    /**
     * 不变量自检：`ok = false` 时落一条 `[Assert]` 记录。
     *
     * 这样「有没有坏」不需要人盯屏幕 —— 验收清单只要写「日志里不得出现 `[Assert]`」。
     * 返回 [ok]，便于调用点按需继续处理。
     */
    fun assert(tag: String, name: String, ok: Boolean, detail: String = ""): Boolean {
        if (!ok) {
            log("Assert", "$tag.$name${if (detail.isEmpty()) "" else " $detail"}")
        }
        return ok
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
        // 摘要在最前面：验收时读这几行就够，不用在几十行流水里数
        summaryLines().forEach { appendLine(it) }
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

    /** 从已有条目里现算，不额外维护计数器 —— 少一处状态就少一处能算错的地方。 */
    private fun summaryLines(): List<String> {
        val texts = entries.map { it.text }
        fun hits(vararg needles: String) = texts.count { line -> needles.all { line.contains(it) } }
        val launches = hits("[App]", "启动")
        val navRequests = hits("[Nav]", "请求")
        val teleports = hits("方式=瞬移")
        val asserts = hits("[Assert]")
        val reminders = hits("[Reminder]", "下一次=")
        // 取**最差**一次而不是最近一次：最近一次往往刚好是平顺的那次，
        // 只看它会把开头的 11%、14% 藏掉，比不写摘要更容易误判
        // (掉帧百分比, 帧数, "5(35%)")。帧数要一起带上：14 帧里掉 5 帧也是 35%，
        // 只报百分比会把人吓一跳，报出帧数才知道这是不是一个可信的样本
        val drops = texts.filter { it.contains("[Perf]") && it.contains("掉帧=") }
            .mapNotNull { line ->
                val token = line.substringAfter("掉帧=").substringBefore(" 平均").trim()
                val pct = token.substringAfter('(', "").substringBefore("%)").toIntOrNull()
                val frames = line.substringAfter("帧数=").substringBefore(' ').toIntOrNull() ?: 0
                if (pct == null) null else Triple(pct, frames, token)
            }
        // 优先用 ≥30 帧的样本；全是短样本时才退回全部，并如实报出帧数
        val worstDrop = drops.filter { it.second >= 30 }.ifEmpty { drops }.maxByOrNull { it.first }
        return buildList {
            add("摘要（读这几行就够）：")
            add("  启动 $launches 次 · 切周 $navRequests 次（其中瞬移 $teleports）")
            add("  提醒重排 $reminders 次")
            add("  [Assert] $asserts 条" + if (asserts == 0) "（正常）" else "（异常，看正文）")
            if (worstDrop != null) {
                add("  最差一次滑动 掉帧=${worstDrop.third}（${worstDrop.second} 帧）")
            }
            if (launches == 0 && texts.isNotEmpty()) {
                add("  本次运行没有启动记录，正文可能来自上一次会话")
            }
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
