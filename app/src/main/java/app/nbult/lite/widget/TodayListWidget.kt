package app.nbult.lite.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent

/**
 * 今日课程列表。
 *
 * 与 [NextClassWidget] 分开注册而不是靠尺寸分支：MIUI 只按自己选中的档位请求内容，
 * 实际拉伸后的尺寸与档位对不上，`LocalSize` 拿不到真实宽高，同一个 provider 里
 * 无法可靠地区分「显示一张卡片」与「显示一张列表」。
 *
 * 代价是小部件面板里多一个条目，换来的是两种形态各自可控。
 */
class TodayListWidget : GlanceAppWidget() {

    /**
     * 三档：4×2、2×4、4×4。
     *
     * 高度都 ≥ 110dp 偏大的一侧，避免被 launcher 拉成一张高瘦的卡片。
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 110.dp),
            DpSize(110.dp, 220.dp),
            DpSize(250.dp, 220.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState(context)
        provideContent {
            // 档位宽度决定用宽行还是窄列：窄列放不下「教室 + 节次」两列
            TodayListBody(state, wide = LocalSize.current.width >= 180.dp)
        }
    }
}

class TodayListReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayListWidget()
}
