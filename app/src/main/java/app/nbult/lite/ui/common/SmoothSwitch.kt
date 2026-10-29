package app.nbult.lite.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * 开关。
 *
 * 自己画而不用 M3 `Switch`，是为了控制**返回动画**：M3 的滑块回位用的是内部固定 spec，
 * 松手即弹回去、观感发硬。这里用一条偏软的 spring（0.82 / 360）驱动同一个 0→1 进度，
 * 轨道色、滑块位置、滑块色全由它插值，所以来回是同一段曲线。
 *
 * ⚠️ 颜色插值**必须**用本文件的 [lerpSrgb]，不能用 `androidx.compose.ui.graphics.lerp`：
 * 后者走 Oklab 空间，插到 `Color.Transparent` 时往返转换会算出 `alpha = -0.0017`
 * 这类越界分量，`Color()` 校验当场抛 `IllegalArgumentException: ... outside the range for Oklab`
 * —— 0.18.5/0.18.6 那次"一开精致动效就闪退"就是这么来的（v0.18.7 修）。
 */
@Composable
fun SmoothSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptics = rememberAppHaptics()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 360f),
        label = "smooth-switch"
    )
    val colors = MaterialTheme.colorScheme
    // ⚠️ 关键：progress 必须夹到 [0,1] 再用。
    // spring(dampingRatio = 0.82f) 是欠阻尼弹簧，到达目标会**过冲**（实测 1.0031），
    // 于是 1 - progress 变成 -0.0031 → alpha 为负 → Color.copy() 抛
    // "alpha = -0.0017 outside the range for sRGB"（v0.18.7/0.18.8 两次闪退的真因）。
    val p = progress.coerceIn(0f, 1f)
    val trackColor = lerpSrgb(colors.surfaceVariant, colors.primary, p)
    val thumbColor = lerpSrgb(colors.outline, colors.onPrimary, p)
    // 描边只在"关"的时候存在：直接缩 alpha，不经过 lerp（避免 Oklab 外插）
    val borderColor = colors.outline.copy(alpha = 0.55f * (1f - p))

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(16.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { next ->
                    haptics.toggle(next)
                    onCheckedChange(next)
                }
            )
    ) {
        Box(
            modifier = Modifier
                // 位移写在 offset 的 lambda 里：只走布局阶段，不触发重组，动画才跟得上 120Hz
                .offset {
                    IntOffset(
                        x = (4.dp + 20.dp * p).roundToPx(),
                        y = 4.dp.roundToPx()
                    )
                }
                .size(24.dp)
                .shadow(elevation = 1.5.dp, shape = CircleShape)
                .background(thumbColor, CircleShape)
        )
    }
}

/**
 * sRGB 分量线性插值，输出永远落在合法范围内。
 *
 * 不用 `androidx.compose.ui.graphics.lerp` 的原因见上方类注释：它在 Oklab 空间插值，
 * 遇到半透明 → 全透明这类端点会产出越界分量并抛异常。这里先统一转换到 sRGB，
 * 再对四个通道做凸组合并夹取，数学上不可能越界。
 */
private fun lerpSrgb(start: Color, stop: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    val a = start.convert(ColorSpaces.Srgb)
    val b = stop.convert(ColorSpaces.Srgb)
    return Color(
        red = (a.red + (b.red - a.red) * t).coerceIn(0f, 1f),
        green = (a.green + (b.green - a.green) * t).coerceIn(0f, 1f),
        blue = (a.blue + (b.blue - a.blue) * t).coerceIn(0f, 1f),
        alpha = (a.alpha + (b.alpha - a.alpha) * t).coerceIn(0f, 1f),
        colorSpace = ColorSpaces.Srgb
    )
}
