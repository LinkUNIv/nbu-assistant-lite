package app.nbult.lite.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.nbult.lite.data.update.UpdateCenter
import app.nbult.lite.data.update.UpdateInfo
import app.nbult.lite.data.update.UpdateState
import app.nbult.lite.ui.settings.UpdateDialog
import kotlinx.coroutines.flow.first
import app.nbult.lite.BuildConfig
import app.nbult.lite.data.notify.ClassReminderScheduler
import app.nbult.lite.debug.DebugScreen
import app.nbult.lite.ui.calendar.CalendarScreen
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import app.nbult.lite.ui.news.NewsExpandOverlay
import app.nbult.lite.ui.background.AppBackdrop
import app.nbult.lite.ui.common.luminanceIsDark
import app.nbult.lite.ui.nav.IslandTab
import app.nbult.lite.ui.nav.SideRail
import app.nbult.lite.ui.nav.rememberRailProgress
import kotlinx.coroutines.launch
import app.nbult.lite.ui.common.MotionSpecs
import app.nbult.lite.ui.common.PageEnterEffect
import app.nbult.lite.ui.news.NewsDetailScreen
import app.nbult.lite.ui.news.NewsScreen
import app.nbult.lite.ui.news.NewsViewModel
import app.nbult.lite.ui.portal.PortalScreen
import app.nbult.lite.ui.settings.AppearanceScreen
import app.nbult.lite.ui.settings.ChangelogScreen
import app.nbult.lite.ui.settings.LicensesScreen
import app.nbult.lite.ui.settings.ScheduleEditorScreen
import app.nbult.lite.ui.settings.SettingsScreen
import app.nbult.lite.ui.timetable.TimetableScreen
import app.nbult.lite.ui.timetable.TimetableViewModel
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 岛上的四项：课表 / 资讯 / 入口 / 我的。
 *
 * 校历不再占导航位，而是并入「入口」页顶部的卡片（那里同时能看到"第几周"）。
 * 校历路由 "calendar" 保留，只是不作为一个 Tab 出现。
 */
private val tabs = listOf(
    IslandTab("timetable", "课表", Icons.Outlined.CalendarMonth),
    IslandTab("news", "资讯", Icons.AutoMirrored.Outlined.Article),
    IslandTab("portal", "入口", Icons.Outlined.Apps),
    IslandTab("settings", "我的", Icons.Outlined.Person)
)

/** 岛可见的路由：四个 Tab + 从「入口」进入的校历页（否则校历页会变成"无栏可退"的死角）。 */
private val barRoutes = tabs.map { it.route } + "calendar"

/**
 * 判断转场方向：按导航项索引比较，而不是按 push/pop。
 * 竖向导航之后，索引小的在上面、大的在下面，所以"往下切"就是新页从下方进来。
 */
private fun isForward(fromRoute: String?, toRoute: String?, fallback: Boolean): Boolean {
    val fromIndex = tabs.indexOfFirst { it.route == fromRoute }
    val toIndex = tabs.indexOfFirst { it.route == toRoute }
    return if (fromIndex >= 0 && toIndex >= 0) toIndex > fromIndex else fallback
}

@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    val timetableVm = rememberViewModel {
        TimetableViewModel(
            repository = it.timetableRepository,
            settings = it.settings,
            calendarRepository = it.calendarRepository,
            termStartResolver = it.termStartResolver
        )
    }
    val newsVm = rememberViewModel { NewsViewModel(it.newsRepository, it.networkMonitor) }
    val motionBlur by container.settings.motionBlur.collectAsStateWithLifecycle(
        initialValue = container.settings.lastKnownMotionBlur
    )

    val navController = rememberNavController()

    // 启动后自动检查版本：延迟 12 秒，避开首屏；24 小时内最多一次。
    // 查到新版且用户没对同一版本点过「以后再说」，就弹窗。
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        val lastCheck = container.settings.updateCheckedAt.first()
        if (UpdateCenter.due(lastCheck)) {
            val result = UpdateCenter.refresh(container.settings)
            if (result is UpdateState.Available) {
                val dismissed = container.settings.updateDismissedCode.first()
                if (dismissed != result.info.versionCode.toLong()) {
                    pendingUpdate = result.info
                }
            }
        }
    }
    pendingUpdate?.let { info ->
        UpdateDialog(info = info, onDismiss = { pendingUpdate = null })
    }
    // 侧栏进度、作息编辑都要读它，提到导航图之前只收一次
    val timetableState by timetableVm.state.collectAsStateWithLifecycle()
    // 通知点击 → 切到课表并跳到那一周（等学期加载完再 setWeek，否则会被静默忽略）
    // 启动时排一次；之后课表/停课/调课一变就重排（闹钟永远只指向"下一个提醒点"）
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        ClassReminderScheduler.reschedule(appContext)
    }
    LaunchedEffect(Unit) {
        // 注意：不能写成 snapshotFlow { timetableVm.state.value } —— state 是 StateFlow，
        // 读 .value 不是快照读，snapshotFlow 不会因为数据变化而重新发射。
        timetableVm.state
            .map { st ->
                listOf(st.term?.id, st.term?.startEpochDay, st.entries.hashCode(), st.overrides.hashCode())
            }
            .distinctUntilChanged()
            .drop(1)
            .collect { ClassReminderScheduler.reschedule(appContext) }
    }
    val pendingWeek by container.pendingWeekJump.collectAsStateWithLifecycle()
    LaunchedEffect(pendingWeek) {
        val week = pendingWeek ?: return@LaunchedEffect
        container.pendingWeekJump.value = null
        navController.navigate("timetable") {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        val term = withTimeoutOrNull(3_000L) {
            timetableVm.state.map { it.term }.first { it != null }
        }
        if (term == null) {
            AppLog.log("Reminder", "跳到第 $week 周失败：学期信息未就绪")
        } else {
            timetableVm.setWeek(week)
            AppLog.log("Reminder", "已跳到第 $week 周")
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 资讯展开态放在这里而不是 NewsScreen 里：bottomBar 是 Scaffold 的独立布局槽位，
    // 绘制在内容之上，内容区里画什么都盖不住底部导航栏。包在 Scaffold 外面才盖得住。
    var newsOpenId by remember { mutableStateOf<Long?>(null) }
    var newsOrigin by remember { mutableStateOf(Rect.Zero) }
    val newsProgress = remember { Animatable(0f) }
    val newsScope = rememberCoroutineScope()

    fun closeNews() {
        if (newsOpenId == null) return
        newsScope.launch {
            // 收起 240ms（原 320）：展开含"撑开 + 浮现"两段、收起只有一段，
            // 时长相同会让返回显得拖；进出比约 1:1.58 更符合"进来有层次、出去干脆"
            newsProgress.animateTo(0f, tween(240, easing = MotionSpecs.Decelerate))
            newsOpenId = null
        }
    }

    LaunchedEffect(newsOpenId) {
        if (newsOpenId != null) {
            newsProgress.snapTo(0f)
            newsProgress.animateTo(1f, tween(380, easing = MotionSpecs.Decelerate))
        }
    }

    // 状态栏那一条是由 Scaffold 铺满整个窗口的（页面自己画的 background 只到内容区），
    // 所以 containerColor 必须等于"页面最顶部那一层"的颜色，否则会在状态栏下沿出现硬边。
    // 实测（真机取色）：资讯/入口/我的 三页的状态栏是 255 纯白、内容区是 247,249,252 淡蓝，
    // 170px 处一道 255→247 的硬切 —— 就是这么来的。
    // 只有"顶部自带白色顶栏"的页面才该让状态栏保持 surface：
    //   timetable（周次栏是 Surface(surface)）、debug（TopAppBar）
    // 其余页面顶部就是淡蓝内容区，状态栏必须同色。
    // currentRoute 为 null 是首帧，按启动页（课表）处理，避免闪一下淡蓝。
    val statusBarIsSurface = currentRoute == null ||
        currentRoute == "timetable" ||
        currentRoute == "debug"

    val backdropId by container.settings.backdrop
        .collectAsStateWithLifecycle(initialValue = "plain")

    // Text 的颜色来自 LocalContentColor，而它的**默认值就是黑色** ——
    // 只有被 Surface / Card 包住时才会被替换成 onSurface。
    // 页面标题、资讯和入口的列表项、子页标题都是直接画在背景上的，
    // 一直吃的是这个默认黑；底色浅时看不出来，抽掉底色之后就露了。
    // 这里统一给成 onBackground，所有裸文字一次性修好。
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onBackground
    ) {
    Box(Modifier.fillMaxSize()) {
    // 背景铺在最底层，连状态栏那条一起填 ——
    // 所以下面 Scaffold 必须透明，否则背景全被它挡住
    AppBackdrop(backdropId)
    Scaffold(
        containerColor = Color.Transparent,
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "timetable",
            // 导航胶囊浮在内容之上，页面不为它让宽度 —— 它窄到 56dp，
            // 而且只在展开的几秒内遮挡右侧一列
            // 右侧 64dp = 栏宽 56 + 与屏缘的 8dp 间距。栏是常驻的，
            // 所以这里是真的让位，不是浮层遮挡
            modifier = Modifier.padding(padding).padding(end = 64.dp),
            enterTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = true
                )
                slideInVertically(
                    animationSpec = tween(240, easing = MotionSpecs.Decelerate),
                    initialOffsetY = { if (forward) it / 3 else -it / 3 }
                ) + fadeIn(tween(240, easing = MotionSpecs.Decelerate))
            },
            exitTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = true
                )
                slideOutVertically(
                    animationSpec = tween(200, easing = MotionSpecs.Decelerate),
                    targetOffsetY = { if (forward) -it / 6 else it / 6 }
                ) + fadeOut(tween(200, easing = MotionSpecs.Decelerate))
            },
            popEnterTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = false
                )
                slideInVertically(
                    animationSpec = tween(240, easing = MotionSpecs.Decelerate),
                    initialOffsetY = { if (forward) it / 3 else -it / 3 }
                ) + fadeIn(tween(240, easing = MotionSpecs.Decelerate))
            },
            popExitTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = false
                )
                slideOutVertically(
                    animationSpec = tween(200, easing = MotionSpecs.Decelerate),
                    targetOffsetY = { if (forward) -it / 6 else it / 6 }
                ) + fadeOut(tween(200, easing = MotionSpecs.Decelerate))
            }
        ) {
            composable("timetable") {
                PageEnterEffect(enabled = motionBlur) {
                    TimetableScreen(vm = timetableVm)
                }
            }
            composable("news") {
                PageEnterEffect(enabled = motionBlur) {
                    // 详情不再是独立路由：它由 NewsScreen 内部以"展开"的方式呈现，
                    // 这样才能让卡片从被点那一行长出来，返回时收回原处。
                    NewsScreen(
                        vm = newsVm,
                        onOpen = { id, rect ->
                            newsOrigin = rect
                            newsOpenId = id
                        }
                    )
                }
            }
            composable("calendar") {
                PageEnterEffect(enabled = motionBlur) {
                    CalendarScreen(onBack = { navController.popBackStack() })
                }
            }
            composable("portal") {
                PageEnterEffect(enabled = motionBlur) {
                    PortalScreen(
                        vm = timetableVm,
                        onOpenCalendar = { navController.navigateOnce("calendar") },
                        onOpenLogs = { navController.navigateOnce("settings/more") }
                    )
                }
            }
            composable("settings") {
                PageEnterEffect(enabled = motionBlur) {
                    SettingsScreen(
                        timetableVm = timetableVm,
                        onOpenDebug = { navController.navigateOnce("debug") },
                        onOpenAppearance = { navController.navigateOnce("settings/appearance") },
                        onNavigateSection = { navController.navigateOnce("settings/$it") }
                    )
                }
            }
            // 具体路径要注册在 {section} 之前，否则会被通配路由抢走
            composable("settings/licenses") {
                PageEnterEffect(enabled = motionBlur) {
                    LicensesScreen(onBack = { navController.popBackStack() })
                }
            }
            composable("settings/changelog") {
                PageEnterEffect(enabled = motionBlur) {
                    ChangelogScreen(onBack = { navController.popBackStack() })
                }
            }
            composable("settings/appearance") {
                PageEnterEffect(enabled = motionBlur) {
                    AppearanceScreen(onBack = { navController.popBackStack() })
                }
            }
            composable("settings/schedule") {
                PageEnterEffect(enabled = motionBlur) {
                    ScheduleEditorScreen(
                        periods = timetableState.periods,
                        onSave = { list ->
                            timetableVm.applyPeriodPreset(list)
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable("settings/{section}") { entry ->
                PageEnterEffect(enabled = motionBlur) {
                    SettingsScreen(
                        timetableVm = timetableVm,
                        onOpenDebug = { navController.navigateOnce("debug") },
                        onOpenAppearance = { navController.navigateOnce("settings/appearance") },
                        onNavigateSection = { navController.navigateOnce("settings/$it") },
                        onBack = { navController.popBackStack() },
                        section = entry.arguments?.getString("section")
                    )
                }
            }
            // 预览版 debuggable=false，不能再用 BuildConfig.DEBUG 判断
            if (BuildConfig.BUILD_KIND != "release") {
                composable("debug") { DebugScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }

        // 常驻侧栏：三段亚克力面板，浮在内容之上、资讯展开层之下。
        // 层级不能反 —— 资讯展开时那片详情必须盖住侧栏。
        val railProgress = rememberRailProgress(timetableState)
        val railScope = rememberCoroutineScope()
        // 读"当前实际长什么样"，而不是读设置里的字符串 ——
        // 设置可能是「跟随系统」，那时字符串说不清是深是浅
        val darkNow = MaterialTheme.colorScheme.background.luminanceIsDark()

        // 底部手势区高度，**必须在 Scaffold 之外量**。
        // MIUI 全面屏手势下 navigationBars 的底部 inset 基本为 0，手势区实际记在
        // mandatorySystemGestures 里；两个取大才拿得到真值。
        // 交给侧栏自己调 navigationBarsPadding() 是不行的：实测只给出约 1.7dp，
        // 岛会贴到屏幕底边 13dp 处
        val density = LocalDensity.current
        val bottomInset = with(density) {
            maxOf(
                WindowInsets.navigationBars.getBottom(this),
                WindowInsets.mandatorySystemGestures.getBottom(this)
            ).toDp()
        }
        SideRail(
            tabs = tabs,
            currentRoute = currentRoute,
            progress = railProgress,
            // 二级页（settings/appearance）也要显示侧栏，按路由前缀匹配
            visible = currentRoute == null || barRoutes.any { currentRoute.startsWith(it) },
            dark = darkNow,
            onToggleDark = {
                // 二态直切：点过之后就从「跟随系统」变成固定值，这是"一键"的代价
                val target = if (darkNow) "light" else "dark"
                railScope.launch { container.settings.setDarkMode(target) }
            },
            bottomInset = bottomInset,
            // 长按圆钮直进外观页
            onOpenAppearance = { navController.navigateOnce("settings/appearance") },
            onSelect = { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        )

        // 资讯展开层：盖住导航岛与状态栏之间的全部区域
        NewsExpandOverlay(
            vm = newsVm,
            openId = newsOpenId,
            origin = newsOrigin,
            progress = newsProgress,
            onClose = { closeNews() }
        )
    }
    }
}

/**
 * 同一条路由只留一份。
 *
 * 不带 `launchSingleTop` 时，重复点同一个入口（长按圆钮、双击子页入口）
 * 会一次次往栈里压同一页，返回要按很多次才退得出去。
 * 做成扩展是为了让**以后新增的入口**也顺手用上 —— 逐个补 flag 治不了根。
 */
private fun NavController.navigateOnce(route: String) {
    navigate(route) { launchSingleTop = true }
}
