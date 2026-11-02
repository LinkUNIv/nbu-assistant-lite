package app.nbult.lite.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.currentState
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import app.nbult.lite.NbuLiteApp
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.CancellationException

private val refreshRevision = longPreferencesKey("course_refresh_revision")

/** 活跃会话也观察持久化刷新信号，不能只依赖 provideGlance 重新启动。 */
@Composable
internal fun refreshedWidgetState(context: Context, initial: WidgetState): WidgetState {
    val revision = currentState<Preferences>()[refreshRevision] ?: 0L
    val state by produceState(initialValue = initial, key1 = revision) {
        value = loadState(context)
        AppLog.log("Widget", "已重读课程和时间，刷新序号=$revision")
    }
    return state
}

/** 重算应用内倒计时，并刷新所有已添加的课程小组件。 */
suspend fun refreshCourseDisplays(context: Context) {
    val app = context.applicationContext as? NbuLiteApp ?: return
    app.container.clockRefresh.value = System.nanoTime()
    val manager = GlanceAppWidgetManager(app)
    for (widget in listOf(TodayListWidget(), NextClassWidget())) {
        for (id in manager.getGlanceIds(widget.javaClass)) {
            try {
                updateAppWidgetState(app, id) { prefs ->
                    prefs[refreshRevision] = (prefs[refreshRevision] ?: 0L) + 1L
                }
                widget.update(app, id)
                AppLog.log("Widget", "已请求刷新：${widget.javaClass.simpleName}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.log("Widget", "刷新失败：${widget.javaClass.simpleName} ${error.message}")
            }
        }
    }
}
