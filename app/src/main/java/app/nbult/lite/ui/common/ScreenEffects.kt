package app.nbult.lite.ui.common

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

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
 * 切页入场效果：与 NavHost 的左右横移叠加，形成「横向推入 + 由淡到实」的观感。
 *
 * **为什么现在不用整页高斯模糊**：
 * 0.18.6 曾把「一开精致动效就闪退」归因于这里的整页 `Modifier.blur()`，事后证明是误判 ——
 * 真正的崩溃在 `SmoothSwitch` 的 `lerp(Color.Transparent)`（Oklab 越界，见 v0.18.7）。
 * 也就是说整页模糊并没有被证实有问题，只是"顺手一起删掉了"。
 * 目前它保持移除状态（它确实是全应用里最重的一次离屏渲染），需要的话可以单独加回来。
 *
 * 现在只用**不建离屏图层**的 alpha + 轻微缩放：结构永远是有 Box 的（不随开关改变组合结构），
 * 开关只改参数，因此不存在"在动画中途换掉整棵子树"的风险。
 */
@Composable
fun PageEnterEffect(
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
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
    val active = enabled && animationsEnabled
    // 首帧以"未落地"状态呈现，随后翻成 1f 触发动画；关闭时直接落地，不播动画
    var settled by remember { mutableStateOf(!active) }
    LaunchedEffect(active) {
        if (active) settled = true
    }

    val progress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(220, easing = MotionSpecs.Decelerate),
        label = "pageEnter"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 只动 alpha 与缩放，不碰 RenderEffect / 离屏合成
                alpha = 0.45f + 0.55f * progress
                val scale = 0.988f + 0.012f * progress
                scaleX = scale
                scaleY = scale
            }
    ) {
        content()
    }
}
