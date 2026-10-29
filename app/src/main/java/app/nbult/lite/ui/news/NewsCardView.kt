package app.nbult.lite.ui.news

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nbult.lite.R
import app.nbult.lite.data.db.NewsItem
import java.util.Calendar
import kotlin.math.absoluteValue

/** 卡片视图中的一天。`date` 形如 `2026-10-29`，`items` 已按发布时间倒序。 */
internal data class DayGroup(val date: String, val items: List<NewsItem>)

/** 按日期分组，保持传入顺序（列表本身已是倒序）。无发布日期的条目单独归入末组。 */
internal fun dayGroups(items: List<NewsItem>): List<DayGroup> {
    val buckets = LinkedHashMap<String, MutableList<NewsItem>>()
    val undated = mutableListOf<NewsItem>()
    for (item in items) {
        val key = dateKey(item.publishedAt)
        if (key == null) undated += item else buckets.getOrPut(key) { mutableListOf() } += item
    }
    val groups = buckets.entries.map { DayGroup(it.key, it.value) }
    return if (undated.isEmpty()) groups else groups + DayGroup("", undated)
}

/** 从发布时间中取出 `yyyy-MM-dd`；无法解析时返回 null。 */
internal fun dateKey(raw: String): String? {
    val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(raw) ?: return null
    val month = m.groupValues[2].toIntOrNull() ?: return null
    val day = m.groupValues[3].toIntOrNull() ?: return null
    return "%s-%02d-%02d".format(m.groupValues[1], month, day)
}

private fun dayMillis(date: String): Long? {
    val parts = date.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return Calendar.getInstance().apply {
        clear()
        set(y, m - 1, d)
    }.timeInMillis
}

/**
 * 相对日期标签。
 *
 * 只用于卡片头部的辅助说明；超过一周不再给出「N 天前」，
 * 因为此时读者关心的是具体日期，而不是相差天数。
 */
internal fun relativeDayLabel(date: String): String {
    if (date.isBlank()) return "日期未知"
    val target = dayMillis(date) ?: return ""
    val now = Calendar.getInstance()
    val y = now.get(Calendar.YEAR)
    val m = now.get(Calendar.MONTH)
    val d = now.get(Calendar.DAY_OF_MONTH)
    val today = Calendar.getInstance().apply {
        clear()
        set(y, m, d)
    }.timeInMillis
    val diff = ((today - target) / 86_400_000L).toInt()
    return when {
        diff == 0 -> "今天"
        diff == 1 -> "昨天"
        diff in 2..7 -> "$diff 天前"
        else -> ""
    }
}

/**
 * 日期块：年份在上、细分隔线居中、月日在下，整体左对齐。
 *
 * `size` 由调用方按卡片宽度推算（卡内宽的四分之一），不提供默认值 ——
 * 写死一个尺寸会在窄屏上超出预留区域。
 */
@Composable
internal fun DateStamp(date: String, size: Dp, modifier: Modifier = Modifier) {
    val parts = remember(date) {
        val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(date) ?: return@remember null
        val month = m.groupValues[2].toIntOrNull() ?: return@remember null
        val day = m.groupValues[3].toIntOrNull() ?: return@remember null
        m.groupValues[1] to "%02d.%02d".format(month, day)
    } ?: return

    val family = FontFamily(Font(R.font.noto_serif_subset))
    val density = LocalDensity.current
    val yearSize = with(density) { (size.value * 0.18f).sp }
    val dateSize = with(density) { (size.value * 0.33f).sp }
    val padding = with(density) { (size.value * 0.10f).dp }

    // 分隔线宽度跟随年份实际宽度，避免「2026」与「1999」下方线条长度不一
    val measurer = rememberTextMeasurer()
    val yearWidth = remember(parts.first, yearSize, family) {
        measurer.measure(
            text = parts.first,
            style = androidx.compose.ui.text.TextStyle(fontSize = yearSize, fontFamily = family)
        ).size.width
    }

    Column(
        modifier = modifier
            .width(size)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = parts.first,
            fontSize = yearSize,
            lineHeight = yearSize * 1.05f,
            fontFamily = family,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Box(
            Modifier
                .width(with(density) { (yearWidth * 1.6f).toDp() })
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        )
        Text(
            text = parts.second,
            fontSize = dateSize,
            lineHeight = dateSize * 1.05f,
            fontFamily = family,
            letterSpacing = (-0.5).sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 卡片视图：一天一张卡，左右滑动切换日期。
 *
 * 相邻卡片按当前页偏移连续缩放与淡出，形成「一叠牌被推开」的观感；
 * 不做相互遮挡，因为 pager 的页面并列排布，无法实现真正叠压。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NewsCardPager(
    items: List<NewsItem>,
    onOpen: (Long, Rect) -> Unit,
    cardView: Boolean,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = remember(items) { dayGroups(items) }
    if (groups.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { groups.size })

    Column(modifier.fillMaxSize()) {
        val currentMonth = remember(pagerState.currentPage, groups) {
            val date = groups.getOrNull(pagerState.currentPage)?.date ?: ""
            if (date.length >= 7) "%s 年 %d 月".format(date.substring(0, 4), date.substring(5, 7).toIntOrNull() ?: 0)
            else "资讯"
        }
        NewsSectionHeader(currentMonth, cardView, onToggleView)
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 32.dp),
            pageSpacing = 12.dp,
            beyondBoundsPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val offset = ((pagerState.currentPage - page) +
                pagerState.currentPageOffsetFraction).absoluteValue
            val scale = 1f - (offset.coerceIn(0f, 1f) * 0.08f)
            val alpha = 1f - (offset.coerceIn(0f, 1f) * 0.45f)
            // 卡片保持垂直居中：上下留白对称，比顶部对齐更稳
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            DayCard(
                group = groups[page],
                onOpen = onOpen,
                modifier = Modifier.padding(top = 12.dp).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    translationY = offset.coerceIn(0f, 1f) * 10.dp.toPx()
                }
            )
            }
        }

        SwipeHint(
            visibleTrigger = pagerState.currentPage to pagerState.isScrollInProgress,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
    }
}

/** 卡片头高度与单行条目高度。行高统一，卡片高度才能只由条目数决定。 */
private val CARD_HEADER_HEIGHT = 124.dp
private val CARD_ROW_HEIGHT = 84.dp

/** 卡片最多展示的条目数，超出部分在卡内滚动。 */
private const val CARD_MAX_ROWS = 3

/**
 * 资讯页次级标题条：左侧当前月份，右侧视图切换。
 *
 * 两个视图共用同一行，切换控件因此始终与月份文字基线对齐，
 * 不会像独立一行那样在顶栏下方留出一条空白带。
 */
@Composable
internal fun NewsSectionHeader(
    monthLabel: String,
    cardView: Boolean,
    onToggleView: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        ViewSwitchInline(cardView = cardView, onToggle = onToggleView)
    }
}

@Composable
private fun ViewSwitchInline(cardView: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(Modifier.padding(2.dp)) {
            SwitchItem("卡片", cardView) { if (!cardView) onToggle() }
            SwitchItem("列表", !cardView) { if (cardView) onToggle() }
        }
    }
}

@Composable
private fun SwitchItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayCard(
    group: DayGroup,
    onOpen: (Long, Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val visibleRows = group.items.size.coerceAtMost(CARD_MAX_ROWS)
    val cardHeight = CARD_HEADER_HEIGHT + CARD_ROW_HEIGHT * visibleRows

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .border(
                width = 1.dp,
                color = scheme.onSurface.copy(alpha = 0.10f),
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surface,
        tonalElevation = 0.dp
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.primaryContainer.copy(alpha = 0.55f),
                        scheme.surface
                    )
                )
            )
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    val stampSize = 84.dp
                    DateStamp(date = group.date, size = stampSize)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.padding(top = 6.dp)) {
                        val relative = remember(group.date) { relativeDayLabel(group.date) }
                        val shortDate = remember(group.date) {
                            if (group.date.length >= 10) group.date.substring(5) else "未标注"
                        }
                        Text(
                            text = if (relative.isBlank()) shortDate else "$relative · $shortDate",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(group.items, key = { it.id }) { item ->
                        DayCardRow(item, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCardRow(item: NewsItem, onOpen: (Long, Rect) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_ROW_HEIGHT)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable { onOpen(item.id, bounds) },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = item.columnName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.readAt == null) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 横滑提示：静止一段时间后浮出、左右晃动一次、再自行淡出；任何触摸立即消失。
 *
 * 与翻页状态完全解耦：只读取「当前页」作为重新触发的信号，不参与偏移计算，
 * 否则提示会被手指拖动带着走。
 */
@Composable
private fun SwipeHint(visibleTrigger: Pair<Int, Boolean>, modifier: Modifier = Modifier) {
    var consumed by rememberSaveable { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }
    val offsetX = remember { Animatable(0f) }

    LaunchedEffect(visibleTrigger) {
        if (consumed) return@LaunchedEffect
        if (visibleTrigger.second) {
            // 用户已开始滑动：立即收掉，不再提示
            if (alpha.value > 0f) alpha.animateTo(0f, tween(160))
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(2500)
        consumed = true
        alpha.animateTo(1f, tween(200))
        offsetX.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 700
                0f at 0 using LinearEasing
                6f at 140
                (-6f) at 300
                4f at 460
                (-4f) at 600
                0f at 700 using LinearEasing
            }
        )
        kotlinx.coroutines.delay(900)
        alpha.animateTo(0f, tween(300))
    }

    if (alpha.value > 0.01f) {
        Surface(
            modifier = modifier.graphicsLayer {
                this.alpha = alpha.value
                translationX = offsetX.value
            },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
        ) {
            Text(
                text = "左右滑动切换日期",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
