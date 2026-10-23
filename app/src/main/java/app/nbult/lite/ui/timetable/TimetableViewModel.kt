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
    val totalSections: Int get() = ScheduleService.totalSections(entries)
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
     * 调课：把**本周这一节**移到目标周次的某个时段。
     *
     * @param targetWeek 目标周，只允许 >= 当前周（不能往过去调）。
     *   等于当前周就是"本周内换天"，大于当前周就是"调到之后某周补课"。
     *
     * 保存前先做冲突检测：目标时段若已有其他课程，**不落库**，先挂起等用户决定
     * 「替换原课程」还是「取消更改」。
     */
    fun moveCurrent(day: Int, start: Int, end: Int, room: String, targetWeek: Int) {
        val entry = _state.value.selected ?: return
        val sourceWeek = _state.value.week
        val safeTarget = targetWeek.coerceIn(sourceWeek, maxWeek())

        // 目标周这个时段本来就摆着这节课本身（本周内调课时尤其常见）：
        // 再"调"一次没有意义，而且会比出一个和原块重叠的「调」块。
        val selfOverlap = ScheduleService.weekEntries(_state.value.entries, safeTarget, _state.value.overrides)
            .any { block ->
                block.isActive &&
                    block.originSlot.id == entry.originSlot.id &&
                    block.slot.dayOfWeek == day &&
                    block.slot.startSection <= end &&
                    block.slot.endSection >= start
            }
        if (selfOverlap) {
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
            room = room
        )
    }

    /** 冲突时选「替换原课程」：先把冲突课程在目标周停掉，再写入本次调课。 */
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
            repository.setOverride(
                SlotOverride(
                    slotId = pending.slotId,
                    kind = SlotOverride.KIND_MOVE,
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
                message = moveMessage(pending.sourceWeek, pending.toWeek, pending.day, pending.start, pending.end)
            )
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

    private fun commitMove(
        slotId: Long,
        sourceWeek: Int,
        toWeek: Int,
        day: Int,
        start: Int,
        end: Int,
        room: String
    ) {
        viewModelScope.launch {
            repository.setOverride(
                SlotOverride(
                    slotId = slotId,
                    kind = SlotOverride.KIND_MOVE,
                    weekMask = WeekMask.bit(sourceWeek),
                    toWeek = toWeek,
                    toDay = day,
                    toStart = start,
                    toEnd = end,
                    toRoom = room.trim(),
                    createdAt = System.currentTimeMillis()
                )
            )
            _state.value = _state.value.copy(
                selected = null,
                message = moveMessage(sourceWeek, toWeek, day, start, end)
            )
        }
    }

    private fun moveMessage(
        sourceWeek: Int,
        toWeek: Int,
        day: Int,
        start: Int,
        end: Int
    ): String {
        val dayName = DateUtil.dayNames.getOrNull(day - 1).orEmpty()
        return if (toWeek == sourceWeek) {
            "已调到本周 $dayName $start-$end 节"
        } else {
            "第 $sourceWeek 周这节课已调至第 $toWeek 周 $dayName $start-$end 节"
        }
    }

    /**
     * 找出目标周次、目标时段上与哪些课程撞了。
     *
     * 直接复用 [ScheduleService.weekEntries]：它会叠加停课/调课，
     * 所以"那门课那周已经被停掉或被调走"不会被误判成冲突；
     * 同一门课的其他时段（[TimetableEntry.originSlot] 不同）则照常参与判断。
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
