package app.nbult.lite.ui.common

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 全应用统一的动效规格。
 *
 * [Decelerate] 是「减速曲线」：起步即最大速度、末速归零，
 * 区别于 Material 的 FastOutSlowIn（S 形，前段在加速，观感像“先慢后快再急停”）。
 */
object MotionSpecs {

    /**
     * 温和减速：起步速度适中、末速归零。
     * 相比 (0.05, 0.7, …) 少了"前 20% 走完 55% 距离"的突兀感，适合按钮触发的整页切换。
     */
    val Decelerate: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 跟手吸附：前置更强，松手后立刻贴近目标页，手感更跟手。 */
    val Snap: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 通用减速动画，默认 280ms。 */
    fun <T> decelerate(durationMillis: Int = 280): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = Decelerate)

    /** 按钮触发的整页切换：340ms，比吸附更慢但更顺。 */
    fun <T> page(durationMillis: Int = 340): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = Decelerate)

    /** 释放后的吸附：260ms。 */
    fun <T> snap(durationMillis: Int = 260): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = Snap)
}

/**
 * 入场模糊：与 NavHost 的左右横移叠加，形成「横向推入 + 由虚到实」的观感。
 *
 * 性能处理：
 * 1. 模糊只在动画期间挂载，结束后完全移除该图层，静止时零开销；
 * 2. 系统「动画时长缩放」为 0（开发者选项或无障碍中的“移除动画”）时直接跳过；
 * 3. 高斯模糊走 RenderEffect，需要 API 31+，低版本自动退化为纯横移。
 */
@Composable
fun BlurIn(
    enabled: Boolean = true,
    radius: Int = 6,
    durationMillis: Int = 150,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    val context = LocalContext.current
    val animationsEnabled = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f) > 0f
    }

    var settled by remember { mutableStateOf(!animationsEnabled) }
    LaunchedEffect(Unit) { if (animationsEnabled) settled = true }

    val blurRadius by animateDpAsState(
        targetValue = if (settled) 0.dp else radius.dp,
        animationSpec = tween(durationMillis),
        label = "entryBlur"
    )

    val modifier = if (blurRadius > 0.5.dp) {
        Modifier
            .fillMaxSize()
            .blur(blurRadius)
    } else {
        Modifier.fillMaxSize()
    }

    Box(modifier) { content() }
}
