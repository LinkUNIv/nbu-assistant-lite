package app.nbult.lite.ui.timetable

import androidx.compose.foundation.verticalScroll
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
    totalWeeks: Int,
    periods: List<PeriodTime>,
    hasOverride: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int, Int, String) -> Unit,
    onSuspend: () -> Unit,
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
    // 点开「调来的」那块时直接进调课面板 —— 它本身就是调课的结果，点它多半是想再改
    var mode by remember(entry.slot.id) {
        mutableStateOf(if (entry.movedIn) EditMode.MOVE else EditMode.NORMAL)
    }
    // 调课的目标周：默认就是本周（本周内换天），也可以往后选，用于"这周的课挪到以后补"
    var targetWeek by remember(entry.originSlot.id, week) { mutableStateOf(week) }
    val originDay = entry.originSlot.dayOfWeek
    val originStart = entry.originSlot.startSection
    val originEnd = entry.originSlot.endSection
    // 调来的块要预填「现在的位置」，否则一打开就显示的是原时段，让人以为没生效
    var moveDay by remember(entry.slot.id) {
        mutableStateOf(
            when {
                entry.movedIn -> entry.slot.dayOfWeek
                originDay == 7 -> 1
                else -> originDay + 1
            }
        )
    }
    var moveStart by remember(entry.slot.id) {
        mutableStateOf(if (entry.movedIn) entry.slot.startSection else originStart)
    }
    var moveEnd by remember(entry.slot.id) {
        mutableStateOf(if (entry.movedIn) entry.slot.endSection else originEnd)
    }
    var moveRoom by remember(entry.slot.id) { mutableStateOf(entry.slot.room) }

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
        Text(
            text = when {
                entry.movedIn -> "本次安排 · 从第 ${entry.sourceWeek} 周调来（现为第 $week 周）"
                entry.overrideId != 0L -> "本次安排（第 $week 周，已调整）"
                else -> "本次安排（第 $week 周）"
            },
            style = MaterialTheme.typography.labelLarge
        )
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
                if (entry.movedIn) {
                    Text(
                        text = "这一节是从第 ${entry.sourceWeek} 周调过来的。要改时间请切到「调整时间」；" +
                            "要改课程名、教师或周次，请在那门课的其他时段上修改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hasOverride) {
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("恢复原始安排")
                    }
                }
            }

            EditMode.SUSPEND -> {
                Text(
                    text = "只停第 $week 周这一节；课程本身的排课不变，其他周照常上课。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onSuspend,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("停掉第 $week 周这一节", fontWeight = FontWeight.Medium) }
            }

            EditMode.MOVE -> {
                TargetWeekPicker(
                    currentWeek = week,
                    totalWeeks = totalWeeks,
                    selected = targetWeek,
                    onSelect = { targetWeek = it }
                )
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
                Text(
                    text = "若目标时段已有其他课程，会先提示冲突，由你决定是否替换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { onMove(moveDay, moveStart, moveEnd, moveRoom, targetWeek) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存调课", fontWeight = FontWeight.Medium) }
            }
        }

        // ---- 课程本身的编辑（只在"正常上课"且不是调来块时展开）----
        // 调来的那一节属于"本节"，改课程名/教师会影响整门课的所有时段，
        // 不该在调课块的入口上顺手改掉。
        if (mode == EditMode.NORMAL && !entry.movedIn) {
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
 * 目标周次选择器（单选）。
 *
 * 只列「本周及之后」：调课本意是"这周的课因故不上，挪到以后补"，
 * 往过去调没有意义，也会让已经过去的周凭空多出一节课。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetWeekPicker(
    currentWeek: Int,
    totalWeeks: Int,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("调到哪一周", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val last = maxOf(totalWeeks, currentWeek)
            for (w in currentWeek..last) {
                FilterChip(
                    selected = selected == w,
                    onClick = { onSelect(w) },
                    label = {
                        Text(
                            text = if (w == currentWeek) "本周" else "第 $w 周",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }
        Text(
            text = if (selected == currentWeek) {
                "只改本周这一节，课程本身的周次设置不变"
            } else {
                "把本周这一节移到第 $selected 周；本周原位保留一条「已调至…」说明"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
