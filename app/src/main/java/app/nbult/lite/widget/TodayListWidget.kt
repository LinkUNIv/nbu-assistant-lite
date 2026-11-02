package app.nbult.lite.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.ComponentName
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
    // Layout and blank-area sizing must follow the host's actual content size,
    // rather than one of three responsive size buckets.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState(context)
        provideContent {
            // 档位宽度决定用宽行还是窄列：窄列放不下「教室 + 节次」两列
            TodayListBody(refreshedWidgetState(context, state), wide = LocalSize.current.width >= 180.dp)
        }
    }
}

class TodayListReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayListWidget()

    private fun refreshIntent(context: Context): PendingIntent {
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, TodayListReceiver::class.java)
        )
        return PendingIntent.getBroadcast(
            context, 354,
            Intent(context, TodayListReceiver::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        // 非精确闹钟，无需精确闹钟权限；系统省电时可能延后刷新。
        context.getSystemService(AlarmManager::class.java).setRepeating(
            AlarmManager.RTC,
            (System.currentTimeMillis() / 60_000 + 1) * 60_000,
            60_000,
            refreshIntent(context)
        )
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
        super.onDisabled(context)
    }
}

/** 刷新全部课程显示，不启动 Activity。 */
class RefreshTodayListAction : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters
    ) {
        app.nbult.lite.util.AppLog.log("Widget", "收到手动刷新点击")
        refreshCourseDisplays(context)
    }
}
