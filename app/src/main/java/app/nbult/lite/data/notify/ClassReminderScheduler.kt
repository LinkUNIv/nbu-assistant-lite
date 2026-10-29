package app.nbult.lite.data.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import app.nbult.lite.NbuLiteApp
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.ReminderPlanner
import app.nbult.lite.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 上课提醒的排程：**永远只挂一个闹钟**，指向"下一个该响的时刻"。
 *
 * 为什么不是一次排满整学期：
 * 1. 课表随时会变（导入、编辑、停课、调课），排满之后每一次改动都要清一大片；
 * 2. 排满意味着几百个 PendingIntent，Doze 下的配额和内存都不划算；
 * 3. 单闹钟 + 每次响完重新算，天然处理跨天、跨周、跳过长假与无课日。
 *
 * 重排时机：开机、改时间/时区、App 启动、课表变更、每次闹钟触发后、设置变更。
 */
object ClassReminderScheduler {

    const val ACTION_FIRE = "app.nbult.lite.action.CLASS_REMINDER"

    /** 下课撤销闹钟专用：只负责把常驻通知撤掉，不发任何提醒。 */
    const val ACTION_CANCEL_ONGOING = "app.nbult.lite.action.CANCEL_ONGOING"

    const val EXTRA_KIND = "kind"
    const val EXTRA_NAME = "name"
    const val EXTRA_ROOM = "room"
    const val EXTRA_START_SECTION = "start_section"
    const val EXTRA_END_SECTION = "end_section"
    const val EXTRA_START_AT = "start_at"
    const val EXTRA_END_AT = "end_at"
    const val EXTRA_SAME_TIME = "same_time"

    const val KIND_LEAD_30 = "lead30"
    const val KIND_LEAD_15 = "lead15"
    const val KIND_START = "class_start"
    const val KIND_END = "class_end"

    private const val REQUEST_CODE = 7103
    private const val REQUEST_CODE_END = 7104

    /** 500ms 内的重复重排合并掉，见 [reschedule]。 */
    private val lastRescheduleAt = AtomicLong(0L)

    /** 常驻通知当前是否已经挂着；只用于"发出/撤下"各记一条日志，避免每次重排都刷屏。 */
    @Volatile
    private var ongoingShown = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone(ReminderPlanner.ZONE)
    }

    /** 供 UI/广播直接调用：不阻塞调用方。 */
    fun reschedule(context: Context) {
        // 启动时 LaunchedEffect 和 settings 流会几乎同时触发一次，
        // 实测日志里出现过相隔 4 毫秒的两条"下一次=…"，重设同一个 PendingIntent
        // 本身无害，但日志会看不清到底排了几次。600ms 窗口足够吞掉这种抖动
        val stamp = SystemClock.elapsedRealtime()
        val last = lastRescheduleAt.get()
        if (stamp - last < 600L) return
        if (!lastRescheduleAt.compareAndSet(last, stamp)) return

        val appContext = context.applicationContext
        scope.launch {
            runCatching { rescheduleNow(appContext) }
                .onFailure { AppLog.log("Reminder", "重排失败: ${it.message}") }
        }
    }

    suspend fun rescheduleNow(context: Context) {
        val appContext = context.applicationContext
        val container = (appContext as NbuLiteApp).container
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(appContext)
        alarmManager?.cancel(pending)

        if (!container.settings.classReminder.first()) {
            ReminderNotifier.cancelAll(appContext)
            ongoingShown = false
            AppLog.log("Reminder", "提醒已关闭，取消全部闹钟")
            return
        }

        val snapshot = container.timetableRepository.observeSnapshot().first()
        val term = snapshot.term ?: run {
            AppLog.log("Reminder", "没有学期信息，暂不排提醒")
            return
        }
        val now = AppClock.now()

        // 上课中的常驻通知：与"下一节课"是两件事，但走同一条重排流程，
        // 这样"App 中途启动""下课闹钟被系统掐掉"都能自己恢复/自愈。
        val liveEnabled = container.settings.classLive.first()
        val ongoing = ReminderPlanner.currentClass(
            snapshot.entries, snapshot.overrides, snapshot.periods, term, now
        )
        if (liveEnabled && ongoing != null) {
            ReminderNotifier.showOngoing(appContext, ongoing)
            // 独立的下课撤销闹钟，不参与"下一个提醒点"的竞争。
            // 之前完全指望下一次重排来撤通知，而候选里从来没有当前这节课的 endAt，
            // 于是每次下课都要挂到下一个提醒点才撤，倒计时一路跑成负数
            scheduleEndGuard(appContext, ongoing.endAt)
            if (!ongoingShown) {
                val leftMinutes = ((ongoing.endAt - now) / 60_000L).coerceAtLeast(0)
                AppLog.log("Reminder", "常驻通知已发 · ${ongoing.name} 剩余 $leftMinutes 分")
            }
            ongoingShown = true
        } else {
            // 不能再用 ongoingShown 当门：它是进程内变量，进程被杀重启后回到 false，
            // 上一次进程留下的那条通知就永远撤不掉，倒计时一直往负数跑。
            // cancel 本身幂等，多调一次的代价可以忽略
            ReminderNotifier.cancelOngoing(appContext)
            cancelEndGuard(appContext)
            if (ongoingShown) {
                AppLog.log("Reminder", "常驻通知已撤")
            }
            ongoingShown = false
        }
        val next = ReminderPlanner.next(
            entries = snapshot.entries,
            overrides = snapshot.overrides,
            periods = snapshot.periods,
            term = term,
            now = now
        ) ?: run {
            AppLog.log("Reminder", "往后 21 天没有课，暂不排提醒")
            return
        }

        // 四类时刻：T-30 / T-15 / 上课 / 下课。取"还没过去"里最早的那个。
        val candidates = listOf(
            next.startAt - ReminderPlanner.LEAD_30 to KIND_LEAD_30,
            next.startAt - ReminderPlanner.LEAD_15 to KIND_LEAD_15,
            next.startAt to KIND_START,
            next.endAt to KIND_END
        ).filter { it.first > now + 1_000L }.sortedBy { it.first }

        val (at, kind) = candidates.firstOrNull() ?: run {
            AppLog.log("Reminder", "这一节的所有提醒时刻都已过去，等下一节课")
            return
        }

        val intent = Intent(appContext, ClassReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_NAME, next.name)
            putExtra(EXTRA_ROOM, next.room)
            putExtra(EXTRA_START_SECTION, next.startSection)
            putExtra(EXTRA_END_SECTION, next.endSection)
            putExtra(EXTRA_START_AT, next.startAt)
            putExtra(EXTRA_END_AT, next.endAt)
            putExtra(EXTRA_SAME_TIME, next.sameTimeCount)
            putExtra(ReminderNotifier.EXTRA_WEEK, next.week)
        }
        val pi = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exact = Build.VERSION.SDK_INT < 31 || (alarmManager?.canScheduleExactAlarms() == true)
        val fired = runCatching {
            if (exact) {
                alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.isSuccess
        if (!fired) {
            AppLog.log("Reminder", "闹钟设置失败（系统拒绝）")
            return
        }
        AppLog.log(
            "Reminder",
            "下一次=${logFormat.format(Date(at))} [$kind] · 第${next.week}周 ${next.name} " +
                "${next.startSection}-${next.endSection}节 ${logFormat.format(Date(next.startAt))} " +
                "· 精确=$exact"
        )
    }

    /**
     * 下课撤销闹钟。
     *
     * 用 `setAlarmClock`：系统不会拿它去做 Doze 或厂商省电的批量推迟。代价是
     * 状态栏会出现一个闹钟图标，从设置一直挂到触发（一节课那么久）。
     * 只有这一条值得付这个代价 —— 它负责清掉"倒计时跑成负数"，晚一分钟都是
     * 肉眼可见的错误；lead30/lead15 迟到几分钟无所谓，有下面的时效校验兜着。
     */
    private fun scheduleEndGuard(context: Context, endAt: Long) {
        val at = endAt + 1_000L
        if (at <= AppClock.now()) return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = endGuardIntent(context)
        val viaAlarmClock = runCatching {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi)
        }.isSuccess
        if (!viaAlarmClock) {
            // 没有精确闹钟权限时 setAlarmClock 会抛，退回到 exact
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            AppLog.log("Reminder", "下课撤销闹钟退化为 exact（无闹钟权限）")
        }
    }

    private fun cancelEndGuard(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(endGuardIntent(context)) }
    }

    private fun endGuardIntent(context: Context): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).apply {
            action = ACTION_CANCEL_ONGOING
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_END,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 用户关掉开关时调用：取消闹钟与残留通知。 */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(pendingIntent(appContext))
        cancelEndGuard(appContext)
        ReminderNotifier.cancelAll(appContext)
        AppLog.log("Reminder", "已取消全部提醒")
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).apply {
            action = ACTION_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
