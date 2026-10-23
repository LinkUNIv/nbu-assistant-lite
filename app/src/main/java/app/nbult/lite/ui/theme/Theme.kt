package app.nbult.lite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2D6B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF151A21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151A21),
    surfaceVariant = Color(0xFFEEF2F8),
    onSurfaceVariant = Color(0xFF4A5565),
    outline = Color(0xFFB9C2D0),
    error = Color(0xFFC1272D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB0FF),
    onPrimary = Color(0xFF00264D),
    primaryContainer = Color(0xFF16365F),
    onPrimaryContainer = Color(0xFFD7E6FF),
    secondary = Color(0xFF5DCAA5),
    onSecondary = Color(0xFF04342C),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF161A20),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1E242C),
    onSurfaceVariant = Color(0xFFAAB4C2),
    outline = Color(0xFF3A424D),
    error = Color(0xFFF09595)
)

@Composable
fun NbuLiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
