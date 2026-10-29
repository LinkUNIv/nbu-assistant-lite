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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.TimetableEntry
import app.nbult.lite.domain.WeekMask
import app.nbult.lite.ui.common.SectionStepper
import app.nbult.lite.ui.common.WheelPicker

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
    /** 调课被拒等即时错误；由 VM 维护，显示在面板内而不是 Snackbar（抽屉会挡住它）。 */
    moveError: String? = null,
    onClearMoveError: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int, Int, String) -> Unit,
    onSuspend: () -> Unit,
    onMove: (Int, Int, Int, String, Int) -> Unit,
    onConvertToSuspend: () -> Unit,
    /** 跳到这门课"原来应该在的位置"（源周的原时段块），在那里编辑整门课。 */
    onGoOrigin: () -> Unit = {},
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
    val focusManager = LocalFocusManager.current
    var confirmDeleteCourse by remember { mutableStateOf(false) }

    // 这一块是"被调整到的位置"（不是原位置）。周内调整同样算 ——
    // 之前只看 movedIn，"把课从周三挪到周四（同周）"产生的块 `movedIn` 是 false，
    // 于是点开落在「正常上课」，还把整门课的编辑表单展开，与跨周调课的观感完全两样。
    val isMovedTarget = (entry.movedIn || entry.toStart > 0) && entry.ghostLabel.isEmpty()

    // 停课 / 调课的临时编辑状态
    //
    // 初始高亮必须按**这一块现在到底是什么**来定，不能只判 movedIn：
    // 之前只看 movedIn，于是停课块和原位灰块点开都显示「正常上课」，
    // 明明已经停课/调走，面板却说一切正常。
    var mode by remember(entry.slot.id) {
        mutableStateOf(
            when {
                // 调来的、周内调过的、被调走留下的灰块，一律落在「调整时间」
                isMovedTarget -> EditMode.MOVE
                entry.suspended -> EditMode.SUSPEND
                entry.ghostLabel.isNotEmpty() -> EditMode.MOVE
                else -> EditMode.NORMAL
            }
        )
    }
    // 调课的目标周：默认就是本周（本周内换天），也可以往后选，用于"这周的课挪到以后补"
    var targetWeek by remember(entry.originSlot.id, week) {
        mutableStateOf(if (entry.toWeek > 0) entry.toWeek else week)
    }
    val originDay = entry.originSlot.dayOfWeek
    val originStart = entry.originSlot.startSection
    val originEnd = entry.originSlot.endSection
    // 调来的块要预填「现在的位置」，否则一打开就显示的是原时段，让人以为没生效
    var moveDay by remember(entry.slot.id) {
        mutableStateOf(
            when {
                // 已经有调课目标就照抄，别拿"原来那天 +1"去猜 ——
                // 灰块按猜法开会显示一个并不存在的目标时段
                entry.toDay > 0 -> entry.toDay.coerceIn(1, 7)
                entry.movedIn -> entry.slot.dayOfWeek
                originDay == 7 -> 1
                else -> originDay + 1
            }
        )
    }
    var moveStart by remember(entry.slot.id) {
        mutableStateOf(
            when {
                entry.toStart > 0 -> entry.toStart
                entry.movedIn -> entry.slot.startSection
                else -> originStart
            }
        )
    }
    var moveEnd by remember(entry.slot.id) {
        mutableStateOf(
            when {
                entry.toEnd > 0 -> entry.toEnd
                entry.movedIn -> entry.slot.endSection
                else -> originEnd
            }
        )
    }
    var moveRoom by remember(entry.slot.id) { mutableStateOf(entry.slot.room) }

    val mask = WeekMask.build(startWeek, endWeek, parity)
    val startTime = periods.firstOrNull { it.idx == start }?.startTime ?: "--:--"
    val endTime = periods.firstOrNull { it.idx == end }?.endTime ?: "--:--"
    val code = app.nbult.lite.parser.FieldExtractor.courseCode(name)
    // 周次上界：以学期周数为准，但至少留到 20 周，且不超过周次位图能表示的范围
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
            Text("课程设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭") }
        }

        // ---- 本次安排：停课 / 调课 ----
        // 语义上这是"第 N 周这一次怎么办"，与下面"课程本身怎么排"是两件事，所以放在最前面
        Text(
            text = when {
                entry.movedIn -> "本次安排 · 从第 ${entry.sourceWeek} 周调来（现为第 $week 周）"
                entry.ghostLabel.isNotEmpty() -> "本次安排 · 已从第 $week 周调走"
                isMovedTarget -> "本次安排 · 第 $week 周内已调整"
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
                selected = mode == EditMode.MOVE,
                onClick = { mode = EditMode.MOVE },
                label = { Text("调整时间") }
            )
        }

        when (mode) {
            EditMode.NORMAL -> {
                // 调整过的位置不提供整门课编辑：改课程名/教师/周次影响的是所有时段，
                // 应该回到"它本来应该在的位置"去改，这里只给一条指路
                if (isMovedTarget) {
                    Text(
                        text = "这是调整后的位置。改课程名、教师或周次，请到它原来的时段 —— " +
                            "那里编辑的才是这门课本身。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onGoOrigin, modifier = Modifier.fillMaxWidth()) {
                        Text("去原位置")
                    }
                }
                if (hasOverride) {
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("恢复原始安排")
                    }
                }
            }

            EditMode.SUSPEND -> {
                Text(
                    // 「停课」chip 已取消，这个分支只剩"本来就是停课块"一种入口，
                    // 所以文案要陈述状态，而不是教人怎么停
                    text = if (entry.suspended) {
                        "第 $week 周这一节已停课；课程本身的排课不变，其他周照常上课。"
                    } else {
                        "只停第 $week 周这一节；课程本身的排课不变，其他周照常上课。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.suspended) {
                    OutlinedButton(
                        onClick = onRestore,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("恢复第 $week 周上课", fontWeight = FontWeight.Medium) }
                } else {
                    Button(
                        onClick = onSuspend,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("停掉第 $week 周这一节", fontWeight = FontWeight.Medium) }
                }
            }

            EditMode.MOVE -> {
                // 周次与星期都换成滚轮：手指上下滑着选，比点一排 chip 快，也不用横着滚
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("调到哪一周", style = MaterialTheme.typography.labelLarge)
                        val moveWeekCount = maxOf(totalWeeks, week) - week + 1
                        val labels = (0 until moveWeekCount).map { offset ->
                            val w = week + offset
                            // 第一档显式说明"不改周次、只改时间"，
                            // 否则它看起来像要先离开本周再回来
                            if (w == week) "本周·周内调" else "第 $w 周"
                        }
                        WheelPicker(
                            items = labels,
                            selectedIndex = (targetWeek - week).coerceIn(0, moveWeekCount - 1),
                            onSelect = {
                                targetWeek = week + it
                                onClearMoveError()
                            }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("星期", style = MaterialTheme.typography.labelLarge)
                        WheelPicker(
                            items = DateUtil.dayNames,
                            selectedIndex = (moveDay - 1).coerceIn(0, 6),
                            onSelect = {
                                moveDay = it + 1
                                onClearMoveError()
                            }
                        )
                    }
                }

                Text("节次", style = MaterialTheme.typography.labelLarge)
                SectionStepper("开始", moveStart) {
                    moveStart = it.coerceIn(1, 12)
                    if (moveStart > moveEnd) moveEnd = moveStart
                    onClearMoveError()
                }
                SectionStepper("结束", moveEnd) {
                    moveEnd = it.coerceIn(1, 12)
                    if (moveEnd < moveStart) moveStart = moveEnd
                    onClearMoveError()
                }
                OutlinedTextField(
                    value = moveRoom,
                    onValueChange = { moveRoom = it },
                    label = { Text("调课地点（留空沿用原教室）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!moveError.isNullOrBlank()) {
                    Text(
                        text = moveError,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = { onMove(moveDay, moveStart, moveEnd, moveRoom, targetWeek) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (targetWeek == week) "调整到本周" else "保存调课",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 停课/取消调整统一放在底部：任何模式下都在同一位置、同一个形状，
        // 之前它是 chip（原始块）或只在调课分支里（调来块），两条路观感不一致。
        // 已停课的块在 SUSPEND 分支里给「恢复」，不重复出现。
        if (mode != EditMode.SUSPEND && !entry.suspended) {
            OutlinedButton(
                onClick = if (entry.overrideId != 0L) onConvertToSuspend else onSuspend,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        entry.movedIn || entry.ghostLabel.isNotEmpty() ->
                            "改为停课（取消这次换课）"
                        isMovedTarget -> "改为停课（取消这次调整）"
                        else -> "停掉第 $week 周这一节"
                    },
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ---- 课程本身的编辑（只在"正常上课"且不是调来块时展开）----
        // 调来的那一节属于"本节"，改课程名/教师会影响整门课的所有时段，
        // 不该在调课块的入口上顺手改掉。
        if (mode == EditMode.NORMAL && !isMovedTarget) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("课程名称") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
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
                OutlinedButton(
                    onClick = { confirmDeleteCourse = true },
                    modifier = Modifier.weight(1f)
                ) {
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

/** 周次滚轮：标题在上、轮子在下，两个并排时各自只占半屏，所以文字用 labelLarge。 */
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
