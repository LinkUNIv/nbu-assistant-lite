package app.nbult.lite.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor

/**
 * 主题色种子。
 *
 * 只换 primary 一族，其余中性色（背景、表面、文字）不动 —— 换色时页面骨架不该跟着变，
 * 用户要的是"强调色变了"，不是"整页换了张皮"。
 */
enum class SeedColor(
    val id: String,
    val label: String,
    val light: Color,
    val dark: Color
) {
    Blue("blue", "蓝", Color(0xFF1F6FEB), Color(0xFF7FB0FF)),
    Teal("teal", "青", Color(0xFF0F766E), Color(0xFF5DCAA5)),
    Purple("purple", "紫", Color(0xFF6B4FB5), Color(0xFFB9A3F0)),
    Amber("amber", "橙", Color(0xFFB4690E), Color(0xFFF0B860)),
    Green("green", "绿", Color(0xFF2E7D32), Color(0xFF8BC98F));

    companion object {
        fun of(id: String?): SeedColor = entries.firstOrNull { it.id == id } ?: Blue
    }
}

/** 深浅模式三态。 */
enum class DarkMode(val id: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色");

    companion object {
        fun of(id: String?): DarkMode = entries.firstOrNull { it.id == id } ?: System
    }
}

/**
 * 背景。
 *
 * 目前只做纯色和三款预设渐变，不接相册图片 ——
 * 图片要处理降采样、裁剪、存储和权限，是另一个量级的事，
 * 先把"面板能透出背景"这条路跑通，图片以后接在同一个枚举上即可。
 */
enum class Backdrop(val id: String, val label: String) {
    Plain("plain", "纯色"),
    Aurora("aurora", "极光"),
    Dusk("dusk", "暮色"),
    Mist("mist", "薄雾");

    companion object {
        fun of(id: String?): Backdrop = entries.firstOrNull { it.id == id } ?: Plain
    }
}

/** 背景画什么。`plain` 直接返回页面底色，保证不换背景时观感和之前一模一样。 */
fun Backdrop.brushFor(dark: Boolean, background: Color): Brush = when (this) {
    Backdrop.Plain -> SolidColor(background)

    Backdrop.Aurora -> Brush.linearGradient(
        if (dark) listOf(Color(0xFF101B26), Color(0xFF152A33), Color(0xFF1B2130))
        else listOf(Color(0xFFF2F7FF), Color(0xFFEAF4F4), Color(0xFFF6F1FA))
    )

    Backdrop.Dusk -> Brush.linearGradient(
        if (dark) listOf(Color(0xFF16131C), Color(0xFF221A22), Color(0xFF171A24))
        else listOf(Color(0xFFFDF6EF), Color(0xFFF8EFEA), Color(0xFFF4F1FA))
    )

    Backdrop.Mist -> Brush.linearGradient(
        if (dark) listOf(Color(0xFF14181C), Color(0xFF1A2026), Color(0xFF121619))
        else listOf(Color(0xFFF8FAFB), Color(0xFFEFF3F6), Color(0xFFF7F9FC))
    )
}

internal fun lightScheme(seed: SeedColor): ColorScheme {
    val p = seed.light
    return lightColorScheme(
        primary = p,
        onPrimary = Color.White,
        primaryContainer = lerp(Color.White, p, 0.14f),
        onPrimaryContainer = lerp(Color.Black, p, 0.78f),
        secondary = seed.dark,
        onSecondary = Color.White,
        background = Color(0xFFF7F9FC),
        onBackground = Color(0xFF151A21),
        surface = Color.White,
        onSurface = Color(0xFF151A21),
        surfaceVariant = Color(0xFFEEF2F8),
        onSurfaceVariant = Color(0xFF4A5565),
        outline = Color(0xFFB9C2D0),
        error = Color(0xFFC1272D)
    )
}

internal fun darkScheme(seed: SeedColor): ColorScheme {
    val p = seed.dark
    return darkColorScheme(
        primary = p,
        onPrimary = Color(0xFF0B1220),
        primaryContainer = lerp(Color.Black, p, 0.30f),
        onPrimaryContainer = lerp(Color.White, p, 0.72f),
        secondary = seed.light,
        onSecondary = Color(0xFF0B1220),
        background = Color(0xFF0F1216),
        onBackground = Color(0xFFE6EAF0),
        surface = Color(0xFF161A20),
        onSurface = Color(0xFFE6EAF0),
        surfaceVariant = Color(0xFF1E242C),
        onSurfaceVariant = Color(0xFFAAB4C2),
        outline = Color(0xFF3A424D),
        error = Color(0xFFF09595)
    )
}
