package app.nbult.lite.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.data.db.SlotOverride
import app.nbult.lite.data.db.Term
import app.nbult.lite.data.remote.TermStartResolver
import app.nbult.lite.data.repo.CalendarRepository
import app.nbult.lite.data.repo.TimetableRepository
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.CalendarDates
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.ScheduleService
import app.nbult.lite.domain.TimetableEntry
import app.nbult.lite.domain.WeekMask
import app.nbult.lite.parser.ParseResult
import app.nbult.lite.parser.TimetableParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TimetableUiState(
    val loading: Boolean = true,
    val term: Term? = null,
    val entries: List<TimetableEntry> = emptyList(),
    val periods: List<PeriodTime> = emptyList(),
    val overrides: List<SlotOverride> = emptyList(),
    /** 索引 0 对应第 1 周；已叠加停课/调课，界面直接用，不再自己过滤。 */
    val weeks: List<List<TimetableEntry>> = emptyList(),
    val week: Int = 1,
    val todayEntries: List<TimetableEntry> = emptyList(),
    val todayIsTomorrow: Boolean = false,
    val preview: ParseResult? = null,
    val previewError: String? = null,
    val importing: Boolean = false,
    val message: String? = null,
    val selected: TimetableEntry? = null,
    /** 非空表示有调课在等用户处理冲突。 */
    val pendingMove: PendingMove? = null
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val totalSections: Int get() = ScheduleService.totalSections(entries, overrides)
    val currentWeekEntries: List<TimetableEntry>
        get() = weeks.getOrElse(week - 1) { emptyList() }
}

/** 调课冲突：目标周的那一段已被哪门课占了。 */
data class MoveConflict(
    val slotId: Long,
    val courseName: String
)

/**
 * 一次等待用户裁决的调课。
 *
 * 只在检测到冲突时产生：先不落库，等用户选「替换原课程」或「取消更改」。
 * 源周恒为发起操作时所在的那一周（调课只影响本周这一节）。
 */
data class PendingMove(
    val slotId: Long,
    val sourceWeek: Int,
    val toWeek: Int,
    val day: Int,
    val start: Int,
    val end: Int,
    val room: String,
    /** 正在被修改的那条 override；0 表示新建。再次调课时用它做"改哪一条"的依据。 */
    val editingOverrideId: Long,
    /**
     * 目标周本来就有这一节（同一条排课）。
     *
     * 这时有两种处理：补课（那周上两次）或替换（那周只上一次）。
     * 以前这里硬编码成"补课"，于是用户看到两节以为是重复。
     */
    val sameSlotInTarget: Boolean,
    val conflicts: List<MoveConflict>
)

class TimetableViewModel(
    private val repository: TimetableRepository,
    private val settings: app.nbult.lite.data.SettingsStore,
    private val calendarRepository: CalendarRepository,
    private val termStartResolver: TermStartResolver
) : ViewModel() {

    private val _state = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = _state.asStateFlow()

    private var weekPickedByUser = false

    init {
        viewModelScope.launch {
            repository.observeSnapshot().collect { snapshot ->
                val week = snapshot.term?.let { ScheduleService.currentWeek(it) } ?: 1
                val totalWeeks = (snapshot.term?.totalWeeks ?: 20).coerceAtLeast(1)
                // 每周的课程在这里一次算好（含停课/调课），滑动时不再重复过滤，也不重复查覆盖表
                val weeks = (1..totalWeeks).map { w ->
                    ScheduleService.weekEntries(snapshot.entries, w, snapshot.overrides)
                }
                val plan = ScheduleService.planForToday(
                    snapshot.entries,
                    snapshot.term,
                    snapshot.periods,
                    snapshot.overrides
                )
                val current = (_state.value.week).coerceIn(1, totalWeeks)
                _state.value = _state.value.copy(
                    loading = false,
                    term = snapshot.term,
                    entries = snapshot.entries,
                    periods = snapshot.periods,
                    overrides = snapshot.overrides,
                    weeks = weeks,
                    week = if (weekPickedByUser) current else week.coerceIn(1, totalWeeks),
                    todayEntries = plan.entries,
                    todayIsTomorrow = plan.isTomorrow
                )
            }
        }
        viewModelScope.launch { bootstrapTermStart() }
    }

    /**
     * 学期开始时间的取数顺序：校历（最权威）> 新闻识别 > 手动设置。
     * 手动设置后不再自动覆盖。
     */
    private suspend fun bootstrapTermStart() {
        if (settings.termManual.first()) return
        val today = AppClock.todayEpochDay()
        val calendarStart = runCatching {
            calendarRepository.load().yearOf(today)?.semesterOf(today)?.startDay
        }.getOrNull()
        if (calendarStart != null && calendarStart > 0L) {
            applyTermStart(calendarStart, "校历")
            return
        }
        val autoStart = settings.autoTermStart.first()
        if (autoStart != 0L) {
            applyTermStart(autoStart, null)
        }
    }

    private suspend fun applyTermStart(epochDay: Long, source: String?) {
        val term = repository.currentTerm() ?: return
        if (term.startEpochDay != epochDay) {
            repository.updateTerm(term.copy(startEpochDay = epochDay))
        }
        if (source != null) settings.setAutoTermStart(epochDay, source)
        weekPickedByUser = false
    }

    /** 设置页「自动识别」：先查校历，查不到再退回新闻识别。 */
    fun refreshTermStart() {
        viewModelScope.launch {
            val today = AppClock.todayEpochDay()
            val semester = runCatching {
                calendarRepository.load().yearOf(today)?.semesterOf(today)
            }.getOrNull()
            if (semester != null) {
                applyTermStart(semester.startDay, "校历 ${semester.name}")
                _state.value = _state.value.copy(
                    message = "已按校历设置开学时间：${CalendarDates.monthDay(semester.startDay)}"
                )
                return@launch
            }
            termStartResolver.resolve()
                .onSuccess { guess ->
                    if (guess == null) {
                        _state.value = _state.value.copy(
                            message = "校历与新闻均未找到开学信息，请手动设置"
                        )
                    } else {
                        applyTermStart(guess.startEpochDay, guess.sourceTitle)
                        _state.value = _state.value.copy(
                            message = "已按新闻识别设置：${CalendarDates.monthDay(guess.startEpochDay)}"
                        )
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(message = "识别失败：${it.message}")
                }
        }
    }

    fun shiftWeek(delta: Int) {
        val term = _state.value.term ?: return
        val target = (_state.value.week + delta).coerceIn(1, term.totalWeeks)
        weekPickedByUser = true
        _state.value = _state.value.copy(week = target)
    }

    /** 供左右滑动直接设置周次（越界自动收敛）。 */
    fun setWeek(week: Int) {
        val term = _state.value.term ?: return
        val target = week.coerceIn(1, term.totalWeeks.coerceAtLeast(1))
        if (target == _state.value.week) return
        weekPickedByUser = true
        _state.value = _state.value.copy(week = target)
    }

    fun jumpToCurrentWeek() {
        val term = _state.value.term ?: return
        weekPickedByUser = false
        _state.value = _state.value.copy(week = ScheduleService.currentWeek(term))
    }

    fun preview(raw: String) {
        val result = runCatching { TimetableParser.parse(raw) }
        _state.value = result.fold(
            onSuccess = { _state.value.copy(preview = it, previewError = null) },
            onFailure = { _state.value.copy(preview = null, previewError = it.message ?: "解析失败") }
        )
    }

    fun previewFile(fileName: String, bytes: ByteArray) {
        val result = runCatching { TimetableParser.parseFile(fileName, bytes) }
        _state.value = result.fold(
            onSuccess = { _state.value.copy(preview = it, previewError = null) },
            onFailure = { _state.value.copy(preview = null, previewError = it.message ?: "解析失败") }
        )
    }

    fun clearPreview() {
        _state.value = _state.value.copy(preview = null, previewError = null)
    }

    fun confirmImport() {
        val preview = _state.value.preview ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            runCatching {
                val startEpochDay = _state.value.term?.startEpochDay
                    ?: DateUtil.mondayOfWeek(DateUtil.todayEpochDay())
                val totalWeeks = _state.value.term?.totalWeeks ?: 20
                repository.importResult(
                    parsed = preview,
                    termName = "当前学期",
                    startEpochDay = startEpochDay,
                    totalWeeks = totalWeeks,
                    replace = true
                )
            }.onSuccess { count ->
                _state.value = _state.value.copy(
                    importing = false,
                    preview = null,
                    message = "已导入 $count 个上课时段"
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    importing = false,
                    message = it.message ?: "导入失败"
                )
            }
        }
    }

    fun updateTermStart(epochDay: Long) {
        viewModelScope.launch {
            val term = _state.value.term ?: return@launch
            repository.updateTerm(term.copy(startEpochDay = epochDay))
            settings.setTermManual(true)
            weekPickedByUser = false
        }
    }

    fun applyAutoTermStart(epochDay: Long, source: String) {
        viewModelScope.launch {
            val term = _state.value.term
            if (term != null) {
                repository.updateTerm(term.copy(startEpochDay = epochDay))
            }
            settings.setAutoTermStart(epochDay, source)
            weekPickedByUser = false
            _state.value = _state.value.copy(message = "开学时间已按官方信息更新")
        }
    }

    fun addCourse(
        name: String,
        teacher: String,
        room: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        weekMask: Int
    ) {
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "请填写课程名称")
            return
        }
        viewModelScope.launch {
            val termId = _state.value.term?.id
                ?: repository.ensureTerm(
                    name = "当前学期",
                    startEpochDay = DateUtil.mondayOfWeek(DateUtil.todayEpochDay()),
                    totalWeeks = 20
                )
            repository.addCourse(
                termId = termId,
                name = name.trim(),
                teacher = teacher.trim(),
                room = room.trim(),
                dayOfWeek = dayOfWeek.coerceIn(1, 7),
                startSection = minOf(startSection, endSection).coerceIn(1, 12),
                endSection = maxOf(startSection, endSection).coerceIn(1, 12),
                weekMask = weekMask
            )
            _state.value = _state.value.copy(message = "已添加课程")
        }
    }

    fun updateTotalWeeks(totalWeeks: Int) {
        viewModelScope.launch {
            val term = _state.value.term ?: return@launch
            repository.updateTerm(term.copy(totalWeeks = totalWeeks.coerceIn(1, 30)))
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
            _state.value = _state.value.copy(message = "课表已清空")
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun select(entry: TimetableEntry?) {
        _state.value = _state.value.copy(selected = entry)
    }

    fun saveSlot(
        courseName: String,
        teacher: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        weekMask: Int,
        room: String
    ) {
        val entry = _state.value.selected ?: return
        viewModelScope.launch {
            val start = minOf(startSection, endSection)
            val end = maxOf(startSection, endSection)
            repository.updateSlot(
                entry.originSlot.copy(
                    dayOfWeek = dayOfWeek.coerceIn(1, 7),
                    startSection = start.coerceIn(1, 12),
                    endSection = end.coerceIn(1, 12),
                    weekMask = if (weekMask == 0) WeekMask.range(1, 16) else weekMask,
                    room = room.trim()
                )
            )
            if (courseName.trim() != entry.course.name || teacher.trim() != entry.course.teacher) {
                repository.updateCourse(
                    entry.course.copy(name = courseName.trim(), teacher = teacher.trim())
                )
            }
            _state.value = _state.value.copy(selected = null, message = "已保存修改")
        }
    }

    fun deleteSelectedSlot() {
        val entry = _state.value.selected ?: return
        viewModelScope.launch {
            repository.deleteSlot(entry.originSlot)
            _state.value = _state.value.copy(selected = null, message = "已删除该时段")
        }
    }

    fun deleteSelectedCourse() {
        val entry = _state.value.selected ?: return
        viewModelScope.launch {
            repository.deleteCourse(entry.course.id)
            _state.value = _state.value.copy(selected = null, message = "已删除整门课程")
        }
    }

    /** 停课：只作用于当前这一周的这一节，不动课程本身的排课。 */
    fun suspendCurrent() {
        val week = _state.value.week
        applySuspend(weekMask = WeekMask.bit(week), week = week)
    }

    /**
     * 调课：把某一节移到目标周次的某个时段。
     *
     * 源周取自**这一块自己所属的 override**（[TimetableEntry.sourceWeek]），
     * 不是"当前显示的周" —— 否则点开第 7 周那个「调」块再调课，
     * 改的会是第 7 周原计划的那一节，而不是从第 5 周调来的那一节。
     */
    fun moveCurrent(day: Int, start: Int, end: Int, room: String, targetWeek: Int) {
        val entry = _state.value.selected ?: return
        val maxW = maxWeek()
        val sourceWeek = entry.sourceWeek.takeIf { it in 1..maxW } ?: _state.value.week
        val safeTarget = targetWeek.coerceIn(sourceWeek, maxW)
        val blocksInTarget = ScheduleService.weekEntries(
            _state.value.entries,
            safeTarget,
            _state.value.overrides
        )

        // 目标周是否已经摆着这一节（同一条排课）。本周内换天时那块就是它自己，不算。
        val sameSlotBlock = if (safeTarget != sourceWeek) {
            blocksInTarget.firstOrNull { block ->
                // 同一条排课；但要把"这一块就是它自己"排除掉。
                // 注意：首次调课时两边 overrideId 都是 0，不能用 != 来判自，
                // 必须"只有 entry 本身来自某条 override 时，才按 id 排除"。
                val isSelf = entry.overrideId != 0L && block.overrideId == entry.overrideId
                block.isActive &&
                    block.originSlot.id == entry.originSlot.id &&
                    !isSelf
            }
        } else {
            null
        }

        // 选的时间正好压在那一节上：没有可调的空间，直接说清楚
        if (sameSlotBlock != null &&
            sameSlotBlock.slot.dayOfWeek == day &&
            sameSlotBlock.slot.startSection <= end &&
            sameSlotBlock.slot.endSection >= start
        ) {
            _state.value = _state.value.copy(
                message = "第 $safeTarget 周的这个时段本来就有这节课，换个时间再试"
            )
            return
        }

        val conflicts = detectMoveConflicts(entry, safeTarget, day, start, end)
        if (conflicts.isNotEmpty()) {
            _state.value = _state.value.copy(
                pendingMove = PendingMove(
                    slotId = entry.originSlot.id,
                    sourceWeek = sourceWeek,
                    toWeek = safeTarget,
                    day = day,
                    start = start,
                    end = end,
                    room = room.trim(),
                    editingOverrideId = entry.overrideId,
                    sameSlotInTarget = sameSlotBlock != null,
                    conflicts = conflicts
                )
            )
            return
        }

        commitMove(
            slotId = entry.originSlot.id,
            sourceWeek = sourceWeek,
            toWeek = safeTarget,
            day = day,
            start = start,
            end = end,
            room = room,
            editingOverrideId = entry.overrideId,
            // 目标周已有这一节 → 直接按"只上一次"处理，不再追问。
            // 换课本来的意思就是把课换到那个时间，问一次反而多一步。
            replaceTarget = sameSlotBlock != null
        )
    }

    /**
     * 把这次「换课」改成「停课」：删掉调课记录，改为在**源周**停课。
     *
     * 调课块本身不该有"停课"选项 —— 那会停掉目标周原计划那节，
     * 语义上完全跑偏。要停的是"被调走的这一节"。
     */
    fun convertMoveToSuspend() {
        val entry = _state.value.selected ?: return
        val sourceWeek = entry.sourceWeek.takeIf { it in 1..maxWeek() } ?: _state.value.week
        viewModelScope.launch {
            if (entry.overrideId > 0L) repository.clearOverride(entry.overrideId)
            repository.setOverride(
                SlotOverride(
                    slotId = entry.originSlot.id,
                    kind = SlotOverride.KIND_SUSPEND,
                    weekMask = WeekMask.bit(sourceWeek),
                    createdAt = System.currentTimeMillis()
                )
            )
            _state.value = _state.value.copy(
                selected = null,
                message = "第 $sourceWeek 周已改为停课"
            )
        }
    }

    /** 目标周已有这一节时选「补课」：那周上两次，第二次是调过去的。 */
    fun confirmMoveKeepBoth() {
        commitPendingMove(replaceTarget = false)
    }

    /** 目标周已有这一节时选「替换」：那周只上一次，时间用调过去的。 */
    fun confirmMoveReplaceTarget() {
        commitPendingMove(replaceTarget = true)
    }

    /** 与其他课程冲突时选「替换原课程」：先把那些课在目标周停掉，再落本次调课。 */
    fun confirmMoveWithReplace() {
        val pending = _state.value.pendingMove ?: return
        viewModelScope.launch {
            pending.conflicts.forEach { conflict ->
                repository.setOverride(
                    SlotOverride(
                        slotId = conflict.slotId,
                        kind = SlotOverride.KIND_SUSPEND,
                        weekMask = WeekMask.bit(pending.toWeek),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            // 走到这里必然存在其它课程冲突；同一条排课的情况按"补课"处理，
            // 用户在弹窗里看到的就是这个结果（提示里已写明目标周本来就有这节）。
            writeMove(pending, replaceTarget = false)
        }
    }

    /** 冲突时选「取消更改」：一个字都不写。 */
    fun cancelPendingMove() {
        _state.value = _state.value.copy(pendingMove = null, message = "已取消更改")
    }

    /** 撤销当前这块的停课/调课，回到原始课表。 */
    fun restoreCurrentOverride() {
        val entry = _state.value.selected ?: return
        val overrideId = entry.overrideId
        if (overrideId == 0L) {
            _state.value = _state.value.copy(message = "这块本来就是原始安排")
            return
        }
        viewModelScope.launch {
            repository.clearOverride(overrideId)
            _state.value = _state.value.copy(selected = null, message = "已恢复原始安排")
        }
    }

    private fun maxWeek(): Int =
        (_state.value.term?.totalWeeks ?: 20).coerceAtLeast(1)

    private fun applySuspend(weekMask: Int, week: Int) {
        val entry = _state.value.selected ?: return
        viewModelScope.launch {
            repository.setOverride(
                SlotOverride(
                    slotId = entry.originSlot.id,
                    kind = SlotOverride.KIND_SUSPEND,
                    weekMask = weekMask,
                    createdAt = System.currentTimeMillis()
                )
            )
            _state.value = _state.value.copy(
                selected = null,
                message = "第 $week 周已设为停课"
            )
        }
    }

    private fun commitPendingMove(replaceTarget: Boolean) {
        val pending = _state.value.pendingMove ?: return
        viewModelScope.launch { writeMove(pending, replaceTarget) }
    }

    /**
     * 落盘一条调课。
     *
     * [editingOverrideId] 非 0 时先删掉旧的那条 —— 再次调课是"改这一条"，
     * 不是"再叠一条"。不删的话，源周与目标周各留一条 override，
     * 两条都能命中渲染，于是同一周出现两个块。
     */
    private suspend fun writeMove(pending: PendingMove, replaceTarget: Boolean) {
        if (pending.editingOverrideId > 0L) {
            repository.clearOverride(pending.editingOverrideId)
        }
        repository.setOverride(
            SlotOverride(
                slotId = pending.slotId,
                kind = if (replaceTarget) {
                    SlotOverride.KIND_MOVE_REPLACE
                } else {
                    SlotOverride.KIND_MOVE
                },
                weekMask = WeekMask.bit(pending.sourceWeek),
                toWeek = pending.toWeek,
                toDay = pending.day,
                toStart = pending.start,
                toEnd = pending.end,
                toRoom = pending.room,
                createdAt = System.currentTimeMillis()
            )
        )
        _state.value = _state.value.copy(
            pendingMove = null,
            selected = null,
            message = moveMessage(
                pending.sourceWeek,
                pending.toWeek,
                pending.day,
                pending.start,
                pending.end,
                replaceTarget
            )
        )
    }

    private fun commitMove(
        slotId: Long,
        sourceWeek: Int,
        toWeek: Int,
        day: Int,
        start: Int,
        end: Int,
        room: String,
        editingOverrideId: Long,
        replaceTarget: Boolean
    ) {
        commitPendingMoveLike(
            PendingMove(
                slotId = slotId,
                sourceWeek = sourceWeek,
                toWeek = toWeek,
                day = day,
                start = start,
                end = end,
                room = room.trim(),
                editingOverrideId = editingOverrideId,
                sameSlotInTarget = false,
                conflicts = emptyList()
            ),
            replaceTarget
        )
    }

    private fun commitPendingMoveLike(pending: PendingMove, replaceTarget: Boolean) {
        viewModelScope.launch { writeMove(pending, replaceTarget) }
    }

    private fun moveMessage(
        sourceWeek: Int,
        toWeek: Int,
        day: Int,
        start: Int,
        end: Int,
        replaceTarget: Boolean
    ): String {
        val dayName = DateUtil.dayNames.getOrNull(day - 1).orEmpty()
        return when {
            toWeek == sourceWeek -> "已调到本周 $dayName $start-$end 节"
            replaceTarget -> "第 $sourceWeek 周这节课已取代第 $toWeek 周那节（$dayName $start-$end 节）"
            else -> "第 $sourceWeek 周这节课已调至第 $toWeek 周 $dayName $start-$end 节"
        }
    }

    /**
     * 找出目标周次、目标时段上与**其他课程**撞了哪些。
     *
     * 同一条排课在目标周已有的一块由 [moveCurrent] 单独处理（补课 / 替换），
     * 这里排除掉自己，避免把"本课"误报成冲突。
     */
    private fun detectMoveConflicts(
        entry: TimetableEntry,
        targetWeek: Int,
        day: Int,
        start: Int,
        end: Int
    ): List<MoveConflict> {
        val st = _state.value
        val ownSlotId = entry.originSlot.id
        val hit = LinkedHashMap<Long, String>()
        ScheduleService.weekEntries(st.entries, targetWeek, st.overrides).forEach { block ->
            if (!block.isActive) return@forEach
            if (block.originSlot.id == ownSlotId) return@forEach
            if (block.slot.dayOfWeek != day) return@forEach
            if (block.slot.startSection > end || block.slot.endSection < start) return@forEach
            hit[block.originSlot.id] = block.displayName
        }
        return hit.map { (slotId, name) -> MoveConflict(slotId = slotId, courseName = name) }
    }

    fun applyPeriodPreset(periods: List<Pair<String, String>>) {
        viewModelScope.launch {
            repository.applyPeriodPreset(periods)
            _state.value = _state.value.copy(message = "作息时间已更新")
        }
    }
}
