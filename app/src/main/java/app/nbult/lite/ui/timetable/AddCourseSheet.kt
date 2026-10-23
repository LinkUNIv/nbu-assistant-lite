package app.nbult.lite.ui.timetable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.WeekMask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCourseSheet(
    periods: List<PeriodTime>,
    defaultDay: Int = 1,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, Int, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(defaultDay.coerceIn(1, 7)) }
    var start by remember { mutableStateOf(1) }
    var end by remember { mutableStateOf(2) }
    var startWeek by remember { mutableStateOf(1) }
    var endWeek by remember { mutableStateOf(16) }
    var parity by remember { mutableStateOf(WeekMask.Parity.ALL) }

    val mask = WeekMask.build(startWeek, endWeek, parity)
    val startTime = periods.firstOrNull { it.idx == start }?.startTime ?: "--:--"
    val endTime = periods.firstOrNull { it.idx == end }?.endTime ?: "--:--"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("手动添加课程", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("课程名称（必填，可含课号）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text("教师") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                label = { Text("地点") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Text("星期", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (d in 1..7) {
                FilterChip(
                    selected = day == d,
                    onClick = { day = d },
                    label = { Text(DateUtil.dayNames[d - 1]) }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper("开始", start) {
                start = it.coerceIn(1, 12)
                if (start > end) end = start
            }
            Spacer(Modifier.width(12.dp))
            Stepper("结束", end) {
                end = it.coerceIn(1, 12)
                if (end < start) start = end
            }
        }
        Text(
            text = "第 $start - $end 节　$startTime - $endTime",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("周次", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper("开始周", startWeek) {
                startWeek = it.coerceIn(1, WeekMask.MAX_WEEKS)
                if (startWeek > endWeek) endWeek = startWeek
            }
            Spacer(Modifier.width(8.dp))
            Stepper("结束周", endWeek) {
                endWeek = it.coerceIn(1, WeekMask.MAX_WEEKS)
                if (endWeek < startWeek) startWeek = endWeek
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = parity == WeekMask.Parity.ALL,
                onClick = { parity = WeekMask.Parity.ALL },
                label = { Text("全部") }
            )
            FilterChip(
                selected = parity == WeekMask.Parity.ODD,
                onClick = { parity = WeekMask.Parity.ODD },
                label = { Text("单周") }
            )
            FilterChip(
                selected = parity == WeekMask.Parity.EVEN,
                onClick = { parity = WeekMask.Parity.EVEN },
                label = { Text("双周") }
            )
        }
        Text(
            text = "生效：${WeekMask.label(mask)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(Modifier.height(2.dp))
        Button(
            onClick = { onSave(name, teacher, room, day, start, end, mask) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("添加到课表") }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = { onChange(value - 1) }) {
            Icon(Icons.Outlined.Remove, contentDescription = "减少")
        }
        Text("$value", style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(value + 1) }) {
            Icon(Icons.Outlined.Add, contentDescription = "增加")
        }
    }
}
