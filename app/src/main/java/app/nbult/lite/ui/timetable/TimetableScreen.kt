package app.nbult.lite.ui.timetable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.WeekMask
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.ScheduleService
import app.nbult.lite.ui.common.AppBottomSheet
import app.nbult.lite.ui.common.BandMetrics
import app.nbult.lite.ui.common.acrylicPanel
import app.nbult.lite.ui.common.MotionSpecs
import app.nbult.lite.util.AppLog
import app.nbult.lite.util.FrameStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimetableScreen(vm: TimetableViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    var showImport by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.consumeMessage()
        }
    }

    LaunchedEffect(state.preview) {
        if (state.preview != null) showImport = true
    }

    // Scaffold 已改成 surface（白），这里把页面铺回淡蓝 —— 顶栏那块白才"白得出来"
    Box(
        Modifier
            .fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("载入中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                state.isEmpty -> TimetableEmptyHint(onImport = { showImport = true })

                else -> {
                    val totalWeeks = (state.term?.totalWeeks ?: 20).coerceAtLeast(1)
                    val today = AppClock.todayEpochDay()
                    val termStart = state.term?.startEpochDay
                    // 每周的课程由 VM 预算好（已叠加停课/调课），滑动时零过滤成本
                    val entriesByWeek = state.weeks

                    // pager 要被顶栏共享（条带需要读 currentPageOffsetFraction），
                    // 所以把「顶栏 + 课表」整体放进同一个 key 作用域里
                    key(totalWeeks) {
                        val pagerState = rememberPagerState(
                            initialPage = (state.week - 1).coerceIn(0, totalWeeks - 1)
                        ) { totalWeeks }

                        // 箭头与「本周」只写「想去哪一页」，不直接改 VM 的 week。
                        //
                        // 以前按钮走 vm.shiftWeek()，当帧就改 state.week → visibleDays /
                        // totalSections 立刻重算 → 列宽的 tween 与翻页动画**并行播放**，
                        // 翻页途中整张网格在重排（5 天周 ↔ 6 天周最刺眼）。
                        // 手滑走的是 settledPage，落位后才改数据，所以从来不卡。
                        // 现在两条路径统一：所有周次变化只在 settledPage 提交。
                        var navTarget by remember(pagerState) { mutableStateOf<Int?>(null) }
                        // 序号让「同一个目标页」也能再触发一次。
                        // snapshotFlow 自带 distinctUntilChanged，只盯目标值的话
                        // 「本周」连点第二次（值没变）就不会发射 —— 0.30.4 的回归就是它。
                        var navSeq by remember(pagerState) { mutableStateOf(0) }

                        // 连点在 navTarget 上累加：state.week 要等落位才更新，拿它当基准会吞点击
                        fun requestPage(target: Int, source: String) {
                            val page = target.coerceIn(0, totalWeeks - 1)
                            val distance =
                                kotlin.math.abs(page - pagerState.currentPage)
                            AppLog.event(
                                "Nav", "请求",
                                "目标" to page + 1,
                                "来源" to source,
                                "基准" to pagerState.currentPage + 1,
                                "距离" to distance,
                                // 距离 0 时根本不会滚动，写「动画」会让人以为动过
                                "方式" to when {
                                    distance == 0 -> "原地"
                                    distance > 3 -> "瞬移"
                                    else -> "动画"
                                }
                            )
                            navTarget = page
                            navSeq++
                        }

                        Column(
                            Modifier
                                .fillMaxSize()
                                // 大 R 角屏的圆角半径比手势条大，底部再让 8dp 是必要的。
                                //
                                // 更正 0.27.0 的说法：当时删掉 `navigationBarsPadding()`
                                // 并以"inset 让了两次"为理由，其实不成立 —— 里层那个调用
                                // 本来就返回约 0（insets 已在上层被消费 / 机型报告为 0）。
                                // 删掉是对的，但理由记错了，改动本身没有副作用
                                .padding(bottom = 8.dp)
                        ) {
                            // 周次栏：高度 48dp、左右两端半圆的胶囊，
                            // 与右侧圆钮同顶同高 —— 两者一起读作一条横带。
                            //
                            // 右端留 8dp：外层已因侧栏让出 64dp，圆钮占最右 56dp，
                            // 两者之间必须有这条缝。0.27.0 里圆钮只有 48dp、左边空出 8dp，
                            // 当时靠"圆钮自己窄"凑出了缝；圆钮撑到 56 之后就得自己让了
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 8.dp,
                                        end = 8.dp,
                                        top = BandMetrics.TopInset,
                                        bottom = 6.dp
                                    )
                                    .height(BandMetrics.Height)
                                    .acrylicPanel(RoundedCornerShape(percent = 50)),
                                // 内容仍是 48dp 高，竖直居中在 56dp 的胶囊里
                                contentAlignment = Alignment.Center
                            ) {
                                WeekHeader(
                                    pagerState = pagerState,
                                    startEpochDay = termStart,
                                    totalWeeks = totalWeeks,
                                    onPrev = {
                                        requestPage(
                                            (navTarget ?: pagerState.currentPage) - 1, "左箭头"
                                        )
                                    },
                                    onNext = {
                                        requestPage(
                                            (navTarget ?: pagerState.currentPage) + 1, "右箭头"
                                        )
                                    },
                                    onToday = {
                                        state.term?.let {
                                            requestPage(
                                                ScheduleService.currentWeek(it) - 1, "本周"
                                            )
                                        }
                                    },
                                    onImport = { showImport = true }
                                )
                            }

                            // 常驻顶部：只与「今天」有关，不受左右切周影响，也不会自动展开/折叠
                            if (state.todayEntries.isNotEmpty()) {
                                TodayCard(
                                    entries = state.todayEntries,
                                    periods = state.periods,
                                    isTomorrow = state.todayIsTomorrow,
                                    showInactive = state.showInactiveToday,
                                    onToggleInactive = {
                                        vm.setShowInactiveToday(!state.showInactiveToday)
                                    },
                                    onEntryClick = { vm.select(it) }
                                )
                            }

                            if (state.currentWeekEntries.isEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("本周没有课程", style = MaterialTheme.typography.labelMedium)
                                        Text(
                                            text = "左右滑动或点箭头切换周次",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            BoxWithConstraints(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                val timeWidth = WeekGridMetrics.timeWidth
                                val headerHeight = WeekGridMetrics.headerHeight
                                val sections = state.totalSections.coerceAtLeast(1)
                                // 行高**不做**插值。cellHeight 是每一格的布局约束，逐帧变化会让
                                // 11 行 × 5~6 列每帧重新测量 —— 0.30.5 实测掉帧 7~13%
                                // （0.30.4 是 0~1%），代价远大于「行高平滑」这点收益。
                                // columnWidth 能用动画，是因为 WeekBody 只是把宽度传给列，
                                // 行高不变时竖向测量结果可以复用。
                                val cellHeight = ((maxHeight - headerHeight) / sections)
                                    .coerceIn(30.dp, 64.dp)
                                val gridHeight = minOf(headerHeight + cellHeight * sections, maxHeight)
                                // 节次栏固定占 40dp，剩下按**实际要显示的列数**均分 ——
                                // 整学期没有周末课时就只有 5 列，每列能宽出约 40%
                                val columnCount = state.visibleDays.size.coerceAtLeast(1)
                                val targetColumnWidth = ((maxWidth - timeWidth) / columnCount)
                                    .coerceAtLeast(1.dp)
                                // 跨"有周末课的那一周"切周时列数会变（5↔6），
                                // 直接改列宽就是一帧重排的跳变；插值 180ms 把它变成收放。
                                // 字体档位按**目标**宽度判断，否则动画中途字号会来回跳一次
                                val columnWidth by animateDpAsState(
                                    targetValue = targetColumnWidth,
                                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                                    label = "timetableColumnWidth"
                                )
                                val railMetaSize = if (targetColumnWidth < 54.dp) 9.sp else 10.sp

                                // 滑动期间统计每帧耗时（高刷模式在 Activity 里声明）
                                val view = LocalView.current
                                LaunchedEffect(pagerState.isScrollInProgress) {
                                    val hz = runCatching { view.display?.refreshRate ?: 0f }
                                        .getOrDefault(0f)
                                    if (pagerState.isScrollInProgress) {
                                        FrameStats.setRefreshRate(hz)
                                        // 来源：navTarget 还挂着说明这次是按钮触发的翻页动画，
                                        // 否则是手指滑的。两者的渲染负载差很多，必须分开统计
                                        FrameStats.setScrollContext(
                                            source = if (navTarget != null) "按钮" else "手势",
                                            columns = columnCount,
                                            sections = sections
                                        )
                                        FrameStats.start()
                                        FrameStats.logScrollStart(
                                            pagerState.currentPage + 1,
                                            totalWeeks,
                                            hz
                                        )
                                    } else {
                                        FrameStats.stop(hz)
                                    }
                                }

                                // 数据层：只认「已稳定落位」的页，滑动过程中不回写，避免打断惯性
                                LaunchedEffect(pagerState) {
                                    snapshotFlow { pagerState.settledPage }
                                        .drop(1)
                                        .collect { page ->
                                            AppLog.log("Pager", "落位 第 ${page + 1} 周")
                                            vm.setWeek(page + 1)
                                        }
                                }
                                DisposableEffect(pagerState) {
                                    onDispose { FrameStats.stop() }
                                }
                                // 箭头 / 本周：追逐 navTarget，连点不重启动画，时长按距离自适应。
                                // 监听的是 (navSeq, navTarget) 而不是单独的目标页 —— 见 navSeq 的注释
                                LaunchedEffect(pagerState, totalWeeks) {
                                    snapshotFlow { navSeq to navTarget }
                                        .collectLatest { (seq, target) ->
                                            if (seq == 0 || target == null) return@collectLatest
                                            if (target !in 0 until pagerState.pageCount) {
                                                navTarget = null
                                                return@collectLatest
                                            }
                                            val distance =
                                                kotlin.math.abs(target - pagerState.currentPage)
                                            if (distance == 0) {
                                                // 已经在目标页：记一条，验收时能确认「点了但不用动」
                                                AppLog.event(
                                                    "Nav", "已在目标页", "周" to target + 1
                                                )
                                                navTarget = null
                                                return@collectLatest
                                            }
                                            val startedAt = System.currentTimeMillis()
                                            try {
                                                if (distance > 3) {
                                                    // 逐页飞过去时沿途每页都要现渲染
                                                    // （beyondBoundsPageCount = 1），从第 15 周
                                                    // 点「本周」等于看十次过场；远跳直接瞬移
                                                    pagerState.scrollToPage(target)
                                                } else {
                                                    val duration =
                                                        (120 + 60 * distance).coerceAtMost(320)
                                                    pagerState.animateScrollToPage(
                                                        page = target,
                                                        pageOffsetFraction = 0f,
                                                        animationSpec = tween(
                                                            durationMillis = duration,
                                                            easing = MotionSpecs.Decelerate
                                                        )
                                                    )
                                                }
                                                val landed = pagerState.currentPage
                                                AppLog.event(
                                                    "Nav", "落位",
                                                    "周" to landed + 1,
                                                    "目标" to target + 1,
                                                    "耗时" to "${System.currentTimeMillis() - startedAt}ms"
                                                )
                                                AppLog.assert(
                                                    "Nav", "mismatch",
                                                    landed == target,
                                                    "期望=${target + 1} 实际=${landed + 1}"
                                                )
                                            } catch (e: CancellationException) {
                                                // 连点太快时上一段动画会被 collectLatest 取消。
                                                // 不记这一行的话，日志上会变成「请求 32 条、落位 4 条」，
                                                // 看起来像 28 次跳转失败
                                                AppLog.event(
                                                    "Nav", "取消", "目标" to target + 1
                                                )
                                                throw e
                                            } catch (_: Exception) {
                                                // 手势抢占 / 页面销毁导致中断，不构成崩溃
                                            } finally {
                                                // 归零：让箭头下一次的累加基准回到 currentPage，
                                                // 否则会从旧目标起算、往反方向跳。
                                                // 用 seq 比对而不是目标页比对 —— 连点「本周」两次
                                                // 目标页是同一个，按目标页判会把新请求一起清掉
                                                if (navSeq == seq) navTarget = null
                                            }
                                        }
                                }
                                // 看门狗：请求发出 1.5s 后 navTarget 还挂着，说明跳转没落地。
                                // 成功路径会在 finally 里把它清掉，于是这个协程被 collectLatest 取消，
                                // 正常情况下永远不会打这条 [Assert]
                                LaunchedEffect(pagerState) {
                                    snapshotFlow { navTarget }
                                        .collectLatest { pending ->
                                            if (pending == null) return@collectLatest
                                            kotlinx.coroutines.delay(1500)
                                            AppLog.assert(
                                                "Nav", "stuck", false,
                                                "目标=${pending + 1} 1500ms 内没有落位"
                                            )
                                        }
                                }

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(gridHeight)
                                ) {
                                    WeekRail(
                                        periods = state.periods,
                                        totalSections = sections,
                                        cellHeight = cellHeight,
                                        metaSize = railMetaSize
                                    )
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        beyondBoundsPageCount = 1,
                                        pageSpacing = 0.dp,
                                        flingBehavior = PagerDefaults.flingBehavior(
                                            state = pagerState,
                                            pagerSnapDistance = PagerSnapDistance.atMost(1),
                                            snapAnimationSpec = MotionSpecs.snap(),
                                            snapPositionalThreshold = 0.32f
                                        )
                                    ) { page ->
                                        WeekBody(
                                            weekEntries = entriesByWeek.getOrElse(page) {
                                                emptyList()
                                            },
                                            periods = state.periods,
                                            totalSections = sections,
                                            columnWidth = columnWidth,
                                            cellHeight = cellHeight,
                                            weekStartEpochDay = termStart?.let {
                                                it + page * 7L
                                            },
                                            today = today,
                                            visibleDays = state.visibleDays,
                                            onEntryClick = { vm.select(it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    // 导入课表：三个抽屉统一走 AppBottomSheet（insets / 进退动画 / 高度过渡都在里面）
    AppBottomSheet(
        visible = showImport,
        onDismissRequest = {
            showImport = false
            vm.clearPreview()
        }
    ) {
        Column(Modifier.fillMaxWidth()) {
            ImportContent(
                preview = state.preview,
                previewError = state.previewError,
                importing = state.importing,
                onFile = vm::previewFile,
                onClear = vm::clearPreview,
                onConfirm = {
                    vm.confirmImport()
                    showImport = false
                },
                onManualAdd = {
                    showImport = false
                    showManual = true
                }
            )
        }
    }

    // 手动添加课程
    AppBottomSheet(
        visible = showManual,
        onDismissRequest = { showManual = false }
    ) {
        AddCourseSheet(
            periods = state.periods,
            defaultDay = 1,
            totalWeeks = state.term?.totalWeeks?.coerceAtLeast(1) ?: 20,
            onDismiss = { showManual = false },
            onSave = { name, teacher, room, day, start, end, mask ->
                vm.addCourse(name, teacher, room, day, start, end, mask)
                showManual = false
            }
        )
    }

    // 课程设置：payload 就是当前选中的那一节。
    // 选中被置空（点关闭、停课/恢复/删除、保存调课成功）时，组件自己播完下滑再移除 ——
    // 所有关闭路径自动都有动画，不需要给每个按钮单独包回调。
    AppBottomSheet(
        payload = state.selected,
        onDismissRequest = { vm.select(null) }
    ) { entry ->
        CourseEditorSheet(
            entry = entry,
            week = state.week,
            totalWeeks = state.term?.totalWeeks?.coerceAtLeast(1) ?: 20,
            periods = state.periods,
            hasOverride = entry.overrideId != 0L,
            moveError = state.moveError,
            onClearMoveError = vm::clearMoveError,
            onDismiss = { vm.select(null) },
            onSave = { name, teacher, day, start, end, mask, room ->
                vm.saveSlot(name, teacher, day, start, end, mask, room)
            },
            onSuspend = { vm.suspendCurrent() },
            onMove = { day, start, end, room, targetWeek ->
                vm.moveCurrent(day, start, end, room, targetWeek)
            },
            onConvertToSuspend = { vm.convertMoveToSuspend() },
            onGoOrigin = { vm.openOrigin() },
            onRestore = { vm.restoreCurrentOverride() },
            onDeleteSlot = { vm.deleteSelectedSlot() },
            onDeleteCourse = { vm.deleteSelectedCourse() }
        )
    }

    // 只有「与别的课程撞车」才需要问一句；目标周本来就有本节的情况不弹窗 —— 那两节共存
    state.pendingMove?.let { pending ->
        val dayName = DateUtil.dayNames.getOrNull(pending.day - 1).orEmpty()
        AlertDialog(
            onDismissRequest = { vm.cancelPendingMove() },
            title = { Text("时间冲突") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "把第 ${pending.sourceWeek} 周这一节调到" +
                            if (pending.toWeek == pending.sourceWeek) {
                                "本周 $dayName ${pending.start}-${pending.end} 节。"
                            } else {
                                "第 ${pending.toWeek} 周 $dayName ${pending.start}-${pending.end} 节。"
                            }
                    )
                    Text("该时段与以下课程重叠：", style = MaterialTheme.typography.labelMedium)
                    pending.conflicts.forEach { conflict ->
                        Text(
                            text = "· ${conflict.courseName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "「替换原课程」会把这些课在目标周设为停课，再完成本次调课。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmMoveWithReplace() }) { Text("替换原课程") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelPendingMove() }) { Text("取消更改") }
            }
        )
    }
}
