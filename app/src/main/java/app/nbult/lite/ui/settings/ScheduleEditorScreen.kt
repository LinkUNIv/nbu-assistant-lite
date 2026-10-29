package app.nbult.lite.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nbult.lite.data.db.PeriodTime

/**
 * 逐节编辑作息。
 *
 * 改动先落在草稿里，点"保存"才写库 —— 每敲一下就写一次 DataStore / Room 没有意义，
 * 而且中途退出会让课表在半天的时间表上抖一下。
 *
 * 时间只在"分钟"这个粒度上有意义（课表按 section 对齐，不按秒），
 * 所以用系统时间选择器而不是让用户手打 "08:00"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    periods: List<PeriodTime>,
    onSave: (List<Pair<String, String>>) -> Unit,
    onBack: () -> Unit
) {
    // 草稿：只存 (开始, 结束) 两个字符串，节次靠下标推
    val draft = remember(periods) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(periods.map { it.startTime to it.endTime })
        }
    }
    var editingIndex by remember { mutableStateOf(-1) }
    var editingStart by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "作息时间",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { onSave(draft.toList()) },
                enabled = draft.isNotEmpty()
            ) {
                Text("保存")
            }
        }

        Text(
            text = "点任意时间修改。改完后课表的节次高度、上课提醒都按新的时间算。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(draft) { index, pair ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "第 ${index + 1} 节",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            editingIndex = index
                            editingStart = true
                        }) { Text(pair.first) }
                        Text(
                            text = "—",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            editingIndex = index
                            editingStart = false
                        }) { Text(pair.second) }
                    }
                }
            }
        }
    }

    if (editingIndex >= 0 && editingIndex < draft.size) {
        val current = draft[editingIndex]
        val raw = if (editingStart) current.first else current.second
        // key：换一节时重建 picker，否则会沿用上一次的初始值
        key(editingIndex, editingStart) {
            val picker = rememberTimePickerState(
                initialHour = raw.substringBefore(":").toIntOrNull() ?: 8,
                initialMinute = raw.substringAfter(":").toIntOrNull() ?: 0,
                is24Hour = true
            )
            AlertDialog(
                onDismissRequest = { editingIndex = -1 },
                confirmButton = {
                    TextButton(onClick = {
                        val text = "%02d:%02d".format(picker.hour, picker.minute)
                        draft[editingIndex] = if (editingStart) {
                            text to current.second
                        } else {
                            current.first to text
                        }
                        editingIndex = -1
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { editingIndex = -1 }) { Text("取消") }
                },
                text = { TimePicker(state = picker) }
            )
        }
    }
}
