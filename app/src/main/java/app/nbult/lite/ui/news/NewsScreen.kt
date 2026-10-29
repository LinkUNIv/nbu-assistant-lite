package app.nbult.lite.ui.news

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import app.nbult.lite.ui.common.MotionSpecs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.outlined.Refresh
import app.nbult.lite.data.remote.NewsColumns
import app.nbult.lite.data.db.NewsItem
import app.nbult.lite.net.NetStatus
import app.nbult.lite.ui.common.OfflinePanel
import app.nbult.lite.ui.common.SourceErrorPanel
import coil.compose.AsyncImage
import org.json.JSONArray

private data class Block(val type: String, val value: String)

private fun parseBlocks(json: String): List<Block> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val type = o.optString("t")
                val value = o.optString("v")
                if (type.isNotBlank() && value.isNotBlank()) add(Block(type, value))
            }
        }
    }.getOrDefault(emptyList())
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun NewsScreen(vm: NewsViewModel, onOpen: (Long, Rect) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val net by vm.networkStatus.collectAsStateWithLifecycle()

    // 吸顶月标题需要先分组再渲染，无法在滚动过程中临时判断
    val monthGroups = remember(state.items) { groupByMonth(state.items) }

    // 来源与刷新固定在顶端，不随列表滚走：
    // 「这些内容是哪来的」「怎么再刷一次」是随时要能确认的两件事，藏进列表里等于没有。
    Column(
        Modifier
            .fillMaxSize()
    ) {
        NewsHeader(
            loading = state.loading,
            itemCount = state.items.size,
            lastSyncAt = state.items.maxOfOrNull { it.fetchedAt } ?: 0L,
            onRefresh = vm::refresh
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        when (net) {
            NetStatus.OFFLINE -> OfflinePanel(onRetry = vm::refresh)
            NetStatus.SOURCE_UNREACHABLE -> SourceErrorPanel(onRetry = vm::refresh)
            NetStatus.ONLINE -> {
                if (state.items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (state.loading) CircularProgressIndicator() else Text(
                            text = "暂无资讯",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 下拉刷新：顶部固定按钮之外再给一条手势，符合手机直觉
                    val pullState = rememberPullRefreshState(
                        refreshing = state.loading,
                        onRefresh = vm::refresh
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pullRefresh(pullState)
                    ) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            monthGroups.forEach { group ->
                                stickyHeader(key = "month:${group.label}") {
                                    MonthHeader(group.label)
                                }
                                items(group.items, key = { it.id }) { item ->
                                    NewsRow(item) { rect -> onOpen(item.id, rect) }
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }
                        PullRefreshIndicator(
                            refreshing = state.loading,
                            state = pullState,
                            modifier = Modifier.align(Alignment.TopCenter),
                            backgroundColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

}

/** 顶部固定条：来源（放大）＋ 条数/更新时间 ＋ 刷新按钮。 */
@Composable
private fun NewsHeader(
    loading: Boolean,
    itemCount: Int,
    lastSyncAt: Long,
    onRefresh: () -> Unit
) {
    val syncLabel = remember(lastSyncAt) {
        if (lastSyncAt <= 0L) "" else {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(lastSyncAt))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 顶部起点 +6dp：与右侧横带对齐（横带从状态栏下 6dp 起、高 56dp），
            // 这样两行标题的中心正好落在圆钮的水平中线上
            .padding(start = 16.dp, end = 6.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "内容来源：" + NewsColumns.sources.joinToString(" · ") {
                    it.removePrefix("宁波大学")
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = buildString {
                    append("按发布时间排序")
                    if (itemCount > 0) append(" · 共 $itemCount 条")
                    if (syncLabel.isNotBlank()) append(" · 更新于 $syncLabel")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (loading) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
        }
    }
}

/**
 * 月份分组。标签固定在滚动时吸顶，用于替代逐行日期块。
 */
private data class MonthGroup(val label: String, val items: List<NewsItem>)

/** 从 `2026-10-24` 形式的发布时间中取出可排序的月份键；无法解析时返回 null。 */
private fun monthKey(raw: String): String? {
    val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(raw) ?: return null
    val month = m.groupValues[2].toIntOrNull() ?: return null
    m.groupValues[3].toIntOrNull() ?: return null
    return "%s-%02d".format(m.groupValues[1], month)
}

/** `2026-10` → `2026 年 10 月`。 */
private fun formatMonth(key: String): String {
    val parts = key.split("-")
    return "%s 年 %d 月".format(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0)
}

/**
 * 按月份倒序分组。无法解析发布时间的条目统一归入末组，
 * 避免它们被静默丢弃，也避免混进某个月份里造成误读。
 */
private fun groupByMonth(items: List<NewsItem>): List<MonthGroup> {
    val buckets = LinkedHashMap<String, MutableList<NewsItem>>()
    val undated = mutableListOf<NewsItem>()
    for (item in items) {
        val key = monthKey(item.publishedAt)
        if (key == null) undated += item else buckets.getOrPut(key) { mutableListOf() } += item
    }
    val groups = buckets.entries
        .sortedByDescending { it.key }
        .map { MonthGroup(formatMonth(it.key), it.value) }
    return if (undated.isEmpty()) groups else groups + MonthGroup("日期未知", undated)
}

/**
 * 吸顶月标题。
 *
 * 底色必须不透明：滚动时列表内容从其下方经过，透明底会与标题文字重叠。
 */
@Composable
private fun MonthHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 列表行。
 *
 * 这里**刻意不放封面图**：
 * 1. 缩略图是 Coil 异步加载的，从详情页返回时列表重新组合会重跑一次请求/解码，
 *    正是"退出详情后又看到图片在加载"的来源；
 * 2. 去掉之后每行只剩栏目、标题与日期，排版更整齐，滑动也少一层解码。
 *
 * 日期写在标题下方而非左侧方块：月份已在吸顶标题中给出，行内重复一次完整日期，
 * 是为了让任意一行被单独截图时仍然可读。
 */
@Composable
private fun NewsRow(item: NewsItem, onClick: (Rect) -> Unit) {
    // 记下自己在窗口里的位置：展开动画要"从这一行长出来"
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable { onClick(bounds) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = item.columnName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (item.readAt == null) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // 发布日期只有日期粒度，没有时分，因此行内写"月-日"而不是完整时间
        val dateText = remember(item.publishedAt) { rowDate(item.publishedAt) }
        if (dateText != null) {
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * `2026-10-29 14:20` → `10-29 14:20`；只有日期时输出 `10-29`。
 *
 * 时分并非每个栏目都提供，因此按存在与否分别输出，不补 `00:00`。
 */
private fun rowDate(raw: String): String? {
    val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:\s+(\d{1,2}):(\d{2}))?""").find(raw) ?: return null
    val month = m.groupValues[2].toIntOrNull() ?: return null
    val day = m.groupValues[3].toIntOrNull() ?: return null
    val date = "%02d-%02d".format(month, day)
    val hour = m.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() } ?: return date
    val minute = m.groupValues.getOrNull(5)?.toIntOrNull() ?: return date
    return "%s %02d:%02d".format(date, hour.toInt(), minute)
}

/**
 * 日期块：**年贴左上、月/日贴右下**，一条从左下角到右上角的斜线把它们分开。
 *
 * 斜线画在文字**之上**，且两端刻意贴着两个数字的内侧角，
 * 于是数字各自被切掉一点点 —— 这正是想要的效果，不是画歪了。
 * 想调"切多深"，只改两个 Text 的 padding。
 */
@Composable
private fun DateStamp(publishedAt: String, modifier: Modifier = Modifier) {
    val parts = remember(publishedAt) { parseStamp(publishedAt) } ?: return
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = parts.first,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 7.dp, top = 4.dp)
        )
        Text(
            text = parts.second,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 5.dp)
        )
        Canvas(Modifier.matchParentSize()) {
            drawLine(
                color = outline.copy(alpha = 0.55f),
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

/** "2026-10-24" → ("2026", "10/24")；解析不出来返回 null，此时不画日期块，标题占满整行。 */
private fun parseStamp(raw: String): Pair<String, String>? {
    val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(raw) ?: return null
    val year = m.groupValues[1]
    val month = m.groupValues[2].toIntOrNull() ?: return null
    val day = m.groupValues[3].toIntOrNull() ?: return null
    return year to "%02d/%02d".format(month, day)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsDetailScreen(vm: NewsViewModel, id: Long, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.items.firstOrNull { it.id == id }
    val context = LocalContext.current
    val blocks = remember(item?.contentBlocks) { parseBlocks(item?.contentBlocks.orEmpty()) }

    LaunchedEffect(id) {
        vm.markRead(id)
        vm.loadContent(id)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("资讯详情", style = MaterialTheme.typography.titleMedium) },
            // AppRoot 的 Scaffold 已经把内容按状态栏下移过一次，
            // 而 TopAppBar 默认又自带一遍同高的 windowInsets —— 于是顶部留白算了两遍，
            // 标题上方会空出一大块（实测约 57dp）。这里清零，只由 Scaffold 负责。
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            }
        )

        if (item == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("内容已不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 顶部 20dp → 8dp：AppBar 正下方不需要再留一个"标题区高度的空白"
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 标题与来源合成一块（间距 6dp），末尾压一条 1dp 分隔线，
            // 把"标题区"和"正文区"分开 —— 否则去掉空白后标题会直接贴着正文，反而糊在一起
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${item.source} · ${item.publishedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 2.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    )
                }
            }

            if (blocks.isEmpty()) {
                item {
                    if (item.contentFetched) {
                        Text(
                            text = "未能解析正文，可打开原文查看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "  正在加载正文…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(blocks) { block ->
                    when (block.type) {
                        "img" -> AsyncImage(
                            model = block.value,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                        else -> Text(
                            text = block.value,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.7
                        )
                    }
                }
            }

            item {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                    }
                }) { Text("在浏览器中打开原文") }
            }
            item {
                Text(
                    text = "内容版权归宁波大学及原作者所有，本应用仅作阅读索引，请以原站发布为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.height(24.dp))
            }
        }
    }
}


/**
 * 资讯展开层。
 *
 * 放在 [app.nbult.lite.ui.AppRoot] 里、包住 `Scaffold` —— `bottomBar` 是 Scaffold 的
 * 独立布局槽位、绘制在内容之上，所以在内容区里无论怎么画都盖不住底部导航栏。
 *
 * 三段式，全部由一个 `progress` 派生（返回时进度倒退，三段自动倒放，不会出现进出节奏不一致）：
 *
 * | 段 | progress | 窗口 | 遮罩 | 内容 |
 * |---|---|---|---|---|
 * | ① 起手 | 0 → 0.18 | 停在点击的那一行 | 0 → 0.22（缓缓浮起，不刺眼） | 不可见 |
 * | ② 撑开 | 0.18 → 0.60 | 行矩形 → 满屏 | 保持 0.22 | 不可见 |
 * | ③ 浮现 | 0.60 → 1 | 已满屏 | 0.22 → 0 | 0 → 1（最后才渲染） |
 */
@Composable
fun NewsExpandOverlay(
    vm: NewsViewModel,
    openId: Long?,
    origin: Rect,
    progress: Animatable<Float, AnimationVector1D>,
    onClose: () -> Unit
) {
    if (openId == null) return
    val p = progress.value

    // 几何用 easeOutCubic：卡片先快后慢地铺满（p=0.5 已到 87%，p=0.7 到 97%）。
    // 用线性的话 p=0.7 时卡片才 70%，底部会露出亮着的列表和导航栏，
    // 而遮罩那时已经在淡出 —— 三者都处于"半吊子"状态（0.20.9 实机抓帧确认）。
    val g = 1f - (1f - p) * (1f - p) * (1f - p)

    // 遮罩：0.16 渐入 → 0.62 全程保持 → 最后 38% 缓慢退掉。
    val maskAlpha = when {
        p < 0.16f -> MASK_MAX * (p / 0.16f)
        p < 0.62f -> MASK_MAX
        else -> MASK_MAX * (1f - ((p - 0.62f) / 0.38f).coerceIn(0f, 1f))
    }
    // 内容**与撑开同步淡入**，p=0.65 时完全清晰。
    //
    // 这条是实机抓帧改出来的第三版，前两版都被抓帧否掉了：
    //   ① 0.70 起（0.20.9）：p≈0.75 时卡片 75%、遮罩已淡、内容才 0.17 —— 三样都半吊子；
    //   ② 0.22 起（0.20.10 初版）：p≈0.16 时卡片已长到 41%，内容仍是 0 —— 一大块白板；
    //      easeOutCubic 让几何跑得比内容快得多，任何"等一等再显示内容"的写法都会露白。
    // 现在从 0 起线性到 0.65 就满：卡片长多快，内容就浮多快，没有一帧是空的；
    // 而"内容比卡片慢、到 0.65 才清晰"仍然保留了"先撑开、后清晰"的层次。
    val contentAlpha = (p / 0.65f).coerceIn(0f, 1f)
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        val w = lerp(origin.width, fullW, g).coerceAtLeast(1f)
        val h = lerp(origin.height, fullH, g).coerceAtLeast(1f)
        val x = lerp(origin.left, 0f, g)
        val y = lerp(origin.top, 0f, g)

        // 遮罩铺满整个窗口（含状态栏与底部栏）；点空白处关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = maskAlpha))
                .pointerInput(Unit) { detectTapGestures { onClose() } }
        )

        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(
                    width = with(density) { w.toDp() },
                    height = with(density) { h.toDp() }
                )
                .clip(RoundedCornerShape(lerp(16f, 0f, g).dp))
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
        ) {
            // 内容按"满屏安全区"排一次；外层只负责开窗，所以文字全程不重排
            Box(
                Modifier
                    .requiredSize(
                        with(density) { fullW.toDp() },
                        with(density) { fullH.toDp() }
                    )
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .graphicsLayer { alpha = contentAlpha }
            ) {
                NewsDetailScreen(vm = vm, id = openId, onBack = onClose)
            }
        }

        BackHandler { onClose() }
    }
}

/** 遮罩最深值：够压住背景、又不至于在浅色页面上显得一脸黑。 */
private const val MASK_MAX = 0.22f
