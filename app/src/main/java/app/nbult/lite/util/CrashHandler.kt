package app.nbult.lite.util

import android.content.Context
import android.os.Build
import app.nbult.lite.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃取证。
 *
 * 背景：`AppLog` 只存在内存里，进程被杀后记录全部消失 —— 所以"闪退"之后
 * 导出的日志里看不到任何崩溃痕迹，只能靠猜。这里把未捕获异常的完整堆栈
 * 立刻写盘，并在下次启动时重新灌回工作日志。
 *
 * 记录位置：`filesDir/crash/last-crash.txt`（下次启动读取后删除），
 * 以及 `filesDir/crash/history/` 下最多 3 份历史。
 */
object CrashHandler {

    private const val DIR_NAME = "crash"
    private const val LAST_FILE = "last-crash.txt"
    private const val MAX_HISTORY = 3
    private const val MAX_CHARS = 16_000

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 最早处调用。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext

        runCatching { loadPrevious(appContext) }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(appContext, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** 上次运行留下的崩溃堆栈：读回日志后删除，避免每次启动重复出现。 */
    private fun loadPrevious(context: Context) {
        val file = File(dir(context), LAST_FILE)
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        runCatching { file.delete() }
        if (text.isBlank()) return
        AppLog.recordCrash(text)
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val text = buildReport(thread, error)
        runCatching {
            val dir = dir(context)
            if (!dir.exists()) dir.mkdirs()
            File(dir, LAST_FILE).writeText(text)
            val history = File(dir, "history").apply { if (!exists()) mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(history, "crash-$stamp.txt").writeText(text)
            history.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_HISTORY)
                ?.forEach { runCatching { it.delete() } }
            // 再抄一份到外部私有目录（/sdcard/Android/data/<pkg>/files/crash/）：
            // 万一崩在某个页面导致应用内导不出日志，还能用 adb pull 直接取走
            runCatching external@{
                val external = context.getExternalFilesDir("crash") ?: return@external
                if (!external.exists()) external.mkdirs()
                File(external, "$stamp.txt").writeText(text)
            }
        }
        AppLog.recordCrash(text)
    }

    private fun buildReport(thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { sw ->
            runCatching { PrintWriter(sw).use { error.printStackTrace(it) } }
        }.toString()
        val head = buildString {
            appendLine("崩溃时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("线程：${thread.name}")
            appendLine("异常：${error.javaClass.name}: ${error.message}")
            appendLine("-".repeat(40))
        }
        val text = head + stack
        return if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n…（截断）" else text
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)
}
