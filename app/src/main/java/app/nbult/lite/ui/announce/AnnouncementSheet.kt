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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("公告", style = MaterialTheme.typography.titleMedium)
            if (items.isEmpty()) {
                Text(
                    "暂无公告",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items.forEach { item ->
                AnnouncementItem(item)
            }
        }
    }
}

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
        // 置顶（使用说明）直接展开；普通公告保留完整正文，但限制行数免得占满屏幕
        Text(
            text = item.body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = if (item.pinned) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis
        )
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
