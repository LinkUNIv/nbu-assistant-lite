package app.nbult.lite.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 配色表不能"只定义一半"。
 *
 * 背景：`Appearance.kt` 曾只覆盖 primary 一族与 surface/surfaceVariant/outline，
 * 剩下的 token 回落到 `lightColorScheme()` 自带的 M3 基线调色板（紫色种子），
 * 于是课程设置抽屉顶部出现粉紫横带、chip 选中态是淡紫底 —— 界面一半蓝一半紫。
 *
 * 这里钉三件事：容器阶梯不能等于基线值、chip 容器要随种子变、深浅两套都要给。
 */
class ColorSchemeTest {

    /** M3 基线（purple seed）里的几个值，回落时会原样出现。 */
    private val baselineSecondaryContainer = Color(0xFFE8DEF8)
    private val baselineSurfaceContainerLow = Color(0xFFF7F2FA)

    @Test
    fun `浅色容器阶梯不回落 M3 基线紫`() {
        val scheme = lightScheme(SeedColor.Blue)
        assertNotEquals(baselineSurfaceContainerLow, scheme.surfaceContainerLow)
        assertNotEquals(baselineSurfaceContainerLow, scheme.surfaceContainer)
        assertNotEquals(baselineSurfaceContainerLow, scheme.surfaceContainerHigh)
        assertNotEquals(baselineSurfaceContainerLow, scheme.surfaceContainerHighest)
        assertNotEquals(baselineSurfaceContainerLow, scheme.surfaceContainerLowest)
    }

    @Test
    fun `深色容器阶梯不回落 M3 基线紫`() {
        val scheme = darkScheme(SeedColor.Blue)
        assertNotEquals(Color(0xFF211F26), scheme.surfaceContainerLow)
        assertNotEquals(Color(0xFF211F26), scheme.surfaceContainerHigh)
    }

    @Test
    fun `chip 选中色由种子派生`() {
        val blue = lightScheme(SeedColor.Blue)
        val purple = lightScheme(SeedColor.Purple)
        assertNotEquals(baselineSecondaryContainer, blue.secondaryContainer)
        assertNotEquals(blue.secondaryContainer, purple.secondaryContainer)
        assertNotEquals(blue.onSecondaryContainer, purple.onSecondaryContainer)
    }

    @Test
    fun `深色 chip 选中色同样由种子派生`() {
        val blue = darkScheme(SeedColor.Blue)
        val green = darkScheme(SeedColor.Green)
        assertNotEquals(blue.secondaryContainer, green.secondaryContainer)
    }
}
