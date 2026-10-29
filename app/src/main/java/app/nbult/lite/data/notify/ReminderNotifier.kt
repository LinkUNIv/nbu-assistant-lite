package app.nbult.lite.data.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.nbult.lite.MainActivity
import app.nbult.lite.R
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.ReminderPlanner
import app.nbult.lite.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上课提醒的通知渲染。
 *
 * 两条提醒（T-30 / T-15）用**同一个通知 id 更新**，不另开一条 ——
 * 否则通知栏会并排躺两条内容几乎一样的课，纯噪音。
 */
object ReminderNotifier {

    const val CHANNEL_REMINDER = "class_reminder"
    private const val CHANNEL_LIVE = "class_live"
    const val EXTRA_WEEK = "week"

    private const val ID_REMINDER = 4101
    private const val ID_TEST = 4102
    private const val ID_ONGOING = 4103

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone(ReminderPlanner.ZONE)
    }
    private val dayFormat = SimpleDateFormat("M月d日", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone(ReminderPlanner.ZONE)
    }

    @Volatile
    private var channelLogged = false

    /** 实时动态的探测只记一次，避免每次上课都刷一条。 */
    @Volatile
    private var promotionLogged = false

    /**
     * 建渠道。
     *
     * **每次都按当前期望重建**，而不是"存在就跳过"：早期版本或某些 ROM 可能已经建过同名渠道，
     * 或用户把它调成了静默 —— 只在缺失时创建的话，那个静默状态会永远留着。
     * 重建对用户改过的设置无效（系统以用户为准），但至少能纠正我们自己写错的历史。
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_REMINDER,
            "上课提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "开课前 30 分钟与 15 分钟各提醒一次"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 300L, 200L, 300L)
            enableLights(true)
            setShowBadge(true)
            // 显式给声音，不依赖"IMPORTANCE_HIGH 的默认值" —— 个别 ROM 的默认值可能是空
            runCatching {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        runCatching { manager.createNotificationChannel(channel) }
        if (!channelLogged) {
            channelLogged = true
            AppLog.log("Reminder", channelState(context))
        }
    }

    /**
     * 渠道此刻的**真实**状态。
     *
     * 用户报"没有声音、没有震动、没有横幅"时，只有这行日志能区分：
     * 是我们建错渠道、还是系统/用户把它设成了静默。读的是系统里的实际值，不是我们期望的值。
     */
    fun channelState(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (Build.VERSION.SDK_INT >= 26) {
            manager?.getNotificationChannel(CHANNEL_REMINDER)
        } else {
            null
        }
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return "渠道 importance=${channel?.importance ?: -1} " +
            "声音=${if (channel?.sound != null) "有" else "无"} " +
            "震动=${channel?.shouldVibrate() ?: false} " +
            "横幅=importance>=4 权限=${if (enabled) "开" else "关"}"
    }

    /** 收到闹钟：把 extras 变成一条通知。 */
    fun showFromAlarm(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(ClassReminderScheduler.EXTRA_KIND) ?: return
        // 只有课前两档会发通知；上课/下课这两个时刻交给常驻通知
        if (kind != ClassReminderScheduler.KIND_LEAD_30 &&
            kind != ClassReminderScheduler.KIND_LEAD_15
        ) {
            return
        }
        val name = intent.getStringExtra(ClassReminderScheduler.EXTRA_NAME).orEmpty()
        if (name.isBlank()) return
        val room = intent.getStringExtra(ClassReminderScheduler.EXTRA_ROOM).orEmpty()
        val startSection = intent.getIntExtra(ClassReminderScheduler.EXTRA_START_SECTION, 0)
        val endSection = intent.getIntExtra(ClassReminderScheduler.EXTRA_END_SECTION, 0)
        val startAt = intent.getLongExtra(ClassReminderScheduler.EXTRA_START_AT, 0L)
        val week = intent.getIntExtra(EXTRA_WEEK, 0)
        val sameTime = intent.getIntExtra(ClassReminderScheduler.EXTRA_SAME_TIME, 0)

        // 时效校验：闹钟被 ROM 推迟后，标题里写死的"30 分钟后"就成了假消息。
        // 实测有一次 class_start 晚了 42 分钟才触发 —— 同一条路对 lead 提醒也成立。
        // 所以标题改用"离现在真实还剩多少分钟"，已经开课就整条跳过。
        val now = AppClock.now()
        if (startAt <= 0L) return
        if (startAt in 1L..now) {
            val lateMinutes = (now - startAt) / 60_000L
            AppLog.log("Reminder", "提醒已过期，跳过：$name 已开始 $lateMinutes 分")
            return
        }
        val minutesLeft = if (startAt > now) {
            ((startAt - now + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        } else {
            0
        }

        ensureChannel(context)

        val sections = if (startSection > 0 && endSection >= startSection) {
            if (startSection == endSection) "第 $startSection 节" else "$startSection-$endSection 节"
        } else {
            ""
        }
        // 单行只放"节次 + 开始时刻"：小米经典样式一行放不下更多，多写的部分会被截成"…"
        val detail = listOfNotNull(
            sections.takeIf { it.isNotBlank() },
            if (startAt > 0) "${clockFormat.format(Date(startAt))} 开始" else null
        ).joinToString(" · ")
        val fullDetail = listOfNotNull(
            sections.takeIf { it.isNotBlank() },
            room.takeIf { it.isNotBlank() },
            if (startAt > 0) "${clockFormat.format(Date(startAt))} 开始" else null,
            if (sameTime > 0) "另有 $sameTime 节同时开始" else null
        ).joinToString(" · ")

        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$minutesLeft 分钟后 · $name")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (startAt > 0) "${dayFormat.format(Date(startAt))} $fullDetail" else fullDetail
            ))

            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // 26+ 完全由渠道决定；这两行是给 24/25 和"拿不到渠道时"的兜底
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0L, 300L, 200L, 300L))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            // 上课后再挂着也没意义，40 分钟后自动清掉
            .setTimeoutAfter(40 * 60_000L)
            .setContentIntent(openApp(context, week))

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            AppLog.log("Reminder", "通知权限未授予，跳过本次提醒（$minutesLeft 分钟）")
            return
        }
        runCatching { manager.notify(ID_REMINDER, builder.build()) }
            .onFailure { AppLog.log("Reminder", "发通知失败: ${it.message}") }
        AppLog.log("Reminder", "已提醒 $minutesLeft 分钟 · $name $sections")
    }

    /** 立刻发一条测试通知：用来区分"代码没发出去"和"系统把它静默了"。 */
    fun showTest(context: Context) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("测试提醒")
            .setContentText("有声音、有震动、有横幅才算正常；否则请检查系统的通知渠道设置。")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "如果这条没有声音/震动/横幅，说明是系统侧把「上课提醒」这个渠道设成了静默，" +
                        "或关闭了「悬浮通知」。可在系统的应用通知设置里改，应用内改不了。"
                )
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0L, 300L, 200L, 300L))
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setContentIntent(openApp(context, 0))
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.notify(ID_TEST, builder.build()) }
            .onFailure { AppLog.log("Reminder", "测试通知发送失败: ${it.message}") }
        AppLog.log("Reminder", "已发送测试通知 · ${channelState(context)}")
    }

    /**
     * 「上课中」渠道：**DEFAULT 重要性 + 静默**，不是 LOW。
     *
     * AOSP 只禁止 `IMPORTANCE_MIN`，LOW 本身合法；但国产 ROM（ColorOS / OriginOS）
     * 会把 LOW 归进"静默通知"组，不置顶、不渲染倒计时，实时动态更不会考虑它。
     * 静默改由 `setSilent` 保证，两者不冲突。
     */
    private fun ensureLiveChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_LIVE,
            "上课中",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "上课期间显示剩余时间；静默，不响不震"
            // 静默靠"没有声音 + 不震动"，而不是把重要性压到 LOW。
            // NotificationChannel 没有 setSilent（那是 Notification.Builder 的方法）
            runCatching { setSound(null, null) }
            enableVibration(false)
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    /**
     * 上课中的常驻通知。
     *
     * 倒计时交给系统：`setUsesChronometer` + `setChronometerCountDown` + `setWhen(下课时刻)`，
     * SystemUI 每秒自绘"还剩 24:13"，我们一次都不需要唤醒 —— 比每 5 分钟刷一次省电，
     * 而且不会出现"秒数跳格"。
     */
    fun showOngoing(context: Context, cls: app.nbult.lite.domain.UpcomingClass) {
        ensureLiveChannel(context)
        val sections = if (cls.startSection == cls.endSection) {
            "第 ${cls.startSection} 节"
        } else {
            "${cls.startSection}-${cls.endSection} 节"
        }
        // 两种通知样式对文案的要求完全不同：
        // - 原生样式：能换行、会渲染 chronometer（右上角"剩 53:36"），可以放全信息；
        // - 小米经典样式：标题与内容挤在一行、超出即截断，且**不渲染 chronometer**。
        //   所以单行文本只留最短且永远正确的两条：节次 + 下课时刻（不用"剩余 55 分"这种会过期的写法）。
        val shortText = listOf(sections, "${clockFormat.format(Date(cls.endAt))} 下课")
            .joinToString(" · ")
        val fullText = listOfNotNull(
            sections,
            "${clockFormat.format(Date(cls.endAt))} 下课",
            cls.room.takeIf { it.isNotBlank() },
            if (cls.sameTimeCount > 0) "另有 ${cls.sameTimeCount} 节同时开始" else null
        ).joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在上课 · ${cls.name}")
            .setContentText(shortText)
            // 展开（原生样式）时给完整信息；经典样式只用 contentText，不受影响
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            // 不用 setSubText：原生样式会把它塞进头部，与"应用名 + 倒计时"抢位置
            // （实测挤成 "宁大助手Lit… • 北区1号教学楼… • 47:03"），且与展开正文重复；
            // 经典样式则根本不显示它。教室只放在 bigText 里。
            .setWhen(cls.endAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // 实时动态给小图标套的强调色容器默认跟着系统主题（于是成了淡黄）。
            // 这里钉成纯白：图标本身是纯白单色图标，容器也白，整块就是干净的白。
            // 注意不能开 setColorized(true)，那会直接失去提升资格。
            .setColor(android.graphics.Color.WHITE)
            .setContentIntent(openApp(context, cls.week))

        // 进度条去掉了：我们只在上课/下课各发一次通知，任何"确定的进度值"发出去就冻在那儿
        // （刚开始必然是 0%，看着更像坏了）。时间信息交给系统自己跑的倒计时（chronometer），
        // 那部分是 SystemUI 每秒重绘的，不耗我们的电，也不会过期。

        // Android 16 QPR1 起，这条通知可以申请升级成「实时动态」：
        // 常驻通知抽屉顶部、锁屏与息屏可见、状态栏胶囊。能不能真的升级由系统决定
        // （系统版本 / 权限 / 免打扰…），升不了就仍然是普通静默通知，功能不缺。
        //
        // **申请位无条件写**。之前只有 canPostPromotedNotifications() 为 true 才写，
        // 而 hasPromotableCharacteristics() 的第一条要求就是"已请求提升" ——
        // 于是"用户还没给这个应用开实时动态"和"通知本身不合格"在日志里长得一模一样，
        // 用户去系统里开了之后也得等下一次上课才生效。官方文档明确"是否提升由系统投递时决定"，
        // 多写一个 bit 没有副作用，只有好处。
        val promotion = promotionState(context)
        // 兜住：这是个 compat 方法，理论上只写 extras，但老系统上万一抛，
        // 崩的是"发通知"这条链，代价太大
        runCatching { builder.setRequestPromotedOngoing(true) }
        // 胶囊里的短文本。**刻意不写"还剩 XX 分"**：只在上下课各发一次，
        // 写死的分钟数会一直停在那儿变成假信息。"上课中"在整个课堂期间都成立。
        runCatching { builder.setShortCriticalText("上课中") }

        val notification = builder.build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_ONGOING, notification) }
            .onFailure { AppLog.log("Reminder", "常驻通知发送失败: ${it.message}") }

        // 只记一次：三项分开写，用户报"没有岛"时能直接定位卡在哪一道闸
        if (!promotionLogged) {
            promotionLogged = true
            val promotable = runCatching {
                NotificationCompat.hasPromotableCharacteristics(notification)
            }.getOrDefault(false)
            AppLog.log(
                "Reminder",
                "实时动态 申请位=true 可提升=$promotable " +
                    "允许申请=${promotion.allowed} reason=${promotion.reason} " +
                    "sdk=${Build.VERSION.SDK_INT} sdkFull=${promotion.sdkFull}"
            )
        }
    }

    /**
     * 实时动态的判定结果，三项分开记。
     *
     * 只用一个布尔的后果是：用户报"没有岛"时，我们分不清是系统版本不够、
     * 系统没实现这套 API、还是他没给这个应用开「实时动态」——
     * 而这三件事的处理方式完全不同（等升级 / 换机型 / 点一下去开启）。
     */
    private data class PromotionState(
        /** `canPostPromotedNotifications()`：该应用是否被允许提升。 */
        val allowed: Boolean,
        /** `SDK_INT_FULL`，用来区分同为 SDK 36 的 Android 16.0 与 16.1（QPR1）。 */
        val sdkFull: Int,
        /** unsupported / user_disabled / ok。 */
        val reason: String
    )

    private fun promotionState(context: Context): PromotionState {
        if (Build.VERSION.SDK_INT < 36) {
            return PromotionState(allowed = false, sdkFull = 0, reason = "unsupported")
        }
        // 探测本身在部分 ROM 上会抛（官方文档提醒过），全程兜住 —— 能力探测不该成为崩溃点
        val allowed = runCatching {
            NotificationManagerCompat.from(context).canPostPromotedNotifications()
        }.getOrDefault(false)
        val full = runCatching { Build.VERSION.SDK_INT_FULL }.getOrDefault(0)
        return PromotionState(
            allowed = allowed,
            sdkFull = full,
            reason = if (allowed) "ok" else "user_disabled"
        )
    }

    /** 供设置页判断要不要显示"去开启"——只在系统有能力时才提示。 */
    fun promotionHint(context: Context): String = promotionState(context).reason

    fun cancelOngoing(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_ONGOING) }
    }

    fun cancelAll(context: Context) {
        cancelOngoing(context)
        runCatching { NotificationManagerCompat.from(context).cancel(ID_REMINDER) }
    }

    /** 点通知 → 打开课表并定位到那一周。 */
    private fun openApp(context: Context, week: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_WEEK, week)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
