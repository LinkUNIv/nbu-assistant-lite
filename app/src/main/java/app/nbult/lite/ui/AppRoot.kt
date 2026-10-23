package app.nbult.lite.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.nbult.lite.BuildConfig
import app.nbult.lite.debug.DebugScreen
import app.nbult.lite.ui.calendar.CalendarScreen
import app.nbult.lite.ui.common.BlurIn
import app.nbult.lite.ui.common.MotionSpecs
import app.nbult.lite.ui.news.NewsDetailScreen
import app.nbult.lite.ui.news.NewsScreen
import app.nbult.lite.ui.news.NewsViewModel
import app.nbult.lite.ui.portal.PortalScreen
import app.nbult.lite.ui.settings.SettingsScreen
import app.nbult.lite.ui.timetable.TimetableScreen
import app.nbult.lite.ui.timetable.TimetableViewModel

private data class TabItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * 底部栏四项：课表 / 资讯 / 入口 / 我的。
 *
 * 校历不再占底部栏位置，而是并入「入口」页顶部的卡片（那里同时能看到"第几周"）。
 * 校历路由 "calendar" 保留，只是不作为 Tab 出现。
 */
private val tabs = listOf(
    TabItem("timetable", "课表", Icons.Outlined.CalendarMonth),
    TabItem("news", "资讯", Icons.AutoMirrored.Outlined.Article),
    TabItem("portal", "入口", Icons.Outlined.Apps),
    TabItem("settings", "我的", Icons.Outlined.Person)
)

/** 底部栏可见的路由：四个 Tab + 从「入口」进入的校历页（否则校历页会变成"无栏可退"的死角）。 */
private val barRoutes = tabs.map { it.route } + "calendar"

/**
 * 判断转场方向：按底部栏索引比较，而不是按 push/pop。
 * 否则从右往左切 Tab 时也会「从右侧推入」，看起来像盖上去。
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
    val motionBlur by container.settings.motionBlur.collectAsStateWithLifecycle(initialValue = false)

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in barRoutes) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "timetable",
            modifier = Modifier.padding(padding),
            enterTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = true
                )
                slideInHorizontally(
                    animationSpec = tween(240, easing = MotionSpecs.Decelerate),
                    initialOffsetX = { if (forward) it / 3 else -it / 3 }
                ) + fadeIn(tween(240, easing = MotionSpecs.Decelerate))
            },
            exitTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = true
                )
                slideOutHorizontally(
                    animationSpec = tween(200, easing = MotionSpecs.Decelerate),
                    targetOffsetX = { if (forward) -it / 6 else it / 6 }
                ) + fadeOut(tween(200, easing = MotionSpecs.Decelerate))
            },
            popEnterTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = false
                )
                slideInHorizontally(
                    animationSpec = tween(240, easing = MotionSpecs.Decelerate),
                    initialOffsetX = { if (forward) it / 3 else -it / 3 }
                ) + fadeIn(tween(240, easing = MotionSpecs.Decelerate))
            },
            popExitTransition = {
                val forward = isForward(
                    initialState.destination.route,
                    targetState.destination.route,
                    fallback = false
                )
                slideOutHorizontally(
                    animationSpec = tween(200, easing = MotionSpecs.Decelerate),
                    targetOffsetX = { if (forward) -it / 6 else it / 6 }
                ) + fadeOut(tween(200, easing = MotionSpecs.Decelerate))
            }
        ) {
            composable("timetable") {
                BlurIn(enabled = motionBlur) {
                    TimetableScreen(vm = timetableVm)
                }
            }
            composable("news") {
                BlurIn(enabled = motionBlur) {
                    NewsScreen(newsVm, onOpen = { id -> navController.navigate("news/$id") })
                }
            }
            composable(
                route = "news/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                NewsDetailScreen(
                    vm = newsVm,
                    id = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("calendar") {
                BlurIn(enabled = motionBlur) {
                    CalendarScreen(onBack = { navController.popBackStack() })
                }
            }
            composable("portal") {
                BlurIn(enabled = motionBlur) {
                    PortalScreen(
                        vm = timetableVm,
                        onOpenCalendar = { navController.navigate("calendar") }
                    )
                }
            }
            composable("settings") {
                BlurIn(enabled = motionBlur) {
                    SettingsScreen(
                        timetableVm = timetableVm,
                        onOpenDebug = { navController.navigate("debug") }
                    )
                }
            }
            if (BuildConfig.DEBUG) {
                composable("debug") { DebugScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}
