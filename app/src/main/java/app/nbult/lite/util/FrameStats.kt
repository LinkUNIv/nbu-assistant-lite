package app.nbult.lite.util

import android.view.Choreographer
import java.util.Locale

/**
 * 滑动帧统计。
 *
 * 分两层看，缺一层就会误判：
 *
 * 1. **出帧节奏**（[Choreographer] 回调间隔）—— 面板实际按多少 Hz 供帧。
 *    平均间隔 16.7ms 就是 60Hz、8.3ms 才是 120Hz。
 *    如果以为在跑 120Hz、实际面板只给 60Hz，那么"掉帧率"这个数字毫无意义，
 *    因为每一帧都"超预算"。所以这里同时记录开始与结束时的实测刷新率。
 *
 * 2. **单帧 App 耗时**（`Window.OnFrameMetricsAvailableListener`）—— 我们自己到底花了多久。
 *    - 测量布局（LAYOUT_MEASURE）：Compose 的 measure/layout
 *    - 动画（ANIMATION）：组合阶段的动画回调
 *    - 同步（SYNC）：UI 线程把指令交给 RenderThread
 *    - CPU 画（DRAW）：DisplayList 的 CPU 侧绘制
 *    - GPU（GPU_DURATION）：GPU 实际耗时
 *    - 提交（SWAP）：提交到 SurfaceFlinger
 *    - 等 vsync（UNKNOWN_DELAY）：不可控的排队与等待
 *    这组数字才能回答"该优化 CPU 还是该减 GPU 负载"。
 *
 * 教训（v0.9.0 → v0.9.2）：这个文件曾把带字面量「%」的文案交给 `String.format`，
 * 每次都抛 `UnknownFormatConversionException`，于是"一快滑就闪退"。
 * 现在一律用字符串模板拼接，且所有日志写入都包 runCatching —— 统计代码不允许带崩 App。
 */
object FrameStats {

    private const val NS_PER_MS = 1_000_000f

    private var running = false

    // 出帧节奏
    private var frames = 0
    private var janky = 0
    private var worstNanos = 0L
    private var totalNanos = 0L
    private var lastFrameNanos = 0L

    /** 单帧预算：60Hz ≈ 16.7ms，120Hz ≈ 8.3ms。 */
    private var budgetNanos = 16_666_667L

    // FrameMetrics（App 自身耗时，单位 ns 的累加值）
    private var metricFrames = 0
    private var sumTotal = 0L
    private var maxTotal = 0L
    private var sumLayout = 0L
    private var sumAnimation = 0L
    private var sumSync = 0L
    private var sumDraw = 0L
    private var sumGpu = 0L
    private var sumSwap = 0L
    private var sumUnknown = 0L

    fun setRefreshRate(hz: Float) {
        if (hz > 1f) budgetNanos = (1_000_000_000f / hz).toLong()
    }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                val delta = frameTimeNanos - lastFrameNanos
                frames++
                totalNanos += delta
                if (delta > budgetNanos * 1.2) janky++
                if (delta > worstNanos) worstNanos = delta
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        frames = 0
        janky = 0
        worstNanos = 0L
        totalNanos = 0L
        lastFrameNanos = 0L
        metricFrames = 0
        sumTotal = 0L
        maxTotal = 0L
        sumLayout = 0L
        sumAnimation = 0L
        sumSync = 0L
        sumDraw = 0L
        sumGpu = 0L
        sumSwap = 0L
        sumUnknown = 0L
        runCatching { Choreographer.getInstance().postFrameCallback(callback) }
    }

    /** 由 Activity 的 FrameMetrics 监听器逐帧调用；只在统计窗口内累计。 */
    fun onFrameMetrics(
        total: Long,
        layout: Long,
        animation: Long,
        sync: Long,
        draw: Long,
        gpu: Long,
        swap: Long,
        unknownDelay: Long
    ) {
        if (!running) return
        metricFrames++
        val t = total.coerceAtLeast(0L)
        sumTotal += t
        if (t > maxTotal) maxTotal = t
        sumLayout += layout.coerceAtLeast(0L)
        sumAnimation += animation.coerceAtLeast(0L)
        sumSync += sync.coerceAtLeast(0L)
        sumDraw += draw.coerceAtLeast(0L)
        sumGpu += gpu.coerceAtLeast(0L)
        sumSwap += swap.coerceAtLeast(0L)
        sumUnknown += unknownDelay.coerceAtLeast(0L)
    }

    /** 起滑时的面包屑：万一下一帧就崩，日志里能看出崩在哪个周次、什么刷新率。 */
    fun logScrollStart(page: Int, totalWeeks: Int, refreshRate: Float) {
        runCatching {
            AppLog.log(
                "Perf",
                "滑动开始 第${page}周/共${totalWeeks}周 面板=${hz(refreshRate)} " +
                    "预算=${ms(budgetNanos / NS_PER_MS)}ms"
            )
        }
    }

    fun stop(observedHz: Float = 0f) {
        if (!running) return
        running = false
        runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
        if (frames < 5) return

        val avgMs = totalNanos / frames / NS_PER_MS
        val worstMs = worstNanos / NS_PER_MS
        val budgetMs = budgetNanos / NS_PER_MS
        val pct = janky * 100 / frames
        val fps = if (avgMs > 0f) 1000f / avgMs else 0f

        runCatching {
            AppLog.log(
                "Perf",
                "滑动 帧数=$frames 掉帧=$janky($pct%) " +
                    "平均=${ms(avgMs)}ms(≈${hz(fps)}) 最长=${ms(worstMs)}ms " +
                    "预算=${ms(budgetMs)}ms 面板结束=${hz(observedHz)}"
            )
        }

        if (metricFrames > 0) {
            val n = metricFrames
            runCatching {
                AppLog.log(
                    "Perf",
                    "单帧全耗时=${ms(sumTotal / n / NS_PER_MS)}ms " +
                        "峰值=${ms(maxTotal / NS_PER_MS)}ms | " +
                        "测量布局=${ms(sumLayout / n / NS_PER_MS)} " +
                        "动画=${ms(sumAnimation / n / NS_PER_MS)} " +
                        "同步=${ms(sumSync / n / NS_PER_MS)} " +
                        "CPU画=${ms(sumDraw / n / NS_PER_MS)} " +
                        "GPU=${ms(sumGpu / n / NS_PER_MS)} " +
                        "提交=${ms(sumSwap / n / NS_PER_MS)} " +
                        "等vsync=${ms(sumUnknown / n / NS_PER_MS)} （$n 帧）"
                )
            }
        }
    }

    /** 固定 Locale：避免某些地区用逗号作小数点，日志解析出错。 */
    private fun ms(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun hz(value: Float): String =
        if (value <= 0f) "未知" else String.format(Locale.US, "%.0fHz", value)
}
