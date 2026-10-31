package app.nbult.lite.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import app.nbult.lite.ui.theme.SeedColor

/**
 * 小组件的配色。
 *
 * widget 跑在 launcher 进程里，`MaterialTheme` 不存在，`ColorScheme` 也拿不到 ——
 * 只能从 DataStore 读出主题色 id 与深浅模式，在这里重新算一遍。
 * 因此这里不是「复用」主题，而是「按同样的规则重算」，两者必须保持同步。
 */
internal data class WidgetColors(
    val background: Color,
    val title: Color,
    val body: Color,
    val accent: Color,
    /** 倒计时数字。与主题色分开：它表示「快要上课了」，需要独立于主题的紧迫感。 */
    val alert: Color,
    /** 「课程结束」这类中性状态。蓝=无事发生，与红色的紧迫感分开。 */
    val info: Color
)

internal fun widgetColors(seedId: String, dark: Boolean): WidgetColors {
    val seed = SeedColor.of(seedId)
    return if (dark) {
        WidgetColors(
            background = Color(0xFF1B1B1F),
            title = Color(0xFFE6E1E5),
            body = Color(0xFFA8A4AE),
            accent = seed.dark,
            alert = Color(0xFFFF8A80),
            info = Color(0xFF8FB8FF)
        )
    } else {
        WidgetColors(
            background = Color(0xFFFFFFFF),
            title = Color(0xFF1B1B1F),
            body = Color(0xFF5F5E5A),
            accent = seed.light,
            alert = Color(0xFFD93F3C),
            info = Color(0xFF2F6FE0)
        )
    }
}

/**
 * 深浅模式的最终取值。
 *
 * `system` 不能读应用内的设置结果 —— 那需要 Compose 环境。
 * 直接看资源配置里的 night 位，它是 launcher 当前生效的值。
 */
internal fun isDarkNow(context: Context, modeId: String): Boolean = when (modeId) {
    "dark" -> true
    "light" -> false
    else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}
