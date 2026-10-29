package app.nbult.lite.ui.timetable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import app.nbult.lite.ui.common.BandMetrics
import androidx.compose.material.icons.outlined.Today
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nbult.lite.data.db.PeriodTime
import app.nbult.lite.domain.CalendarDates
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.TimetableEntry
import app.nbult.lite.domain.WeekMask
import app.nbult.lite.ui.common.MotionSpecs

private const val MOVED_TAG = "调"
private val MOVED_TAG_SIZE = 12.dp

private val CoursePalette = listOf(
    Color(0xFF2F6FED),
    Color(0xFF0F766E),
    Color(0xFFB4530A),
    Color(0xFF7C3AED),
    Color(0xFFB91C1C),
    Color(0xFF0E7490),
    Color(0xFF4D7C0F)
)

fun courseColor(seed: Int): Color = CoursePalette[(seed and 0x7fffffff) % CoursePalette.size]

/** 课表尺寸常量：外部据此计算分页高度，避免在分页里再套一层垂直滚动。 */
object WeekGridMetrics {
    val cellHeight: Dp = 50.dp
    val headerHeight: Dp = 36.dp

    /** 时间栏要放下「节次 / 开始 / 结束」三行，比原来更宽。 */
    val timeWidth: Dp = 40.dp

    fun contentHeight(sections: Int): Dp = headerHeight + cellHeight * sections
}

/** 课程块的预计算文案，避免绘制阶段重复格式化。 */
private data class BlockVisual(
    val entry: TimetableEntry,
    val weekLabel: String
)

/** 取某节课的完整起止时间，如 08:00-09:35；缺作息时返回空串。 */
fun timeRangeOf(periods: List<PeriodTime>, start: Int, end: Int): String =
    timeRangeOf(periods.associateBy { it.idx }, start, end)

fun timeRangeOf(periods: Map<Int, PeriodTime>, start: Int, end: Int): String {
    val s = periods[start]?.startTime?.take(5)
    val e = periods[end]?.endTime?.take(5)
    return when {
        s != null && e != null -> "$s-$e"
        s != null -> s
        else -> ""
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeekHeader(
    pagerState: PagerState,
    startEpochDay: Long?,
    totalWeeks: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onImport: () -> Unit
) {
    val page = pagerState.currentPage
    val offsetProvider: () -> Float = { pagerState.currentPageOffsetFraction }

    // 顶栏要按可用宽分三档：固定开销约 202dp（箭头 48×2 + 本周 58 + ＋48），
    // 360dp 屏上中间那列只剩 64dp，而日期行要 114dp —— 不分档就必然被裁
    BoxWithConstraints {
    val fullWidth = maxWidth >= BandMetrics.HeaderFullMin
    val tooNarrow = maxWidth < BandMetrics.HeaderCompactMin
    // 系统字号放大后文字会吃掉余量，整体降一档
    val demote = LocalDensity.current.fontScale >= BandMetrics.FontScaleDemote
    val tier = when {
        demote -> if (fullWidth) 1 else 2
        fullWidth -> 0
        tooNarrow -> 2
        else -> 1
    }
    val dateCellWidth = if (tier == 0) 42.dp else 32.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 不再加纵向内边距：Row 里最高的 IconButton 正好 48dp，
            // 去掉那 2dp 后卡片天然落到 48dp，与右侧圆钮同高
            //
            // 右端留 10dp：胶囊右端是半径 24dp 的半圆，
            // 内容贴到边会被圆角切掉一块水波纹
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev, enabled = page > 0) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上一周")
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 周次：只有数字做条带位移，"第""周"是固定骨架
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                PageRibbon(
                    page = page,
                    offsetProvider = offsetProvider,
                    cellWidth = 26.dp,
                    count = totalWeeks
                ) { index ->
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Text(
                    text = "周",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            // C 档（<250dp）连日期行一起藏掉，只留「第 X 周」
            if (tier < 2) Row(verticalAlignment = Alignment.CenterVertically) {
                if (startEpochDay != null) {
                    PageRibbon(
                        page = page,
                        offsetProvider = offsetProvider,
                        cellWidth = dateCellWidth,
                        count = totalWeeks
                    ) { index ->
                        Text(
                            text = CalendarDates.monthDayShort(startEpochDay + index * 7L),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Text(
                        // 紧凑档去掉两侧空白，只要能看出"起 - 止"
                        text = if (tier == 0) " - " else "-",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PageRibbon(
                        page = page,
                        offsetProvider = offsetProvider,
                        cellWidth = dateCellWidth,
                        count = totalWeeks
                    ) { index ->
                        Text(
                            text = CalendarDates.monthDayShort(startEpochDay + index * 7L + 6),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    // 这个全角空格只是个占位符，纯浪费约 10dp，紧凑档不要
                    if (tier == 0) {
                        Text(text = "　", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        IconButton(onClick = onNext, enabled = page < totalWeeks - 1) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下一周")
        }
        if (tier == 0) {
            TextButton(onClick = onToday) { Text("本周") }
        } else {
            IconButton(onClick = onToday) {
                Icon(Icons.Outlined.Today, contentDescription = "回到本周")
            }
        }
        IconButton(onClick = onImport) {
            Icon(Icons.Outlined.Add, contentDescription = "导入课表")
        }
    }
    }
}

/**
 * 随翻页连续位移的条带。
 *
 * 为什么不用 AnimatedContent：它是"先离散换值、再播动画"，所以
 * 1) 必须等 currentPage 过半才动（永远慢半拍）；
 * 2) 退场与入场两条文案会在同一容器里并存，页宽只有几个字时就叠字了。
 *
 * 这里改成：把「上一页 / 当前页 / 下一页」三格并排铺开，整条按
 * [PagerState.currentPageOffsetFraction] 平移，父容器裁边。
 * 手指移 1px、字移 1px —— 同帧、同比例，没有切换瞬间，自然也不会叠。
 *
 * 注意：offset 在 layout 阶段读取（`Modifier.offset { }` 的 lambda 里），
 * 因此每帧只触发重排不触发重组，120Hz 下无额外开销。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageRibbon(
    page: Int,
    offsetProvider: () -> Float,
    cellWidth: Dp,
    count: Int,
    content: @Composable (Int) -> Unit
) {
    val widthPx = with(LocalDensity.current) { cellWidth.toPx() }
    // 用自定义 Layout 而不是 Box+Row：
    // Box 会把子节点约束在自身宽度内，Row 里的后两格会被测成 0 宽（上一版顶部数字全空就是这个原因）。
    // 这里用无约束测量每一格，再按 page/offset 手动摆放。
    Layout(
        content = {
            for (index in (page - 1)..(page + 1)) {
                Box(
                    modifier = Modifier.width(cellWidth),
                    contentAlignment = Alignment.Center
                ) {
                    if (index in 0 until count) content(index)
                }
            }
        },
        modifier = Modifier
            .width(cellWidth)
            .clipToBounds()
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            // offset 在布局阶段读取：每帧只重排、不重组
            val shift = -(1f + offsetProvider()) * widthPx
            placeables.forEachIndexed { index, placeable ->
                placeable.place(x = (shift + index * widthPx).roundToInt(), y = 0)
            }
        }
    }
}

/**
 * 今日 / 明日课程卡片，可折叠。
 * 折叠后只保留第一节课的一行摘要，给课表让出高度。
 */
@Composable
fun TodayCard(
    entries: List<TimetableEntry>,
    periods: List<PeriodTime>,
    isTomorrow: Boolean,
    /** 是否把「停课」「已调走」的块也列出来。偏好存在 DataStore，跨进程有效。 */
    showInactive: Boolean = false,
    onToggleInactive: () -> Unit = {},
    onEntryClick: (TimetableEntry) -> Unit = {}
) {
    if (entries.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    // 展开进度：0 折叠、1 展开。标题位移和内容模糊都由它驱动 ——
    // 用 graphicsLayer / blur 而不是 offset / animateContentSize，
    // 前者只影响绘制，后者每帧都会重新测量布局，是滚动掉帧的大头
    val expandProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = MotionSpecs.decelerate(220),
        label = "today-expand"
    )
    // 箭头：展开时朝上（0°），折叠后绕自身中心转 180° 朝下
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = MotionSpecs.decelerate(220),
        label = "today-arrow"
    )
    // 计数只算"还能上"的：混入停课块之后，直接把总数写上去会骗人
    val activeCount = entries.count { it.isActive }
    val suspendedCount = entries.count { it.suspended }
    val title = buildString {
        append(if (isTomorrow) "明天" else "今天")
        if (activeCount > 0) {
            append(" · 共 $activeCount 节")
        } else if (suspendedCount > 0) {
            append(" · 停课 $suspendedCount 节")
        } else {
            append(" · 共 0 节")
        }
        if (suspendedCount > 0 && activeCount > 0) append(" · 停课 $suspendedCount")
    }
    // 折叠态的摘要是"下一节上什么"，别让停课块抢了位置
    val first = entries.firstOrNull { it.isActive } ?: entries.first()
    val firstRange = timeRangeOf(periods, first.slot.startSection, first.slot.endSection)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 整块不再当折叠按钮：只有右侧那个箭头按钮负责开合
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 展开时标题向上让 5dp，折叠时落回。只改绘制不触发重排
                    .graphicsLayer { translationY = -5.dp.toPx() * expandProgress },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (!expanded) {
                        Text(
                            text = listOf(firstRange, first.displayName, first.room)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 切换「显示停课 / 已调走」。
                // 360dp 屏上标题已经快占满，所以只放图标，不加文字
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onToggleInactive() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (showInactive) {
                            Icons.Outlined.Visibility
                        } else {
                            Icons.Outlined.VisibilityOff
                        },
                        contentDescription = if (showInactive) "隐藏停课与换课" else "显示停课与换课",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                            alpha = if (showInactive) 1f else 0.55f
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowUp,
                        contentDescription = if (expanded) "折叠" else "展开",
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = arrowRotation },
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(MotionSpecs.decelerate(180)) +
                    fadeIn(MotionSpecs.decelerate(150)),
                exit = shrinkVertically(MotionSpecs.decelerate(150)) +
                    fadeOut(MotionSpecs.decelerate(120))
            ) {
                // 展开的明细从模糊里浮出来；折叠时反向糊掉再消失。
                // 模糊只在这一次性过渡里出现，滚动时恒为 0 —— 离屏合成太贵，
                // 每帧开着它会直接把高刷打回 40fps
                Column(
                    Modifier.blur(((1f - expandProgress) * 8f).dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    entries.forEach { entry ->
                        val range = timeRangeOf(periods, entry.slot.startSection, entry.slot.endSection)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 停课与「已调走」的块压暗，和还能上的课一眼分开
                                .alpha(if (entry.isActive) 1f else 0.55f)
                                .clickable { onEntryClick(entry) }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = range.ifBlank { "${entry.slot.startSection}节" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.width(76.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (entry.isActive) {
                                        null
                                    } else {
                                        TextDecoration.LineThrough
                                    }
                                )
                                if (entry.teacher.isNotBlank()) {
                                    Text(
                                        text = entry.teacher,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                        maxLines = 1
                                    )
                                }
                            }
                            if (entry.room.isNotBlank()) {
                                Text(
                                    text = entry.room,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 左侧节次栏：**固定不动**，不在 pager 内。
 *
 * 之前它画在每页课程表里，所以滑动时整栏跟着页面平移，读时间需要"追"着看。
 * 现在把它独立出来，只有右侧七天网格随手指移动。
 */
@Composable
fun WeekRail(
    periods: List<PeriodTime>,
    totalSections: Int,
    cellHeight: Dp,
    metaSize: TextUnit
) {
    val headerHeight = WeekGridMetrics.headerHeight
    val timeWidth = WeekGridMetrics.timeWidth
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val periodMap = remember(periods) { periods.associateBy { it.idx } }

    Column(
        modifier = Modifier
            .width(timeWidth)
            .height(headerHeight + cellHeight * totalSections)
            // 水印数字允许溢出到右边界，靠这个裁边收尾（配合节点内的渐变，接缝不可见）
            .clipToBounds()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "节", fontSize = metaSize, color = mutedColor)
        }
        for (section in 1..totalSections) {
            RailCell(
                section = section,
                start = periodMap[section]?.startTime?.take(5).orEmpty(),
                end = periodMap[section]?.endTime?.take(5).orEmpty(),
                cellHeight = cellHeight,
                width = timeWidth,
                metaSize = metaSize
            )
        }
    }
}

/**
 * 单个节次格：大号斜体数字作水印，起止时间上下排列压在上面。
 *
 * 数字放大后两位数会超出时间栏宽度，这里允许它向右溢出，并在
 * 最后 38% 的宽度里**线性渐隐到完全透明** —— 与课程卡片接触处已经看不见了。
 * `softWrap = false` + `wrapContentWidth(unbounded = true)` 是保证"永远单行"的关键。
 */
@Composable
private fun RailCell(
    section: Int,
    start: String,
    end: String,
    cellHeight: Dp,
    width: Dp,
    metaSize: TextUnit
) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val showEnd = cellHeight >= 44.dp
    val base = (cellHeight.value * 0.8f).coerceIn(26f, 46f)
    // 两位数自动降一档，避免"10/11/12"撑破格子或被迫换行
    val ordinalSize = (if (section >= 10) base * 0.7f else base).sp
    val clockSize = 8.5f.sp
    val clockColor = mutedColor.copy(alpha = 0.8f)

    Box(
        modifier = Modifier
            .width(width)
            .height(cellHeight),
        contentAlignment = Alignment.Center
    ) {
        // 水印数字：用"文字用渐变画刷"实现右缘淡出，而不是 DstIn 遮罩。
        // DstIn 必须在独立图层里才有意义，否则会跟窗口底下的内容合成 —— 上一版整条变黑就是它。
        Text(
            text = "$section",
            style = TextStyle(
                fontSize = ordinalSize,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                brush = Brush.horizontalGradient(
                    0f to Color(0xFF3B82F6).copy(alpha = 0.18f),
                    0.66f to Color(0xFF3B82F6).copy(alpha = 0.18f),
                    1f to Color(0xFF3B82F6).copy(alpha = 0.02f)
                )
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.5.dp)
        ) {
            if (start.isNotBlank()) {
                Text(
                    text = start,
                    fontSize = clockSize,
                    lineHeight = clockSize * 1.05f,
                    color = clockColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            if (showEnd && end.isNotBlank()) {
                Text(
                    text = end,
                    fontSize = clockSize,
                    lineHeight = clockSize * 1.05f,
                    color = clockColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 右侧七天网格（不含左侧节次栏），随 pager 翻页。
 *
 * 性能要点：
 * 1. 网格线用 [drawBehind] 一次画完，不创建单元格节点；
 * 2. 课程块由单个 [Layout] 统一放置，不触发布局重排；
 * 3. 文案预计算并 remember；
 * 4. 恒定完整渲染，起滑时没有任何结构变化。
 */
@Composable
fun WeekBody(
    weekEntries: List<TimetableEntry>,
    periods: List<PeriodTime>,
    totalSections: Int,
    columnWidth: Dp,
    cellHeight: Dp,
    weekStartEpochDay: Long? = null,
    today: Long = 0L,
    /** 要渲染的星期，升序。没有周末课时就少两列，工作日能宽出 40%。 */
    visibleDays: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7),
    onEntryClick: (TimetableEntry) -> Unit = {}
) {
    val headerHeight = WeekGridMetrics.headerHeight
    // 页面底色全透之后，格子没有白底兜着，网格线是唯一的分隔 ——
    // 0.22 太淡，抬到 0.34 才看得出格
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)
    val todayColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    val blocks = remember(weekEntries) {
        weekEntries.map { entry ->
            BlockVisual(entry = entry, weekLabel = WeekMask.label(entry.slot.weekMask))
        }
    }
    val dayDates = remember(weekStartEpochDay) {
        weekStartEpochDay?.let { start -> (0..6).map { start + it } }
    }

    val compact = columnWidth < 54.dp
    val nameSize = if (compact) 10.sp else 11.sp
    val metaSize = if (compact) 9.sp else 10.sp

    // 列宽三档：40dp 以下教室放不下，34dp 以下连课程名都得截
    val hideRoom = columnWidth < 40.dp
    val nameLimit = if (columnWidth < 34.dp) 2 else 0

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val colW = columnWidth.toPx()
                val top = headerHeight.toPx()
                val cellH = cellHeight.toPx()
                for (i in 0..visibleDays.size) {
                    val x = colW * i
                    drawLine(gridColor, Offset(x, top), Offset(x, size.height), 1f)
                }
                for (i in 0..totalSections) {
                    val y = top + cellH * i
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                }
            }
    ) {
        // 星期 + 日期表头（随页滑动）
        Row(Modifier.height(headerHeight)) {
            for (day in visibleDays) {
                val date = dayDates?.getOrNull(day - 1)
                val isToday = date != null && today != 0L && date == today
                val dayColor = if (isToday) todayColor else mutedColor
                val dateColor = if (isToday) todayColor else mutedColor.copy(alpha = 0.6f)
                Box(
                    Modifier
                        .width(columnWidth)
                        .height(headerHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    fontSize = metaSize,
                                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                                    color = dayColor
                                )
                            ) { append(DateUtil.dayNames[day - 1].removePrefix("周")) }
                            if (date != null) {
                                withStyle(
                                    SpanStyle(
                                        fontSize = (metaSize.value - 1.5f).coerceAtLeast(7f).sp,
                                        color = dateColor
                                    )
                                ) {
                                    append('\n')
                                    append(CalendarDates.monthDayShort(date))
                                }
                            }
                        },
                        textAlign = TextAlign.Center,
                        lineHeight = metaSize * 1.05f
                    )
                }
            }
        }

        BlockLayer(
            blocks = blocks,
            columnWidth = columnWidth,
            cellHeight = cellHeight,
            headerHeight = headerHeight,
            totalSections = totalSections,
            compact = compact,
            nameSize = nameSize,
            metaSize = metaSize,
            visibleDays = visibleDays,
            hideRoom = hideRoom,
            nameLimit = nameLimit,
            onEntryClick = onEntryClick
        )
    }
}

@Composable
private fun CourseBlock(
    entry: TimetableEntry,
    weekLabel: String,
    compact: Boolean,
    nameSize: TextUnit,
    metaSize: TextUnit,
    hideRoom: Boolean = false,
    nameLimit: Int = 0
) {
    val accent = courseColor(entry.course.colorSeed)
    val sections = entry.slot.endSection - entry.slot.startSection + 1
    val roomColor = MaterialTheme.colorScheme.onSurfaceVariant
    val metaSmall = (metaSize.value - 1).sp

    // ① 调课后的原时段：留一块灰底说明去向，避免"这门课这周消失了"的错觉
    if (entry.ghostLabel.isNotEmpty()) {
        val ghostSize = (metaSize.value - 0.5f).coerceAtLeast(7f).sp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRoundRect(
                        color = roomColor.copy(alpha = 0.3f),
                        style = Stroke(width = 1f),
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                }
                .padding(4.dp)
        ) {
            Text(
                text = entry.ghostLabel,
                fontSize = ghostSize,
                lineHeight = ghostSize * 1.25f,
                color = roomColor.copy(alpha = 0.7f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    // ② 停课：不改变卡片本体，只在上面盖一层半透明白 + 居中「停课」。
    // 本体保留作底纹，扫一眼就知道"这节课原来是哪门课、只是不上了"。
    val suspended = entry.suspended
    val blockColor = accent

    // 文案只在数据/尺寸变化时重建：滑动过程中相邻页会反复重组，
    // 每帧重建 AnnotatedString 会持续制造 SpanStyle 垃圾，正是高刷下滑顿挫的来源之一
    val titleText = remember(
        entry.displayName,
        entry.teacher,
        sections,
        nameSize,
        metaSize,
        blockColor,
        nameLimit
    ) {
        // 列宽 <34dp 时只留前两个字，否则一定溢出格子
        val shownName = if (nameLimit > 0) entry.displayName.take(nameLimit) else entry.displayName
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = blockColor,
                    fontSize = nameSize,
                    fontWeight = FontWeight.Medium
                )
            ) { append(shownName) }
            if (sections >= 2 && entry.teacher.isNotBlank()) {
                withStyle(
                    SpanStyle(color = blockColor.copy(alpha = 0.85f), fontSize = metaSize)
                ) {
                    append('\n')
                    append(entry.teacher)
                }
            }
        }
    }

    // 地点 + 周次：时间已挪到左侧时间栏（每节显示起止），卡片里不再重复
    val metaText = remember(
        entry.room,
        weekLabel,
        sections,
        compact,
        metaSize,
        metaSmall,
        roomColor,
        hideRoom
    ) {
        buildAnnotatedString {
            var first = true
            fun line(style: SpanStyle, text: String) {
                if (text.isBlank()) return
                if (!first) append('\n')
                withStyle(style) { append(text) }
                first = false
            }
            // 窄列放不下教室，直接不写，省下的高度留给周次
            if (!hideRoom) line(SpanStyle(color = roomColor, fontSize = metaSize), entry.room)
            if (!compact && sections >= 3) {
                line(
                    SpanStyle(color = roomColor.copy(alpha = 0.6f), fontSize = metaSmall),
                    weekLabel
                )
            }
        }
    }

    Box(
        // 一层绘制搞定：填充 + 描边都在 drawBehind 里画完。
        // 之前是 clip(圆角，会生成一个 RenderNode 图层) + background + drawBehind 三层，
        // 40 块/页 × 2 页 = 80 个多余图层，正是 GPU 6–7ms 的主要来源。
        modifier = Modifier
            .fillMaxSize()
            // 只加裁剪指令，不生成 RenderNode —— 之前去掉 clip 后文字会溢到相邻列
            .clipToBounds()
            .drawBehind {
                val radius = CornerRadius(6.dp.toPx())
                drawRoundRect(color = blockColor.copy(alpha = 0.13f), cornerRadius = radius)
                drawRoundRect(
                    color = blockColor.copy(alpha = 0.45f),
                    style = Stroke(width = 1f),
                    cornerRadius = radius
                )
            }
            .padding(horizontal = 3.dp, vertical = 3.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // 课程名 + 教师（同一段文字）；调课块在名字前挂一个方框「调」
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = titleText,
                    fontSize = nameSize,
                    lineHeight = nameSize * 1.15f,
                    maxLines = if (sections >= 2) 4 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (metaText.isNotEmpty()) {
                Text(
                    text = metaText,
                    fontSize = metaSize,
                    lineHeight = metaSize * 1.1f,
                    maxLines = when {
                        sections >= 3 -> 6
                        sections == 2 -> 4
                        else -> 2
                    },
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 调课标记：右下角正方形「调」，不占课名位置
        if (entry.badge == MOVED_TAG) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(MOVED_TAG_SIZE)
                    .border(1.dp, blockColor.copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = MOVED_TAG,
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Medium,
                    color = blockColor
                )
            }
        }

        // 停课遮罩：整块半透明白 + 居中「停课」
        if (suspended) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                        RoundedCornerShape(6.dp)
                    )
            )
            Text(
                text = "停课",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = blockColor.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun BlockLayer(
    blocks: List<BlockVisual>,
    columnWidth: Dp,
    cellHeight: Dp,
    headerHeight: Dp,
    totalSections: Int,
    compact: Boolean,
    nameSize: TextUnit,
    metaSize: TextUnit,
    visibleDays: List<Int>,
    hideRoom: Boolean,
    nameLimit: Int,
    onEntryClick: (TimetableEntry) -> Unit
) {
    Layout(
        content = {
            blocks.forEach { block ->
                CourseBlock(
                    entry = block.entry,
                    weekLabel = block.weekLabel,
                    compact = compact,
                    nameSize = nameSize,
                    metaSize = metaSize,
                    hideRoom = hideRoom,
                    nameLimit = nameLimit
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(blocks, columnWidth, cellHeight, visibleDays) {
                detectTapGestures { offset ->
                    val colW = columnWidth.toPx()
                    val cellH = cellHeight.toPx()
                    val top = headerHeight.toPx()
                    if (offset.x < 0f || offset.y < top) return@detectTapGestures
                    // 列号 → 星期：隐藏周末后列号不再等于 dayOfWeek
                    val day = visibleDays.getOrNull((offset.x / colW).toInt())
                        ?: return@detectTapGestures
                    val section = ((offset.y - top) / cellH).toInt() + 1
                    blocks.lastOrNull { b ->
                        b.entry.slot.dayOfWeek == day &&
                            section >= b.entry.slot.startSection &&
                            section <= b.entry.slot.endSection
                    }?.let { onEntryClick(it.entry) }
                }
            }
    ) { measurables, constraints ->
        // 尺寸兜底：快滑过程中若约束在极端情况下退化成 0/负数，
        // Constraints.fixed 会直接抛 IllegalArgumentException，这里统一夹到 ≥1px
        val colW = columnWidth.roundToPx().coerceAtLeast(1)
        val cellH = cellHeight.roundToPx().coerceAtLeast(1)
        val headH = headerHeight.roundToPx().coerceAtLeast(0)
        val gap = 2.dp.roundToPx().coerceAtLeast(0)

        // 用 measurables 为准去取 block，而不是反过来 —— 测量数量与数据列表
        // 万一同帧不同步，也只会少画一块，不会因为 blocks[index] 越界而闪退
        val placed = measurables.mapIndexedNotNull { index, measurable ->
            val block = blocks.getOrNull(index) ?: return@mapIndexedNotNull null
            val start = block.entry.slot.startSection.coerceIn(1, totalSections)
            val end = block.entry.slot.endSection.coerceIn(start, totalSections)
            val height = (cellH * (end - start + 1) - gap).coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(colW, height))
            block.entry.slot to placeable
        }

        val width = constraints.maxWidth.coerceAtLeast(0)
        val height = constraints.maxHeight.coerceAtLeast(0)
        layout(width, height) {
            placed.forEach { (slot, placeable) ->
                // 被隐藏的那天不该有块；真有就跳过，而不是画到别人的列上
                val col = visibleDays.indexOf(slot.dayOfWeek)
                if (col < 0) return@forEach
                val x = colW * col
                val y = headH + cellH * (slot.startSection.coerceIn(1, totalSections) - 1)
                placeable.place(x, y)
            }
        }
    }
}
