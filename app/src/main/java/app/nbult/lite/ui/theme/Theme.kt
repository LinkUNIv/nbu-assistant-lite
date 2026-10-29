package app.nbult.lite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/**
 * 应用主题。
 *
 * 两件事都可变：深浅（三态，由调用方算好 `darkTheme` 传进来）和主题色种子。
 * 配色表在 [Appearance.kt] 里按种子派生，这里只负责把结果交给 MaterialTheme。
 */
@Composable
fun NbuLiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seed: SeedColor = SeedColor.Blue,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme(seed) else lightScheme(seed),
        typography = Typography(),
        content = content
    )
}
