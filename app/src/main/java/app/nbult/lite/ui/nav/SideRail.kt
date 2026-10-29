package app.nbult.lite.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.ReminderPlanner
import app.nbult.lite.ui.common.BandMetrics
import app.nbult.lite.ui.common.RailMetrics
import app.nbult.lite.ui.common.acrylicPanel
import app.nbult.lite.ui.timetable.TimetableUiState
import kotlinx.coroutines.delay

/** 导航项。路由与改造前的底部栏一一对应，没变。 */
data class IslandTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val DotSize = 40.dp
private val DotGap = 9.dp

/**
 * 常驻右侧栏，三段各自独立成块。
 *
 * - **顶部**：深色切换圆钮（48dp 正圆）。
 * - **中部**：进度胶囊（56×100），显示下一节时刻或本节课剩余时间。
 * - **底部**：四个导航圆钮，贴底，避开屏幕圆角。
 *
 * 圆钮与课表周次栏**同顶同高**：都从状态栏下 `BandMetrics.TopInset` 起、
 * 高度都是 `BandMetrics.Height`。横向也是连续的 —— 周次栏右端在
 * 距屏右 `RailMetrics.Width + RailMetrics.Gutter` 那条线上，正好是右列左边缘，
 * 圆钮比胶囊窄 8dp，于是它与周次栏之间自然留出 8dp，看起来像一条横带。
 *
 * 圆钮右边缘与两块胶囊右边缘对齐（都距屏右 8dp），左边缘差一档。
 * 这是有意的：右边缘贴同一条屏缘更要紧，而 48dp 的高度带来的 24dp 半径
 * 与胶囊 28dp 的圆角更接近，比"同宽"更容易被眼睛接受。
 */
@Composable
fun SideRail(
    tabs: List<IslandTab>,
    currentRoute: String?,
    progress: RailProgress,
    visible: Boolean,
    dark: Boolean,
    onToggleDark: () -> Unit,
    onOpenAppearance: () -> Unit,
    /** 底部手势区高度，由 AppRoot 在 Scaffold 之外量好传进来（见下）。 */
    bottomInset: Dp,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible || tabs.isEmpty()) return

    // 子页（如 settings/appearance）高亮它所属的那个 tab
    val currentIndex = tabs.indexOfFirst {
        it.route == currentRoute || currentRoute?.startsWith(it.route + "/") == true
    }.coerceAtLeast(0)

    Box(modifier.fillMaxSize()) {
        // 顶段：圆钮 + 进度胶囊，作为一个整体从状态栏下 6dp 开始
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = RailMetrics.Gutter)
                .statusBarsPadding()
                .padding(top = BandMetrics.TopInset)
                .width(RailMetrics.Width),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 靠右对齐，右边缘与下面两块胶囊同一条线（都距屏右 8dp）。
            // 圆钮只有 48dp 宽，居中会往左缩 4dp，与胶囊差半格，看着像没对齐
            DarkToggle(
                dark = dark,
                onToggle = onToggleDark,
                onLongPress = onOpenAppearance,
                modifier = Modifier.align(Alignment.End)
            )
            Spacer(Modifier.height(RailMetrics.ToggleToProgress))
            ProgressCapsule(progress)
        }

        // 底段：导航块贴底。
        //
        // 这里不能用 `navigationBarsPadding()`：实测这台机器上它只给出约 1.7dp，
        // 岛离屏底只有 13.7dp（设计要 36）。原因是 MIUI 全面屏手势下
        // `navigationBars` 底部 inset 基本为 0，真正的手势区在
        // `mandatorySystemGestures` 里；M3 Scaffold 也可能已经把 insets 消费掉。
        // 所以高度由 AppRoot 在 Scaffold 之外量好传进来，这里只加固定余量
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = RailMetrics.Gutter)
                .padding(bottom = bottomInset + RailMetrics.NavBottomExtra)
                .width(RailMetrics.Width)
        ) {
            NavBlock(tabs, currentIndex, onSelect)
        }
    }
}

/**
 * 深色模式一键切换。
 *
 * 点是**二态直切**：读当前实际外观，写成 `light` 或 `dark`。
 * 代价要说清 —— 一旦点过，设置里的"深浅模式"就从「跟随系统」变成固定值，
 * 之后不会再跟着日落变；想回到跟随得去「我的 → 外观」手动选回来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DarkToggle(
    dark: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(RailMetrics.DarkToggle)
            .acrylicPanel(CircleShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = if (dark) "切到浅色" else "切到深色",
                onLongClickLabel = "打开外观设置",
                onLongClick = onLongPress,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            contentDescription = if (dark) "切到浅色" else "切到深色",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun NavBlock(
    tabs: List<IslandTab>,
    currentIndex: Int,
    onSelect: (String) -> Unit
) {
    val shape = RoundedCornerShape(RailMetrics.Width / 2)
    Column(
        modifier = Modifier
            .width(RailMetrics.Width)
            .height(RailMetrics.NavHeight)
            .acrylicPanel(shape),
        verticalArrangement = Arrangement.spacedBy(DotGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        tabs.forEachIndexed { i, tab ->
            RailDot(
                icon = tab.icon,
                label = tab.label,
                selected = i == currentIndex,
                onClick = { onSelect(tab.route) }
            )
        }
    }
}

@Composable
private fun RailDot(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val idleIcon = MaterialTheme.colorScheme.onSurfaceVariant
    val idleBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    Box(
        modifier = Modifier
            .size(DotSize)
            .clip(CircleShape)
            .background(if (selected) primary else idleBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) Color.White else idleIcon,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 算出进度胶囊要显示什么。
 *
 * 刷新对齐到分钟边界而不是固定轮询：一分钟醒一次，且切换时刻不会晚。
 * 之前按 30s 轮询，最坏会晚 30 秒才显示出新的一分钟。
 */
@Composable
fun rememberRailProgress(state: TimetableUiState): RailProgress {
    var now by remember { mutableStateOf(AppClock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val wait = 60_000L - (AppClock.now() % 60_000L)
            delay(wait.coerceIn(1_000L, 60_000L) + 60L)
            now = AppClock.now()
        }
    }

    val term = state.term ?: return RailProgress.None
    if (state.periods.isEmpty()) return RailProgress.None

    val current = remember(state.entries, state.overrides, state.periods, term, now) {
        ReminderPlanner.currentClass(
            entries = state.entries,
            overrides = state.overrides,
            periods = state.periods,
            term = term,
            now = now
        )
    }

    if (current != null) {
        val total = (current.endAt - current.startAt).coerceAtLeast(60_000L)
        val left = (current.endAt - now).coerceIn(0L, total)
        return RailProgress.InClass(
            remainingMin = (left / 60_000L).toInt().coerceAtLeast(1),
            // 语义是"还剩多少"：越接近下课越短，描边随之逆时针收回
            fraction = left.toFloat() / total
        )
    }

    // 没课：显示下一节的开始时刻。今天有几节课扫一眼课表就知道，
    // 真正想知道的是"下一节什么时候"
    val upcoming = remember(state.entries, state.overrides, state.periods, term, now) {
        ReminderPlanner.next(
            entries = state.entries,
            overrides = state.overrides,
            periods = state.periods,
            term = term,
            now = now
        )
    }
    return if (upcoming != null) {
        RailProgress.NextClass(upcoming.startAt)
    } else {
        RailProgress.None
    }
}
