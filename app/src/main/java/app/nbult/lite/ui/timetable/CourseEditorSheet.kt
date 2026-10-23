package app.nbult.lite.ui.timetable

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    onConvertToSuspend: () -> Unit,
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
            // 调来的那一节不提供「停课」：那会停掉**目标周**原计划的那节，
            // 而真正要停的是"被调走的这一节"，用下方「改为停课」表达。
            if (!entry.movedIn) {
                FilterChip(
                    selected = mode == EditMode.SUSPEND,
                    onClick = { mode = EditMode.SUSPEND },
                    label = { Text("停课") }
                )
            }
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
                // 周次与星期都换成滚轮：手指上下滑着选，比点一排 chip 快，也不用横着滚
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("调到哪一周", style = MaterialTheme.typography.labelLarge)
                        val weekCount = maxOf(totalWeeks, week) - week + 1
                        val labels = (0 until weekCount).map { offset ->
                            val w = week + offset
                            if (w == week) "本周" else "第 $w 周"
                        }
                        WheelPicker(
                            items = labels,
                            selectedIndex = (targetWeek - week).coerceIn(0, weekCount - 1),
                            onSelect = { targetWeek = week + it }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("星期", style = MaterialTheme.typography.labelLarge)
                        WheelPicker(
                            items = DateUtil.dayNames,
                            selectedIndex = (moveDay - 1).coerceIn(0, 6),
                            onSelect = { moveDay = it + 1 }
                        )
                    }
                }

                Text("第几节", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionField("开始节", moveStart, Modifier.weight(1f)) {
                        moveStart = it.coerceIn(1, 12)
                        if (moveStart > moveEnd) moveEnd = moveStart
                    }
                    SectionField("结束节", moveEnd, Modifier.weight(1f)) {
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
                    text = "若目标周那个时段已有别的课，会提示是否替换；已有本节则直接替换，不再追问。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { onMove(moveDay, moveStart, moveEnd, moveRoom, targetWeek) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存调课", fontWeight = FontWeight.Medium) }

                if (entry.movedIn) {
                    OutlinedButton(
                        onClick = onConvertToSuspend,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "改为停课（取消这次换课）",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionField("开始节", start, Modifier.weight(1f)) {
                start = it.coerceIn(1, 12)
                if (start > end) end = start
            }
            SectionField("结束节", end, Modifier.weight(1f)) {
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

/**
 * 节次输入：文本框可直接键入，两侧保留 ± 用来微调。
 *
 * 原来只有 ± 按钮，从第 3 节改到第 11 节要点 8 次；现在点开键盘直接输两位数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { onChange((value - 1).coerceIn(1, 12)) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                // 只收数字、最多两位：手滑输入字母或粘贴一长串时不会把状态写坏
                val digits = raw.filter { it.isDigit() }.take(2)
                text = digits
                digits.toIntOrNull()?.let { if (it in 1..12) onChange(it) }
            },
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { onChange((value + 1).coerceIn(1, 12)) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
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
 * 上下滑动的滚轮选择器（类似秒表的数字轮）。
 *
 * 用 [LazyColumn] + 吸附（snap）实现：停下时自动对齐到整项，中间那项加粗高亮。
 * 上下留出半屏空白，让首尾项也能滚到中线。
 *
 * 只做"一次性定位 + 向上汇报"，不回写内部滚动位置 —— 否则用户手指还没松，
 * 外部的值就把列表拽回去了。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 34.dp,
    visibleItems: Int = 3
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val half = visibleItems / 2
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, items.lastIndex)
    )
    val fling = rememberSnapFlingBehavior(listState)

    // 停下后把最靠近中线的那项汇报出去
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val extra = if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0
                    val index = (listState.firstVisibleItemIndex + extra)
                        .coerceIn(0, items.lastIndex)
                    onSelect(index)
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight * visibleItems),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemHeight * half),
            modifier = Modifier.fillMaxHeight()
        ) {
            itemsIndexed(items) { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
            }
        }
        // 中线：告诉手指"现在选的是这一格"
        HorizontalDivider(
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )
    }
}
