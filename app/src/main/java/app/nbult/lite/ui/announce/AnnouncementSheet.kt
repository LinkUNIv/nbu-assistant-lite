package app.nbult.lite.ui.announce

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nbult.lite.data.pack.Announcement
import app.nbult.lite.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 公告列表。
 *
 * 严格只渲染纯文本：不做 Markdown、不解析 HTML、不加载图片 —— 公告来自资源包，
 * 一旦允许富文本，就等于允许它伪装成应用自己的界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementSheet(
    items: List<Announcement>,
    onDismiss: () -> Unit,
    onSeen: (Collection<String>) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // 打开即视为已读（置顶项不参与未读计数）；顺便记一行曝光日志 ——
    // 没有后端，日志是唯一能回答「这条公告到底有没有人看到」的东西
    LaunchedEffect(items) {
        AppLog.log(
            "Announce",
            "看到 ${items.size} 条（未读 ${items.count { !it.pinned }}，置顶 ${items.count { it.pinned }}）"
        )
        val fresh = items.filter { !it.pinned }.map { it.seenKey }
        if (fresh.isNotEmpty()) onSeen(fresh)
    }

    // 参数名 items 会和 LazyListScope.items() 撞名，先取个别名
    val entries = items
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        // LazyColumn 而不是 Column+verticalScroll：公告多起来时，后者会把所有条目
        // 一次性测量并组合，长正文叠几条就卡；Lazy 只组合可见项。
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
        ) {
            item { Text("公告", style = MaterialTheme.typography.titleMedium) }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "暂无公告",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(entries, key = { it.seenKey }) { item ->
                AnnouncementItem(item)
            }
        }
    }
}

/** 折叠态显示的行数。超过就出现「展开」。 */
private const val COLLAPSED_LINES = 6

@Composable
private fun AnnouncementItem(item: Announcement) {
    val colors = MaterialTheme.colorScheme
    val accent = when (item.severity) {
        Announcement.Severity.BLOCKING -> colors.error
        Announcement.Severity.BANNER -> colors.primary
        Announcement.Severity.SILENT -> colors.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.pinned) {
                Box(
                    Modifier
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("置顶", style = MaterialTheme.typography.labelSmall, color = accent)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.pinned) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.since > 0L) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = dayOf(item.since),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 置顶（使用说明）默认展开；普通公告先折叠，**装不下才**显示「展开」——
        // 短公告挂一个"展开"按钮反而让人以为有隐藏内容（此处以前是 6 行截断且无处可展）
        var expanded by remember(item.seenKey) { mutableStateOf(item.pinned) }
        var overflowed by remember(item.seenKey) { mutableStateOf(false) }
        Text(
            text = item.body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout: TextLayoutResult ->
                if (!expanded) overflowed = layout.hasVisualOverflow
            }
        )
        if (!item.pinned && (overflowed || expanded)) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp)
            )
        }
        if (item.link != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.link,
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outline.copy(alpha = 0.12f))
        )
    }
}

private fun dayOf(epochSecond: Long): String =
    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(epochSecond * 1000))
