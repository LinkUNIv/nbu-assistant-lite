package app.nbult.lite.ui.timetable

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
    totalWeeks: Int,
    periods: List<PeriodTime>,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int, Int, String) -> Unit,
    onSuspend: (Int) -> Unit,
    onMove: (Int, Int, Int, String, Int) -> Unit,
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
    // 生效周次：默认只勾当前这一周，其余靠手动勾选（原「仅本周 / 本周起每周」两档已移除）
    var overrideMask by remember(entry.originSlot.id, week) {
        mutableStateOf(WeekMask.bit(week))
    }
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
            // 周次选择器最多 30 个格子，矮屏上必须能滚，否则「保存」会被顶出屏幕
            .verticalScroll(rememberScrollState())
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
                WeekSelector(
                    mask = overrideMask,
                    totalWeeks = totalWeeks,
                    onChange = { overrideMask = it }
                )
                Button(
                    onClick = { onSuspend(overrideMask) },
                    enabled = overrideMask != 0,
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
                WeekSelector(
                    mask = overrideMask,
                    totalWeeks = totalWeeks,
                    onChange = { overrideMask = it }
                )
                Text(
                    text = "若目标时段已有其他课程，会先提示冲突，由你决定是否替换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { onMove(moveDay, moveStart, moveEnd, moveRoom, overrideMask) },
                    enabled = overrideMask != 0,
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

/**
 * 生效周次选择器。
 *
 * 取代原来的「仅本周 / 本周起每周」两档：用户要的是"这几周调课、那几周不调"，
 * 而不是一个从某周开始无限延伸的区间。连续多周本质上是改课程本身（课程设置里的周次），
 * 不该在这里用一条覆盖去近似。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekSelector(
    mask: Int,
    totalWeeks: Int,
    onChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("生效周次", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onChange(WeekMask.range(1, totalWeeks)) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("全选", style = MaterialTheme.typography.labelMedium) }
            TextButton(
                onClick = { onChange(0) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("清空", style = MaterialTheme.typography.labelMedium) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (w in 1..totalWeeks.coerceAtLeast(1)) {
                FilterChip(
                    selected = WeekMask.isOn(mask, w),
                    onClick = { onChange(mask xor WeekMask.bit(w)) },
                    label = { Text("$w", style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        Text(
            text = if (mask == 0) {
                "还没有选择周次"
            } else {
                "已选 ${WeekMask.count(mask)} 周 · ${WeekMask.label(mask)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (mask == 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.secondary
            }
        )
    }
}
