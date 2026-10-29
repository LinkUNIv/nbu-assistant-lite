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
import kotlinx.coroutines.flow.combine
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
    /** 置顶卡是否把「停课」「已调走」的块也列出来（偏好存在 DataStore，跨进程有效）。 */
    val showInactiveToday: Boolean = false,
    /** 课表布局：true = 显示全部（7 天 + 满节次），false = 显示部分。 */
    val showFullTimetable: Boolean = false,
    val preview: ParseResult? = null,
    val previewError: String? = null,
    val importing: Boolean = false,
    val message: String? = null,
    val selected: TimetableEntry? = null,
    /** 非空表示有调课在等用户处理冲突。 */
    val pendingMove: PendingMove? = null,
    /**
     * 调课面板内的即时错误（例如"此时段已有这节课"）。
     *
     * 不能只靠 [message]：那条走的是课表页的 SnackbarHost，而调课面板是独立的
     * ModalBottomSheet 窗口，Snackbar 会被 scrim 和抽屉整个挡住，用户根本看不见。
     */
    val moveError: String? = null
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    /**
     * 要画多少行。
     *
     * 显示全部：沿用原来的 `minimum = 10`，也就是"至少十行"。
     * 显示部分：只画到**最晚有课的那一节**，下限 4 ——
     * 早间四节始终保留（第一节课在下午的人也要有纵向锚点），
     * 空周也不至于只剩一行看起来像坏了。
     */
    val totalSections: Int
        get() = ScheduleService.totalSections(
            entries,
            overrides,
            minimum = if (showFullTimetable) 10 else 4
        )
    val currentWeekEntries: List<TimetableEntry>
        get() = weeks.getOrElse(week - 1) { emptyList() }

    /**
     * 课表要显示的星期，升序。
     *
     * **按当前周算**，不是整学期。之前按学期聚合（`weeks` 全展开），
     * 于是把某一节调到周六之后，那两列被整学期保留 ——
     * 第 8 周有课、第 7 周没课，第 7 周也会多出一列空白周六。
     *
     * 代价是跨"有周末课的那周"时列数会变，宽度要重算；
     * 但所有周次变化都只在 pager 落位后提交（`settledPage` → `setWeek`），
     * 手滑、箭头、「本周」三条路径次序一致 —— 变化永远发生在翻页结束之后。
     * （0.30.4 之前箭头和「本周」是直接改 week，动画与翻页并行，5↔6 列时会卡一下。）
     */
    val visibleDays: List<Int>
        get() {
            // 还没加载完时先按满 7 列画，避免开屏先从 5 列跳到 6 列
            if (showFullTimetable || weeks.isEmpty()) return listOf(1, 2, 3, 4, 5, 6, 7)
            val used = currentWeekEntries.mapTo(HashSet()) { it.slot.dayOfWeek }
            return (1..7).filter { it <= 5 || it in used }
        }
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
            // 开关存在 DataStore，和课表快照一起组合进来：
            // 这样"杀进程重开"之后卡片仍然按上次的选择渲染
            combine(
                repository.observeSnapshot(),
                settings.showInactiveInToday,
                settings.showFullTimetable
            ) { snapshot, showInactive, showFull ->
                Triple(snapshot, showInactive, showFull)
            }
                .collect { (snapshot, showInactive, showFull) ->
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
                    snapshot.overrides,
                    includeInactive = showInactive
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
                    todayIsTomorrow = plan.isTomorrow,
                    showInactiveToday = showInactive,
                    showFullTimetable = showFull
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

    /**
     * 周次的**唯一**提交点：pager 落位后由 `settledPage` 调用（越界自动收敛）。
     *
     * 0.30.4 删掉了 `shiftWeek` / `jumpToCurrentWeek` —— 它们会被顶栏按钮直接调，
     * 当帧就改 `week`，于是列宽/行高动画和翻页动画并行播放。
     * 现在按钮只改屏幕层的 `navTarget`，翻页停稳后再落到这里。
     */
    fun setWeek(week: Int) {
        val term = _state.value.term ?: return
        val target = week.coerceIn(1, term.totalWeeks.coerceAtLeast(1))
        if (target == _state.value.week) return
        weekPickedByUser = true
        _state.value = _state.value.copy(week = target)
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
        _state.value = _state.value.copy(selected = entry, moveError = null)
    }

    /** 用户改了目标时间：旧的即时错误立刻作废，别让上一句提示赖在面板里。 */
    fun clearMoveError() {
        if (_state.value.moveError != null) {
            _state.value = _state.value.copy(moveError = null)
        }
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

    /** 置顶卡开关。写 DataStore，不是界面瞬时状态。 */
    fun setShowInactiveToday(enabled: Boolean) {
        viewModelScope.launch { settings.setShowInactive(enabled) }
    }

    /** 课表布局开关（列与行同时生效）。同样写 DataStore。 */
    fun setShowFullTimetable(enabled: Boolean) {
        viewModelScope.launch { settings.setShowFullTimetable(enabled) }
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

        // 只禁「和原时段一模一样」这一种：那等于原地调课，没有任何意义。
        // 3-3 / 4-4 这类与原时段部分重叠的选法要放行 —— 它至少是个不同的目标。
        if (sameSlotBlock != null &&
            sameSlotBlock.slot.dayOfWeek == day &&
            sameSlotBlock.slot.startSection == start &&
            sameSlotBlock.slot.endSection == end
        ) {
            _state.value = _state.value.copy(
                // 用 moveError 而不是 message：抽屉是独立窗口，Snackbar 会被它挡死
                moveError = "第 $safeTarget 周的此时段已有这节课，请换个时间再试"
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
            editingOverrideId = entry.overrideId
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
            // 走到这里必然存在其它课程冲突（同一条排课不会进这个弹窗）。
            writeMove(pending)
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

    /**
     * 跳到这门课「原来应该在的位置」。
     *
     * 调整过的位置不提供整门课编辑（改课程名/教师/周次会影响所有时段），
     * 所以给一个动作把人送回源周的原时段块 —— 那里编辑的才是课程本身。
     *
     * 周内调整时源周就是当前周，此时会选中同一周里的「原位置」块（灰块），
     * 不会重新打开刚才那一块，所以不存在"点了又弹回来"的循环。
     */
    fun openOrigin() {
        val entry = _state.value.selected ?: return
        val totalWeeks = (_state.value.term?.totalWeeks ?: 20).coerceAtLeast(1)
        val sourceWeek = (entry.sourceWeek.takeIf { it > 0 } ?: _state.value.week)
            .coerceIn(1, totalWeeks)
        val candidates = _state.value.weeks.getOrElse(sourceWeek - 1) { emptyList() }
        val originBlock = candidates.firstOrNull {
            // 原位块的 slot 就是 originSlot（调走后留下的灰块也满足这一条）
            it.originSlot.id == entry.originSlot.id && it.slot.id == entry.originSlot.id
        } ?: candidates.firstOrNull { it.originSlot.id == entry.originSlot.id }

        if (originBlock == null) {
            _state.value = _state.value.copy(message = "没找到这一节原来的位置")
            return
        }
        weekPickedByUser = true
        _state.value = _state.value.copy(
            week = sourceWeek,
            selected = originBlock,
            moveError = null
        )
    }

    /**
     * 落盘一条调课。
     *
     * [editingOverrideId] 非 0 时先删掉旧的那条 —— 再次调课是"改这一条"，
     * 不是"再叠一条"。不删的话，源周与目标周各留一条 override，
     * 两条都能命中渲染，于是同一周出现两个块。
     */
    private suspend fun writeMove(pending: PendingMove) {
        if (pending.editingOverrideId > 0L) {
            repository.clearOverride(pending.editingOverrideId)
        }
        repository.setOverride(
            SlotOverride(
                slotId = pending.slotId,
                // 一律 KIND_MOVE：目标周原本那一节必须留着。
                // KIND_MOVE_REPLACE 会把目标周同一 slot 的原块藏掉 —— 那样"把第 7 周这节
                // 调到第 10 周周四"会连带吃掉第 10 周周二那节原课，属于误删。
                // 老版本落库的 MOVE_REPLACE 记录仍按原样渲染（兼容分支保留在 ScheduleService）。
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
            moveError = null,
            message = moveMessage(
                pending.sourceWeek,
                pending.toWeek,
                pending.day,
                pending.start,
                pending.end
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
        editingOverrideId: Long
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
                conflicts = emptyList()
            )
        )
    }

    private fun commitPendingMoveLike(pending: PendingMove) {
        viewModelScope.launch { writeMove(pending) }
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
