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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.TimetableEntry
import app.nbult.lite.domain.WeekMask

/** 弹窗内的三种意图。 */
private enum class EditMode { NORMAL, SUSPEND, MOVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditorSheet(
    entry: TimetableEntry,
    week: Int,
    periods: List<PeriodTime>,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int, Int, String) -> Unit,
    onSuspend: (OverrideScope) -> Unit,
    onMove: (Int, Int, Int, String, OverrideScope) -> Unit,
    onRestore: () -> Unit,
    onDeleteSlot: () -> Unit,
    onDeleteCourse: () -> Unit
) {
    var name by remember(entry.slot.id) { mutableStateOf(entry.course.name) }
    var teacher by remember(entry.slot.id) { mutableStateOf(entry.course.teacher) }
    // 一律以"原始时段"为编辑基准：调课块的 entry.slot 指向目标时段，
    // 拿它当基准会把调课结果写回真实课表
    var room by remember(entry.originSlot.id) { mutableStateOf(entry.originSlot.room) }
    var day by remember(entry.originSlot.id) { mutableStateOf(entry.originSlot.dayOfWeek) }
    var start by remember(entry.originSlot.id) { mutableStateOf(entry.originSlot.startSection) }
    var end by remember(entry.originSlot.id) { mutableStateOf(entry.originSlot.endSection) }
    var startWeek by remember(entry.slot.id, entry.slot.weekMask) {
        mutableStateOf(WeekMask.startWeek(entry.slot.weekMask))
    }
    var endWeek by remember(entry.slot.id, entry.slot.weekMask) {
        mutableStateOf(WeekMask.endWeek(entry.slot.weekMask))
    }
    var parity by remember(entry.slot.id, entry.slot.weekMask) {
        mutableStateOf(WeekMask.parityOf(entry.slot.weekMask))
    }
    var confirmDeleteCourse by remember { mutableStateOf(false) }

    // 停课 / 调课的临时编辑状态
    var mode by remember(entry.slot.id) { mutableStateOf(EditMode.NORMAL) }
    var scope by remember(entry.slot.id) { mutableStateOf(OverrideScope.THIS_WEEK) }
    val originDay = entry.originSlot.dayOfWeek
    val originStart = entry.originSlot.startSection
    val originEnd = entry.originSlot.endSection
    var moveDay by remember(entry.slot.id) { mutableStateOf(if (originDay == 7) 1 else originDay + 1) }
    var moveStart by remember(entry.slot.id) { mutableStateOf(originStart) }
    var moveEnd by remember(entry.slot.id) { mutableStateOf(originEnd) }
    var moveRoom by remember(entry.slot.id) { mutableStateOf(entry.originSlot.room) }

    val mask = WeekMask.build(startWeek, endWeek, parity)
    val startTime = periods.firstOrNull { it.idx == start }?.startTime ?: "--:--"
    val endTime = periods.firstOrNull { it.idx == end }?.endTime ?: "--:--"
    val code = app.nbult.lite.parser.FieldExtractor.courseCode(name)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("课程设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭") }
        }

        // ---- 本次安排：停课 / 调课 ----
        // 语义上这是"第 N 周这一次怎么办"，与下面"课程本身怎么排"是两件事，所以放在最前面
        Text("本次安排（第 $week 周）", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = mode == EditMode.NORMAL,
                onClick = { mode = EditMode.NORMAL },
                label = { Text("正常上课") }
            )
            FilterChip(
                selected = mode == EditMode.SUSPEND,
                onClick = { mode = EditMode.SUSPEND },
                label = { Text("停课") }
            )
            FilterChip(
                selected = mode == EditMode.MOVE,
                onClick = { mode = EditMode.MOVE },
                label = { Text("调整时间") }
            )
        }

        when (mode) {
            EditMode.NORMAL -> {
                if (hasOverride) {
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("恢复原始安排")
                    }
                }
            }

            EditMode.SUSPEND -> {
                ScopeChips(scope = scope, onChange = { scope = it })
                Button(
                    onClick = { onSuspend(scope) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("设为停课", fontWeight = FontWeight.Medium) }
            }

            EditMode.MOVE -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (d in 1..7) {
                        FilterChip(
                            selected = moveDay == d,
                            onClick = { moveDay = d },
                            label = { Text(DateUtil.dayNames[d - 1]) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionStepper("开始", moveStart) {
                        moveStart = it.coerceIn(1, 12)
                        if (moveStart > moveEnd) moveEnd = moveStart
                    }
                    Spacer(Modifier.width(12.dp))
                    SectionStepper("结束", moveEnd) {
                        moveEnd = it.coerceIn(1, 12)
                        if (moveEnd < moveStart) moveStart = moveEnd
                    }
                }
                OutlinedTextField(
                    value = moveRoom,
                    onValueChange = { moveRoom = it },
                    label = { Text("调课地点（留空沿用原教室）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ScopeChips(scope = scope, onChange = { scope = it })
                Button(
                    onClick = { onMove(moveDay, moveStart, moveEnd, moveRoom, scope) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存调课", fontWeight = FontWeight.Medium) }
            }
        }

        // ---- 课程本身的编辑（只在"正常上课"下展开，避免弹窗过长）----
        if (mode == EditMode.NORMAL) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("课程名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (code.isNotBlank()) {
            Text(
                text = "课号：$code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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

        Text("上课时间", style = MaterialTheme.typography.labelLarge)
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
            SectionStepper("开始", start) {
                start = it.coerceIn(1, 12)
                if (start > end) end = start
            }
            Spacer(Modifier.width(12.dp))
            SectionStepper("结束", end) {
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
            WeekStepper("开始周", startWeek) {
                startWeek = it.coerceIn(1, WeekMask.MAX_WEEKS)
                if (startWeek > endWeek) endWeek = startWeek
            }
            Spacer(Modifier.width(8.dp))
            WeekStepper("结束周", endWeek) {
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
            text = "生效：${WeekMask.label(mask)}　｜　第 $week 周：${if (WeekMask.isOn(mask, week)) "上课" else "不上课"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onSave(name, teacher, day, start, end, mask, room) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存修改", fontWeight = FontWeight.Medium) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDeleteSlot, modifier = Modifier.weight(1f)) {
                Text("删除该时段", color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { confirmDeleteCourse = true }, modifier = Modifier.weight(1f)) {
                Text("删除整门课", color = MaterialTheme.colorScheme.error)
            }
        }
        } // end if (mode == EditMode.NORMAL)
    }

    if (confirmDeleteCourse) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCourse = false },
            title = { Text("删除整门课程？") },
            text = { Text("会删除「${entry.course.name}」的所有上课时段，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteCourse = false
                    onDeleteCourse()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCourse = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionStepper(label: String, value: Int, onChange: (Int) -> Unit) {
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

@Composable
private fun WeekStepper(label: String, value: Int, onChange: (Int) -> Unit) {
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

/** 生效范围：仅本周 / 本周起每周。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeChips(scope: OverrideScope, onChange: (OverrideScope) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = scope == OverrideScope.THIS_WEEK,
            onClick = { onChange(OverrideScope.THIS_WEEK) },
            label = { Text("仅本周") }
        )
        FilterChip(
            selected = scope == OverrideScope.FROM_NOW,
            onClick = { onChange(OverrideScope.FROM_NOW) },
            label = { Text("本周起每周") }
        )
    }
}
