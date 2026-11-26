package app.nbult.lite.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.nbult.lite.data.notify.HealthItem
import app.nbult.lite.data.notify.HealthState
import app.nbult.lite.data.notify.ReminderHealth
import app.nbult.lite.data.notify.ReminderNotifier

/**
 * 提醒自检：把「为什么闹钟没响」拆成五项可以直接看懂的开关。
 *
 * 为什么单独一页而不是塞进「上课提醒」卡：这五项分布在四五个系统页面里，
 * 用户需要的是一个清单和结论，而不是在两张卡之间来回找。
 *
 * **每次回到页面都重算** —— 用户十有八九是刚从系统设置跳回来的，
 * 缓存会让绿点立刻变成假的。
 */
@Composable
internal fun ReminderHealthSection() {
    val context = LocalContext.current
    var items by remember { mutableStateOf(ReminderHealth.items(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        items = ReminderHealth.items(context)
    }
    val blocking = items.count { it.state == HealthState.Warn }

    SettingCard(title = "提醒自检") {
        Text(
            text = ReminderHealth.summary(items),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (blocking == 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Text(
            text = "这些开关只有系统设置能改。逐条对着看，缺哪项点哪项。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        items.forEach { item ->
            HealthRow(item)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                ReminderNotifier.showTest(context)
                items = ReminderHealth.items(context)
            }) { Text("发送测试通知") }
        }
    }
}

@Composable
private fun HealthRow(item: HealthItem) {
    val context = LocalContext.current
    val (mark, color) = when (item.state) {
        HealthState.Ok -> "✓" to MaterialTheme.colorScheme.primary
        HealthState.Warn -> "!" to MaterialTheme.colorScheme.error
        HealthState.Unknown -> "?" to MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = mark,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.width(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = item.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 状态为「正常」时不放按钮：一排「去设置」只会让人以为还有事没做
        if (item.state != HealthState.Ok) {
            TextButton(onClick = { act(item.key, context) }) {
                Text(if (item.state == HealthState.Unknown) "去看看" else "去设置")
            }
        }
    }
}

private fun act(key: String, context: android.content.Context) {
    when (key) {
        ReminderHealth.KEY_PERMISSION -> ReminderIntents.openNotificationSettings(context)
        ReminderHealth.KEY_CHANNEL -> ReminderIntents.openChannelSettings(context)
        ReminderHealth.KEY_EXACT -> ReminderIntents.requestExactAlarm(context)
        ReminderHealth.KEY_BATTERY -> ReminderIntents.openBatterySettings(context)
        ReminderHealth.KEY_AUTOSTART -> ReminderIntents.openAutoStartSettings(context)
    }
}
