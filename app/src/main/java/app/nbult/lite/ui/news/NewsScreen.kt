package app.nbult.lite.ui.news

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun NewsScreen(vm: NewsViewModel, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val net by vm.networkStatus.collectAsStateWithLifecycle()

    // 来源与刷新固定在顶端，不随列表滚走：
    // 「这些内容是哪来的」「怎么再刷一次」是随时要能确认的两件事，藏进列表里等于没有。
    Column(Modifier.fillMaxSize()) {
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
                            items(state.items, key = { it.id }) { item ->
                                NewsRow(item) { onOpen(item.id) }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                )
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
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
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

@Composable
private fun NewsRow(item: NewsItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (item.coverUrl.isNotBlank()) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Box(Modifier.weight(1f))
                Text(
                    text = item.publishedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.readAt == null) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(item.title, style = MaterialTheme.typography.titleLarge)
            }
            item {
                Text(
                    text = "${item.source} · ${item.publishedAt}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
