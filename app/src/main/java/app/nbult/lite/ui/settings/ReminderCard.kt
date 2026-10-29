package app.nbult.lite.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.data.notify.ClassReminderScheduler
import app.nbult.lite.data.notify.ReminderNotifier
import app.nbult.lite.ui.LocalAppContainer
import app.nbult.lite.ui.common.SmoothSwitch
import kotlinx.coroutines.launch

/**
 * 上课提醒卡片。
 *
 * 开关默认关。开启时按顺序处理两道门槛：
 * 1. 通知权限（API 33+ 运行时权限）——没有它通知根本不会出现，所以先申请，拒绝就不开；
 * 2. 精确闹钟（API 31+ 用户可关）——没有它只能退成不精确闹钟，会晚几分钟，但不阻止开启。
 *
 * 小米/红米还需要用户手动允许「自启动」并把省电策略改成「无限制」，否则闹钟会被系统掐掉；
 * 这一点在卡片里明说，并给一个直达入口。
 */
@Composable
fun ReminderCard() {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val settings = container.settings
    val scope = rememberCoroutineScope()

    val enabled by settings.classReminder.collectAsStateWithLifecycle(
        initialValue = settings.lastKnownClassReminder
    )
    var notificationsOk by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var exactOk by remember { mutableStateOf(canScheduleExact(context)) }
    var channelState by remember { mutableStateOf(ReminderNotifier.channelState(context)) }
    val liveEnabled by settings.classLive.collectAsStateWithLifecycle(
        initialValue = settings.lastKnownClassLive
    )
    var awaitingPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsOk = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (awaitingPermission) {
            awaitingPermission = false
            // 拒绝了就不把开关打开：否则界面显示"已开"，实际一条都发不出来
            if (granted) {
                // 必须"先写设置再排程"，且放在同一个协程里：
                // 分两处发起的话，排程那边读到的可能还是旧值（false），转头就把刚排的闹钟取消了
                scope.launch {
                    settings.setClassReminder(true)
                    ClassReminderScheduler.rescheduleNow(context)
                }
            }
        }
    }

    // 从系统设置页返回时刷新状态，避免"我明明开了它却还说没开"
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsOk = NotificationManagerCompat.from(context).areNotificationsEnabled()
        exactOk = canScheduleExact(context)
        channelState = ReminderNotifier.channelState(context)
    }

    fun turnOn() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsOk) {
            awaitingPermission = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        scope.launch {
            settings.setClassReminder(true)
            ClassReminderScheduler.rescheduleNow(context)
        }
    }

    fun turnOff() {
        scope.launch {
            settings.setClassReminder(false)
            ClassReminderScheduler.cancel(context)
        }
    }

    SettingCard(title = "上课提醒") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("开启上课提醒", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "开课前 30 分钟与 15 分钟各提醒一次。视机型情况无法完全保证准时。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SmoothSwitch(
                checked = enabled,
                onCheckedChange = { on -> if (on) turnOn() else turnOff() }
            )
        }

        // 子开关：默认关 —— 常驻通知会一直占着通知栏，得用户自己点头
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("上课中显示剩余时间", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "显示当前课程剩余时间，开启后于通知栏挂起。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SmoothSwitch(
                checked = liveEnabled,
                enabled = enabled,
                onCheckedChange = { on ->
                    scope.launch {
                        settings.setClassLive(on)
                        ClassReminderScheduler.rescheduleNow(context)
                    }
                }
            )
        }

        if (!notificationsOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "通知权限未授予，提醒不会出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                        )
                    }
                }) { Text("去开启") }
            }
        }

        if (!exactOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "精确闹钟受限，提醒可能晚几分钟（系统省电策略导致）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 31) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                }) { Text("去允许") }
            }
        }

        // 渠道真实状态回显：没有声音/震动/横幅时，这一行就是判断依据
        Text(
            text = "通知渠道：$channelState",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                ReminderNotifier.showTest(context)
                channelState = ReminderNotifier.channelState(context)
            }) { Text("发送测试通知") }
            TextButton(onClick = { openChannelSettings(context) }) { Text("通知样式设置") }
        }
        // 自启动 / 电池策略不在这里了 —— 挪进下面独立的「允许后台运行」卡片，
        // 与实时动态那张卡里的同类开关合并，避免同一个诉求分散在两个地方
    }
}

private fun canScheduleExact(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    } else {
        true
    }

/** 直达「上课提醒」这个渠道的设置页：静默/悬浮/声音都是在这里改的，应用内改不了。 */
private fun openChannelSettings(context: android.content.Context) {
    val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, ReminderNotifier.CHANNEL_REMINDER)
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

/** 先试小米的自启动页，进不去就退到应用详情 —— 各机型入口差异太大，不能硬跳。 */
private fun openAutoStartSettings(context: android.content.Context) {
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

/**
 * 实时动态的状态与跳转。
 *
 * 系统层面的两个开关（每个应用的「实时动态」、电池白名单）我们改不了，
 * 但至少要让用户一步点到正确的位置 —— 否则他只会看到"没有岛"然后以为坏了。
 *
 * 文案刻意留了"设置完也不能保证"：这是灵动蓝盒那类工具的做法，
 * 与其让人反复试，不如先把预期说清楚。
 */
@Composable
fun LiveUpdateStateCard() {
    val context = LocalContext.current
    // 从系统设置返回时要重算：用户可能刚把开关打开
    var hint by remember { mutableStateOf(ReminderNotifier.promotionHint(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hint = ReminderNotifier.promotionHint(context)
    }

    SettingCard(title = "实时动态") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (hint) {
                        "ok" -> "已开启"
                        "unsupported" -> "当前机型不支持"
                        else -> "未开启"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = when (hint) {
                        "ok" ->
                            "上课中的常驻通知会以胶囊形式出现在状态栏、锁屏与通知栏顶部。"
                        "unsupported" ->
                            "实时动态要 Android 16 及以上。低于此版本只有普通常驻通知，" +
                                "倒计时照样走，功能不缺。"
                        else ->
                            "系统允许应用把常驻通知提升为实时动态，但需要你手动打开一次。" +
                                "打开后下一节课生效，不用重装。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hint != "ok" && hint != "unsupported") {
                TextButton(onClick = { openPromotionSettings(context) }) { Text("去开启") }
            }
        }
    }
}

/**
 * 「允许后台运行」：自启动与电池策略两个开关。
 *
 * 两者都是「让系统别掐掉闹钟」，但入口在系统设置里是两个不同的页面 ——
 * 自启动在各家 ROM 的权限中心，电池策略在系统的电池优化列表。所以拆两行各自标注类型，
 * 免得点了「去设置」发现跳到的不是刚才那一项。
 */
@Composable
fun BackgroundRunCard() {
    val context = LocalContext.current
    SettingCard(title = "允许后台运行") {
        Text(
            text = "设置后可提高提醒准时概率，无法保证完全准时。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PermissionRow(
            title = "自启动设置",
            subtitle = "允许应用开机自启、不被清理（小米 / 红米等在权限中心）",
            onClick = { openAutoStartSettings(context) }
        )
        PermissionRow(
            title = "电池策略设置",
            subtitle = "加入电池优化白名单，避免闹钟被当作后台活动掐掉",
            onClick = { openBatterySettings(context) }
        )
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClick) { Text("去设置") }
    }
}

/**
 * 「实时动态」这个应用级开关在系统设置里的位置。
 *
 * 用字面量而不是 `Settings.ACTION_...`：该 action 在 API 36 才加入，
 * 而个别 ROM 上这个页面根本不存在（官方文档也提醒"可能没有对应 Activity"），
 * 所以必须 try-catch 并退到应用通知设置页。
 */
private fun openPromotionSettings(context: android.content.Context) {
    val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    if (Build.VERSION.SDK_INT >= 36) {
        val promotion = Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(promotion) }
            .onFailure { runCatching { context.startActivity(appIntent) } }
    } else {
        runCatching { context.startActivity(appIntent) }
    }
}

/** 电池优化白名单：直接弹系统对话框；弹不出来就退到自启动页。 */
private fun openBatterySettings(context: android.content.Context) {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(request) }
        .onFailure { openAutoStartSettings(context) }
}
