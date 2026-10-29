package app.nbult.lite.ui.background

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nbult.lite.ui.common.luminanceIsDark
import app.nbult.lite.ui.theme.Backdrop
import app.nbult.lite.ui.theme.brushFor

/**
 * 应用最底层背景。
 *
 * 放在 `Scaffold` 之前、铺满整个窗口 —— 状态栏那条也会被它填上，
 * 所以状态栏颜色不再由 `Scaffold.containerColor` 决定，而是跟着背景走。
 *
 * 页面自己的底色是**半透明**的（`background.copy(alpha = 0.65f)`），
 * 渐变从这里透上来；`plain` 模式下底下铺的和页面上盖的是同一个颜色，
 * 叠加后仍是原色，观感和不接背景时完全一致。
 */
@Composable
fun AppBackdrop(id: String, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxSize()
            .background(Backdrop.of(id).brushFor(background.luminanceIsDark(), background))
    )
}
