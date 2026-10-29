package app.nbult.lite.ui.timetable

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.WeekMask
import app.nbult.lite.ui.common.SectionStepper
import app.nbult.lite.ui.common.WheelPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCourseSheet(
    periods: List<PeriodTime>,
    defaultDay: Int = 1,
    totalWeeks: Int = 20,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, Int, Int, Int) -> Unit
) {
    val focusManager = LocalFocusManager.current
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
    val weekCount = minOf(
        WeekMask.MAX_WEEKS,
        maxOf(totalWeeks, startWeek, endWeek, 20)
    ).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = { Text("教师") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                label = { Text("地点") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f)
            )
        }

        Text("上课时间", style = MaterialTheme.typography.labelLarge)
        WheelPicker(
            items = DateUtil.dayNames,
            selectedIndex = (day - 1).coerceIn(0, 6),
            onSelect = { day = it + 1 }
        )
        SectionStepper("开始", start) {
            start = it.coerceIn(1, 12)
            if (start > end) end = start
        }
        SectionStepper("结束", end) {
            end = it.coerceIn(1, 12)
            if (end < start) start = end
        }
        Text(
            text = "第 $start - $end 节　$startTime - $endTime",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("周次", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WeekWheel("开始周", startWeek, weekCount, Modifier.weight(1f)) {
                startWeek = it
                if (startWeek > endWeek) endWeek = startWeek
            }
            WeekWheel("结束周", endWeek, weekCount, Modifier.weight(1f)) {
                endWeek = it
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

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onSave(name, teacher, room, day, start, end, mask) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("添加到课表") }
    }
}

/** 周次滚轮：与课程设置里的同款，标题在上、轮子在下。 */
@Composable
private fun WeekWheel(
    title: String,
    value: Int,
    weekCount: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        WheelPicker(
            items = (1..weekCount).map { "第 $it 周" },
            selectedIndex = (value - 1).coerceIn(0, weekCount - 1),
            onSelect = { onChange(it + 1) }
        )
    }
}
