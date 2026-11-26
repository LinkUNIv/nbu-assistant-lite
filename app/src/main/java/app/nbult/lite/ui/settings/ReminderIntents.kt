package app.nbult.lite.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 提醒相关的系统设置跳转。
 *
 * 抽成一处的原因：这些 intent 在「上课提醒」「允许后台运行」「提醒自检」三张卡里都要用，
 * 各写一份的话，改一个 action 就要改三处；而且这类跳转**必须**带 fallback ——
 * 各家 ROM 常年裁掉某些页面，硬跳会直接抛 ActivityNotFoundException。
 */
internal object ReminderIntents {

    /** 应用通知设置页。 */
    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        }
    }

    /** 直达「上课提醒」渠道：静默 / 悬浮 / 声音都在这里改，应用内改不了。 */
    fun openChannelSettings(context: Context) {
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, app.nbult.lite.data.notify.ReminderNotifier.CHANNEL_REMINDER)
        }
        val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { context.startActivity(channelIntent) }
                .onFailure { runCatching { context.startActivity(appIntent) } }
        } else {
            runCatching { context.startActivity(appIntent) }
        }
    }

    /** 申请精确闹钟。Android 12 起需要单独授权，被收回就只能走不精确闹钟。 */
    fun requestExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    /** 电池优化白名单：直接弹系统对话框；弹不出来就退到自启动页。 */
    fun openBatterySettings(context: Context) {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(request) }
            .onFailure { openAutoStartSettings(context) }
    }

    /** 先试小米的自启动页，进不去就退到应用详情 —— 各机型入口差异太大，不能硬跳。 */
    fun openAutoStartSettings(context: Context) {
        val miui = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(miui) }
            .onFailure { runCatching { context.startActivity(fallback) } }
    }
}
