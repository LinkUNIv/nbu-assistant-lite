package app.nbult.lite.data.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟到点：发通知，然后把下一个提醒点排上。
 *
 * 用 [goAsync] 而不是起 Service —— 只是读一下 extras 发条通知再重排，
 * 几毫秒的事，为此拉一个前台服务不值当（还会多一条常驻通知）。
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 下课撤销闹钟：只撤常驻通知，不发提醒。
        // 它是独立于"下一个提醒点"的一条兜底，负责端掉"倒计时跑成负数"
        if (intent.action == ClassReminderScheduler.ACTION_CANCEL_ONGOING) {
            val appContext = context.applicationContext
            ReminderNotifier.cancelOngoing(appContext)
            AppLog.log("Reminder", "下课撤销闹钟触发 · 常驻通知已撤")
            // 顺手重排：万一主闹钟被系统掐掉，这一步能把下一条补回来
            ClassReminderScheduler.reschedule(appContext)
            return
        }
        if (intent.action != ClassReminderScheduler.ACTION_FIRE) return
        // 先记一条：没有这条就说明闹钟压根没触发（被系统掐了），有这条但没通知才是渲染问题
        AppLog.log(
            "Reminder",
            "闹钟触发 kind=${intent.getStringExtra(ClassReminderScheduler.EXTRA_KIND)}"
        )
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderNotifier.showFromAlarm(appContext, intent)
                ClassReminderScheduler.rescheduleNow(appContext)
            } catch (t: Throwable) {
                AppLog.log("Reminder", "触发失败: ${t.message}")
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}

/**
 * 开机 / 改时间 / 改时区 / 应用被覆盖安装 → 重新排一次。
 *
 * 这些场景下系统会清空或错位已有的闹钟，不重排就等于提醒永久失效。
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLog.log("Reminder", "收到 ${intent.action}，重排提醒")
        ClassReminderScheduler.reschedule(context)
    }
}
