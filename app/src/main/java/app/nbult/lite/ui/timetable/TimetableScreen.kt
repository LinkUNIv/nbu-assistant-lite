package app.nbult.lite.ui.timetable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.ScheduleService
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

    Box(Modifier.fillMaxSize()) {
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

                        Column(Modifier.fillMaxSize()) {
                            WeekHeader(
                                pagerState = pagerState,
                                startEpochDay = termStart,
                                totalWeeks = totalWeeks,
                                onPrev = { vm.shiftWeek(-1) },
                                onNext = { vm.shiftWeek(1) },
                                onToday = { vm.jumpToCurrentWeek() },
                                onImport = { showImport = true }
                            )

                            // 常驻顶部：只与「今天」有关，不受左右切周影响，也不会自动展开/折叠
                            if (state.todayEntries.isNotEmpty()) {
                                TodayCard(
                                    entries = state.todayEntries,
                                    periods = state.periods,
                                    isTomorrow = state.todayIsTomorrow,
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
                                val cellHeight = ((maxHeight - headerHeight) / sections)
                                    .coerceIn(30.dp, 64.dp)
                                val gridHeight = minOf(headerHeight + cellHeight * sections, maxHeight)
                                // 节次栏固定占 40dp，剩下的宽度才是 7 列分页的可用宽度
                                val columnWidth = ((maxWidth - timeWidth) / 7).coerceAtLeast(1.dp)
                                val railMetaSize = if (columnWidth < 54.dp) 9.sp else 10.sp

                                // 滑动期间统计每帧耗时（高刷模式在 Activity 里声明）
                                val view = LocalView.current
                                LaunchedEffect(pagerState.isScrollInProgress) {
                                    val hz = runCatching { view.display?.refreshRate ?: 0f }
                                        .getOrDefault(0f)
                                    if (pagerState.isScrollInProgress) {
                                        FrameStats.setRefreshRate(hz)
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
                                // 箭头 / 本周：追逐目标，连点不重启动画，时长按距离自适应
                                LaunchedEffect(pagerState, totalWeeks) {
                                    snapshotFlow { (state.week - 1).coerceIn(0, totalWeeks - 1) }
                                        .collectLatest { target ->
                                            if (target !in 0 until pagerState.pageCount) {
                                                return@collectLatest
                                            }
                                            val distance =
                                                kotlin.math.abs(target - pagerState.currentPage)
                                            if (distance == 0) return@collectLatest
                                            val duration = (120 + 60 * distance).coerceAtMost(320)
                                            try {
                                                pagerState.animateScrollToPage(
                                                    page = target,
                                                    pageOffsetFraction = 0f,
                                                    animationSpec = tween(
                                                        durationMillis = duration,
                                                        easing = MotionSpecs.Decelerate
                                                    )
                                                )
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                // 手势抢占 / 页面销毁导致中断，不构成崩溃
                                            }
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

    if (showImport) {
        ModalBottomSheet(onDismissRequest = {
            showImport = false
            vm.clearPreview()
        }) {
            Surface(color = MaterialTheme.colorScheme.surface) {
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
        }
    }

    if (showManual) {
        ModalBottomSheet(onDismissRequest = { showManual = false }) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                AddCourseSheet(
                    periods = state.periods,
                    defaultDay = 1,
                    onDismiss = { showManual = false },
                    onSave = { name, teacher, room, day, start, end, mask ->
                        vm.addCourse(name, teacher, room, day, start, end, mask)
                        showManual = false
                    }
                )
            }
        }
    }

    val selected = state.selected
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { vm.select(null) }) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                CourseEditorSheet(
                    entry = selected,
                    week = state.week,
                    periods = state.periods,
                    hasOverride = selected.overrideId != 0L,
                    onDismiss = { vm.select(null) },
                    onSave = { name, teacher, day, start, end, mask, room ->
                        vm.saveSlot(name, teacher, day, start, end, mask, room)
                    },
                    onSuspend = { scope -> vm.suspendCurrent(scope) },
                    onMove = { day, start, end, room, scope ->
                        vm.moveCurrent(day, start, end, room, scope)
                    },
                    onRestore = { vm.restoreCurrentOverride() },
                    onDeleteSlot = { vm.deleteSelectedSlot() },
                    onDeleteCourse = { vm.deleteSelectedCourse() }
                )
            }
        }
    }
}
