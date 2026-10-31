package app.nbult.lite.ui.news

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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

/**
 * 日期块：年份在上、细分隔线居中、月日在下。
 *
 * 排版是定稿的，只调字号：`size` 由调用方给定，年份占 0.20、月日占 0.40。
 * 块内左对齐 —— 年份、分隔线、月日的左端对齐成一条竖线，比居中更稳，
 * 切页时数字宽度变化也不会让整块左右晃动。
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
    // 除以 fontScale：卡片高度是固定的 dp，字号若跟着系统放大就会把下半截挤掉
    val yearSize = with(density) { (size.value * 0.20f / density.fontScale).sp }
    val dateSize = with(density) { (size.value * 0.40f / density.fontScale).sp }
    val padding = with(density) { (size.value * 0.06f).dp }

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
 *
 * 卡片默认居中于「顶栏与次级行之下的剩余区域」，那个区域的中心比整屏中心低。
 * 上移量取「pager 顶部在窗口中的位置 + 次级行高度」的一半。
 *
 * 这里不能用「顶栏高度」代替代替顶部位置 —— 顶栏之上还有状态栏，
 * 用高度算会正好漏掉状态栏的一半，卡片仍偏下十几 dp。
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

    // pager 顶部在窗口中的绝对位置：含状态栏与顶栏，正好是可用区域的上界
    var pagerTopPx by remember { mutableStateOf(0f) }
    val headerRowHeightPx = with(LocalDensity.current) { HEADER_ROW_HEIGHT.toPx() }
    val centerOffset = -(pagerTopPx + headerRowHeightPx) / 2f

    Column(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { pagerTopPx = it.positionInRoot().y }
    ) {
        // 卡片视图不显示左侧年月：卡片本身已经给出日期，重复一次只会挤占空间
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HEADER_ROW_HEIGHT)
                .padding(end = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewSwitchInline(cardView = cardView, onToggle = onToggleView)
        }
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
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    DayCard(
                        group = groups[page],
                        onOpen = onOpen,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            translationY = centerOffset + offset.coerceIn(0f, 1f) * 10.dp.toPx()
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
private val CARD_HEADER_HEIGHT = 132.dp
private val CARD_ROW_HEIGHT = 62.dp

/**
 * 日期块尺寸。固定值，不随条目数缩放 —— 切页时字号跳动比「单条日显得空」更难接受。
 *
 * 头部 132dp：日期块实测约 86dp 高，上下各留 23dp。
 * 再高会让日期块与第一条之间空出一大块，再低则日期块贴到卡片边缘。
 */
private val CARD_STAMP_SIZE = 108.dp

/** 卡片视图与列表视图共用的次级行高度。月份隐藏后仍要给这一行高度，否则间距会塌。 */
private val HEADER_ROW_HEIGHT = 40.dp

/** 卡内条目之间的间距。卡片高度必须把它算进去，否则三条会差几 dp 放不下。 */
private val CARD_ROW_SPACING = 2.dp

/** 卡内列表底部留白。最后一条不贴卡片下沿。 */
private val CARD_LIST_BOTTOM = 12.dp

/** 卡内列表底部渐隐带的高度。 */
private val CARD_FADE_HEIGHT = 40.dp

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
    // 高度 = 头部 + 各行 + 行间距 + 底部留白。
    // 早先只算了「行高 × 行数」，漏掉间距，于是三条的日期也差几 dp，
    // 明明没有第四条却仍被判为可滚动，底部渐隐就盖住了最后一条
    val cardHeight = CARD_HEADER_HEIGHT +
        CARD_ROW_HEIGHT * visibleRows +
        CARD_ROW_SPACING * (visibleRows - 1).coerceAtLeast(0) +
        CARD_LIST_BOTTOM

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
                // 日期块占满头部并左对齐。原来右侧还有一列相对日期，
                // 与块内月日重复，去掉后头部只剩日期本身
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CARD_HEADER_HEIGHT)
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    DateStamp(date = group.date, size = CARD_STAMP_SIZE)
                }

                val listState = rememberLazyListState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = CARD_LIST_BOTTOM
                        ),
                        verticalArrangement = Arrangement.spacedBy(CARD_ROW_SPACING)
                    ) {
                        items(group.items, key = { it.id }) { item ->
                            DayCardRow(item, onOpen)
                        }
                    }

                    // 底部渐隐：只在还能继续滚动时出现，提示下面还有内容。
                    // 用 alpha 过渡而不是 AnimatedVisibility —— 后者会改变布局高度，
                    // 列表内容会跟着抖一下
                    val canScroll by remember {
                        derivedStateOf { listState.canScrollForward }
                    }
                    val fadeAlpha by animateFloatAsState(
                        targetValue = if (canScroll) 1f else 0f,
                        animationSpec = tween(180),
                        label = "cardFade"
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(CARD_FADE_HEIGHT)
                            .graphicsLayer { alpha = fadeAlpha }
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, scheme.surface)
                                )
                            )
                    )
                }
            }
        }
    }
}

/**
 * 固定像素字号：除以系统 fontScale，让文字不随系统字号设置放大。
 *
 * 卡片的行高与头部高度都是写死的 dp（62dp / 132dp），字号一旦跟着系统放大，
 * 条目就会被挤出行外、整块内容溢出卡片。代价是不再响应无障碍字号设置，
 * 但「内容被裁掉」比「字没变大」更糟。
 */
@Composable
private fun fixedSp(value: Float): androidx.compose.ui.unit.TextUnit {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(0.5f)
    return (value / scale).sp
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
        // 栏目名与标题之间只留 2dp：两者是同一条资讯的两行，
        // 留多了会让人误以为是两条
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = item.columnName,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = fixedSp(11f)),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fixedSp(14f)),
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
