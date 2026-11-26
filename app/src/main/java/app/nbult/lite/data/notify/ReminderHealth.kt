package app.nbult.lite.data.notify

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * 提醒能不能正常响，取决于几个**应用改不了**的系统开关。
 *
 * 为什么要把它们攒成一张表：用户报"闹钟没响"时，九成不是代码 bug，而是通知权限、
 * 渠道被静音、精确闹钟被收回、省电策略限制后台 —— 每一条都在不同系统页面里。
 * 在应用内逐条给出"现在是开还是关、点哪里改"，比让用户自己找强得多。
 *
 * 只读状态，不做修改：这些开关只有系统设置能改，应用内最多跳转。
 */
internal enum class HealthState {
    /** 正常。 */
    Ok,

    /** 会影响提醒，需要用户处理。 */
    Warn,

    /** 无法检测（各 ROM 没有统一接口，例如自启动管理）。 */
    Unknown
}

internal data class HealthItem(
    /** 用于 UI 决定"点哪个按钮"，不放文案。 */
    val key: String,
    val title: String,
    val state: HealthState,
    val detail: String
)

internal object ReminderHealth {

    const val KEY_PERMISSION = "permission"
    const val KEY_CHANNEL = "channel"
    const val KEY_EXACT = "exact"
    const val KEY_BATTERY = "battery"
    const val KEY_AUTOSTART = "autostart"

    /** 每次进入页面都重算：用户常从系统设置跳回来，缓存会立刻变成假信息。 */
    fun items(context: Context): List<HealthItem> = listOf(
        permission(context),
        channel(context),
        exactAlarm(context),
        battery(context),
        autoStart()
    )

    /** 顶部一句话结论。 */
    fun summary(items: List<HealthItem>): String {
        val blocking = items.count { it.state == HealthState.Warn }
        return if (blocking == 0) "当前可以正常提醒" else "有 $blocking 项需要处理，否则可能收不到提醒"
    }

    private fun permission(context: Context): HealthItem {
        val ok = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return HealthItem(
            key = KEY_PERMISSION,
            title = "通知权限",
            state = if (ok) HealthState.Ok else HealthState.Warn,
            detail = if (ok) "已允许" else "关掉后通知不会出现，提醒等于没有"
        )
    }

    private fun channel(context: Context): HealthItem {
        if (Build.VERSION.SDK_INT < 26) {
            return HealthItem(KEY_CHANNEL, "通知渠道", HealthState.Ok, "系统版本较低，无渠道概念")
        }
        val manager = androidx.core.app.NotificationManagerCompat.from(context)
        val importance = manager.getNotificationChannel(ReminderNotifier.CHANNEL_REMINDER)?.importance
        val ok = importance != null && importance != android.app.NotificationManager.IMPORTANCE_NONE
        val state = if (ok) HealthState.Ok else HealthState.Warn
        return HealthItem(
            key = KEY_CHANNEL,
            title = "通知渠道",
            state = state,
            detail = if (ok) {
                ReminderNotifier.channelState(context)
            } else {
                "「上课提醒」渠道被关闭，通知不会显示"
            }
        )
    }

    private fun exactAlarm(context: Context): HealthItem {
        val ok = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }
        return HealthItem(
            key = KEY_EXACT,
            title = "精确闹钟",
            state = if (ok) HealthState.Ok else HealthState.Warn,
            detail = if (ok) "已允许" else "被收回时提醒会晚几分钟到十几分钟（省电策略）"
        )
    }

    private fun battery(context: Context): HealthItem {
        val power = context.getSystemService(PowerManager::class.java)
        val ignoring = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        return HealthItem(
            key = KEY_BATTERY,
            title = "电池优化",
            state = if (ignoring) HealthState.Ok else HealthState.Warn,
            detail = if (ignoring) {
                "已加入白名单"
            } else {
                "未加入白名单：锁屏久了可能被冻结，闹钟要等系统唤醒才响"
            }
        )
    }

    /**
     * 自启动管理：没有统一 API，**故意不猜**。
     *
     * 猜错的代价是让用户去改一个本来就正常的开关；标成「无法检测」并给出入口，
     * 比给一个假的绿点更诚实。
     */
    private fun autoStart(): HealthItem = HealthItem(
        key = KEY_AUTOSTART,
        title = "自启动 / 后台运行",
        state = HealthState.Unknown,
        detail = "部分机型（小米、OPPO、vivo 等）需要手动允许，否则重启后提醒会失效"
    )
}
