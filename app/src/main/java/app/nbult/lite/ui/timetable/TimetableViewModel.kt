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
    val selected: TimetableEntry? = null
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val totalSections: Int get() = ScheduleService.totalSections(entries)
    val currentWeekEntries: List<TimetableEntry>
        get() = weeks.getOrElse(week - 1) { emptyList() }
}

/** 停课/调课的生效范围。 */
enum class OverrideScope { THIS_WEEK, FROM_NOW }

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

    /** 停课：默认仅本周，也可"本周起每周"。 */
    fun suspendCurrent(scope: OverrideScope) {
        applyOverride(kind = SlotOverride.KIND_SUSPEND, scope = scope)
    }

    /** 调课到别的星期/节次。 */
    fun moveCurrent(day: Int, start: Int, end: Int, room: String, scope: OverrideScope) {
        applyOverride(
            kind = SlotOverride.KIND_MOVE,
            scope = scope,
            day = day,
            start = start,
            end = end,
            room = room
        )
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

    private fun applyOverride(
        kind: String,
        scope: OverrideScope,
        day: Int = 0,
        start: Int = 0,
        end: Int = 0,
        room: String = ""
    ) {
        val entry = _state.value.selected ?: return
        val term = _state.value.term ?: return
        val week = _state.value.week
        // 编辑永远针对原始时段：调课块被点开时 selected.slot 已经是目标时段了
        val slotId = entry.originSlot.id
        val toWeek = if (scope == OverrideScope.THIS_WEEK) {
            week
        } else {
            term.totalWeeks.coerceAtLeast(week)
        }
        viewModelScope.launch {
            repository.setOverride(
                SlotOverride(
                    slotId = slotId,
                    kind = kind,
                    fromWeek = week,
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
                message = if (kind == SlotOverride.KIND_SUSPEND) {
                    "已设为停课（第 $week 周）"
                } else {
                    "已调整上课时间（第 $week 周）"
                }
            )
        }
    }

    fun applyPeriodPreset(periods: List<Pair<String, String>>) {
        viewModelScope.launch {
            repository.applyPeriodPreset(periods)
            _state.value = _state.value.copy(message = "作息时间已更新")
        }
    }
}
