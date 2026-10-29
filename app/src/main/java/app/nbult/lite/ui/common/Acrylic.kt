package app.nbult.lite.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * 细磨砂亚克力。
 *
 * 三层叠出来的：
 *
 * 1. **底色**：半透明白（深色模式是半透明深灰），竖向三段渐变 ——
 *    顶部略亮、中部最透、底部回亮，模拟真实亚克力受光后的菲涅耳分布。
 *    透明度刻意压在 0.66–0.80：留出足够通透度，**将来接自定义背景图时能透出来**。
 * 2. **颗粒**：一张 96×96 的随机灰度噪点平铺，整体 alpha 只有 4% 左右。
 *    这是"磨砂"和"塑料"的分界线 —— 少了它，半透明面板看起来像蒙了一层塑料膜。
 *    噪点用灰度随机而不是纯白，浅色和深色底上都能看到颗粒。
 * 3. **描边**：0.8dp 竖向渐变高光，负责把面板从背景里拎出来。
 *
 * **没有真模糊**。Compose 1.6 拿不到"面板背后的像素"（`rememberGraphicsLayer` 是 1.7 才有），
 * 所以这不是把背景糊掉，而是靠降低不透明度 + 颗粒来暗示"这是一层玻璃"。
 * 等升到 1.7 或者改用截帧方案时，只需要把这里换成真 backdrop，调用方一行都不用动。
 */
@Composable
fun Modifier.acrylicPanel(
    shape: Shape,
    cornerRadius: Float = 0f
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val noise = rememberNoiseBrush()

    val fill = Brush.verticalGradient(
        listOf(
            if (dark) Color(0xFF313841).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.82f),
            if (dark) Color(0xFF1E242B).copy(alpha = 0.68f) else Color.White.copy(alpha = 0.66f),
            if (dark) Color(0xFF272E36).copy(alpha = 0.74f) else Color.White.copy(alpha = 0.76f)
        )
    )

    val edge = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.30f else 0.95f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.35f),
            Color.White.copy(alpha = if (dark) 0.16f else 0.68f)
        )
    )

    return this
        .clip(shape)
        .background(fill)
        // drawWithCache：噪点画布是固定的，没必要每帧重新组织绘制命令。
        // 侧栏和顶栏都是随时会重绘的面板，这条能省一点每帧开销
        .drawWithCache {
            onDrawWithContent {
                drawContent()
                drawRect(brush = noise, alpha = 0.55f)
            }
        }
        .border(0.8.dp, edge, shape)
}

/** 噪点贴图只生成一次；96px 在 3x 屏上仍够细，平铺也看不出接缝。 */
@Composable
private fun rememberNoiseBrush(): ShaderBrush {
    val bitmap = remember { noiseBitmap() }
    return remember(bitmap) {
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }
}

private fun noiseBitmap(size: Int = 96): ImageBitmap {
    val pixels = IntArray(size * size)
    val random = Random(20261025)
    for (i in pixels.indices) {
        val grey = random.nextInt(256)
        // alpha 固定很小，明暗随机 —— 形成细微的明暗颗粒，而不是一层白霜
        val alpha = 18
        pixels[i] = (alpha shl 24) or (grey shl 16) or (grey shl 8) or grey
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}

internal fun Color.luminanceIsDark(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f
