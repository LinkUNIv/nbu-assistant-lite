package app.nbult.lite

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import app.nbult.lite.ui.AppRoot
import app.nbult.lite.ui.LocalAppContainer
import app.nbult.lite.ui.theme.NbuLiteTheme
import app.nbult.lite.util.FrameStats

/**
 * 唯一入口。
 *
 * 除了主题与导航，这里还负责两件与"滑得顺不顺"直接相关的事：
 *
 * 1. **请求高刷新率**：LTPO 面板默认会把非游戏应用压到 60/90Hz。
 *    注意只允许在同分辨率内挑刷新率 —— 选到分辨率不同的模式会触发一次
 *    Surface 重建（显示模式切换），那正是滑动中最脆弱的时刻。
 *
 * 2. **挂 FrameMetrics 监听**（API 24+）：[Choreographer] 只能测"多久出一帧"，
 *    测不出"这一帧我们花了多久"。两者混在一起会把"面板被降到 60Hz"误判成
 *    "App 太慢"。FrameMetrics 给出组合布局 / 同步 / GPU 的真实耗时，
 *    才能决定优化 CPU 还是优化 GPU。
 */
class MainActivity : ComponentActivity() {

    private var metricsThread: HandlerThread? = null

    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        FrameStats.onFrameMetrics(
            total = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
            layout = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
            animation = metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
            sync = metrics.getMetric(FrameMetrics.SYNC_DURATION),
            draw = metrics.getMetric(FrameMetrics.DRAW_DURATION),
            gpu = metrics.getMetric(FrameMetrics.GPU_DURATION),
            swap = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
            unknownDelay = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
        )
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NbuLiteApp).container

        requestHighRefreshRate()
        attachFrameMetrics()

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                NbuLiteTheme {
                    AppRoot()
                }
            }
        }
    }

    /**
     * 在同分辨率内挑最高刷新率。
     *
     * 只做「同分辨率」这一层过滤：选到分辨率不同的模式会触发一次显示模式切换
     * （Surface 重建），而那是滑动中最脆弱的时刻。
     * `preferredRefreshRate` 在 API 30 起被标记废弃，但至今仍是各 ROM
     * （尤其 MIUI/HyperOS）实际生效最广的提示；`Window.setFrameRate` 是系统 API，
     * 普通应用调不到，所以不指望它。
     */
    private fun requestHighRefreshRate() {
        runCatching {
            val display = windowManager.defaultDisplay
            val current = display.mode
            val best = display.supportedModes
                .filter {
                    (it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight) ||
                        (it.physicalWidth == current.physicalHeight &&
                            it.physicalHeight == current.physicalWidth)
                }
                .maxByOrNull { it.refreshRate }
            if (best != null && best.refreshRate > current.refreshRate + 1f) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = best.modeId
                    @Suppress("DEPRECATION")
                    preferredRefreshRate = best.refreshRate
                }
            }
        }
    }

    private fun attachFrameMetrics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            val thread = HandlerThread("frame-metrics").apply { start() }
            metricsThread = thread
            window.addOnFrameMetricsAvailableListener(
                frameMetricsListener,
                Handler(thread.looper)
            )
        }
    }

    override fun onDestroy() {
        runCatching { window.removeOnFrameMetricsAvailableListener(frameMetricsListener) }
        metricsThread?.quitSafely()
        metricsThread = null
        FrameStats.stop()
        super.onDestroy()
    }
}
