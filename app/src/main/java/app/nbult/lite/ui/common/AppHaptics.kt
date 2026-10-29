package app.nbult.lite.ui.common

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import app.nbult.lite.util.AppLog

/**
 * 全应用统一的触感反馈。
 *
 * **为什么优先走 View 而不是 Vibrator**：0.18.3 补了 `VIBRATE` 权限后震动依然是"没有"，
 * 说明问题不在权限，而在 `VibrationEffect` 的 composition 原语 ——
 * 部分 ROM（含 HyperOS）对 `PRIMITIVE_TICK` 是**静默忽略**：既不报错也不震，
 * `runCatching` 一点都兜不住。系统给"滚轮刻度"预留的语义是
 * [HapticFeedbackConstants.CLOCK_TICK]（API 21+）/ `SEGMENT_TICK`（API 34+），
 * 走 `View.performHapticFeedback` 会交给窗口的触感引擎，各家皮肤都认，且天然尊重
 * 系统的"触摸反馈"开关与强度设置。Vibrator 只作为兜底。
 */
class AppHaptics(private val view: View, context: Context) {

    private val appContext = context.applicationContext
    private var lastWheelTickAt = 0L
    private var logged = false

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** 滚轮越过一格：轻点到几乎听不见，但不能连成一片，所以 40ms 节流。 */
    fun wheelTick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastWheelTickAt < 40L) return
        lastWheelTickAt = now
        val handled = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }.getOrDefault(false)
        if (!handled) vibratorFallback(longArrayOf(0L, 8L))
        // 只记第一次：日志里能看出这条链路到底有没有被系统接住，避免再靠猜
        if (!logged) {
            logged = true
            AppLog.log(
                "Haptics",
                "首个滚轮刻度 via=${if (handled) "view" else "vibrator"} sdk=${Build.VERSION.SDK_INT} " +
                    "viewEnabled=${runCatching { view.isHapticFeedbackEnabled }.getOrDefault(false)}"
            )
        }
    }

    /** 开关拨动：开/关给不同的语义（API 34 起系统有专门常量）。 */
    fun toggle(on: Boolean) {
        val handled = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                view.performHapticFeedback(
                    if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
                )
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }.getOrDefault(false)
        if (!handled) vibratorFallback(longArrayOf(0L, 12L))
    }

    /**
     * 只有"系统说开了触感、但 View 这条链路没被认"时才兜底震动，
     * 系统设置里关掉触感就必须彻底安静 —— 不能自己绕过去震。
     */
    private fun vibratorFallback(pattern: LongArray) {
        if (!hapticFeedbackEnabled()) return
        val vibrator = vibrator ?: return
        if (!runCatching { vibrator.hasVibrator() }.getOrDefault(false)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
        // 注：composition 原语在部分 ROM 上会被静默忽略，所以这里不用它
    }

    @Suppress("DEPRECATION")
    private fun hapticFeedbackEnabled(): Boolean = runCatching {
        Settings.System.getInt(
            appContext.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
    }.getOrDefault(true)
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context) { AppHaptics(view, context) }
}
