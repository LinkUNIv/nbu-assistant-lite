package app.nbult.lite.ui.portal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nbult.lite.util.openInBrowser
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.domain.CalendarDates
import app.nbult.lite.ui.timetable.TimetableViewModel
import org.json.JSONObject

/**
 * 「入口」页：校园常用系统的跳转聚合。
 *
 * 定位很明确 —— **只跳转，不读取**：
 * - 一律用 `ACTION_VIEW` 交给系统默认浏览器，不注入脚本、不读页面、不碰 Cookie；
 * - 因此这里没有 WebView，也没有任何网络请求，页面数据全部来自本地 assets；
 * - 标了 HTTP 的站点（校园卡、教务）是学校侧长期未升级明文，浏览器会提示不安全，
 *   我们把这件事写在页脚，而不是弹窗打扰。
 */
@Composable
fun PortalScreen(
    vm: TimetableViewModel,
    onOpenCalendar: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var entries by remember { mutableStateOf<List<PortalEntry>>(emptyList()) }
    var notice by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loaded = runCatching { PortalCatalog.load(context) }.getOrNull()
        entries = loaded?.first.orEmpty()
        notice = loaded?.second.orEmpty()
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2200)
            toast = null
        }
    }

    Box(Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item("calendar") {
                CalendarEntryCard(
                    week = state.week,
                    totalWeeks = (state.term?.totalWeeks ?: 20).coerceAtLeast(1),
                    weekStartEpochDay = state.term?.startEpochDay?.let {
                        it + (state.week - 1) * 7L
                    },
                    onClick = onOpenCalendar
                )
            }

            PortalCatalog.categories.forEach { category ->
                val group = entries.filter { it.category == category }
                if (group.isEmpty()) return@forEach
                item("header-$category") {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                    )
                }
                items(group, key = { it.id }) { entry ->
                    EntryRow(entry) {
                        // 校历默认进应用内页面（那里还能一键转浏览器），其余一律交默认浏览器
                        if (entry.id == "C1") {
                            onOpenCalendar()
                        } else {
                            val opened = openInBrowser(context, entry.url)
                            if (!opened) {
                                clipboard.setText(AnnotatedString(entry.url))
                                toast = "没有找到可用浏览器，链接已复制"
                            }
                        }
                    }
                }
            }

            item("footer") {
                Column(Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "入口失效？到「我的 → 工作日志」导出日志并反馈。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        val message = toast
        if (message != null) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** 校历入口卡片：既是入口，也是「这周第几周」的即时信息。 */
@Composable
private fun CalendarEntryCard(
    week: Int,
    totalWeeks: Int,
    weekStartEpochDay: Long?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "校历",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = buildString {
                        append("第 $week 周 / 共 $totalWeeks 周")
                        if (weekStartEpochDay != null) {
                            append("　")
                            append(CalendarDates.monthDayShort(weekStartEpochDay))
                            append(" - ")
                            append(CalendarDates.monthDayShort(weekStartEpochDay + 6))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun EntryRow(entry: PortalEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 用首字色块代替图标：不引第三方图标包，也不下载网络图标
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.title.take(1),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.login) Tag("需登录", MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.insecure) Tag("HTTP", MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = "在浏览器打开",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/** 入口配置：从 assets 读取，改入口不用改代码。 */
data class PortalEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val category: String,
    val login: Boolean,
    val insecure: Boolean
)

object PortalCatalog {

    val categories = listOf("学习", "生活", "服务")

    /** 返回 (入口列表, 页脚提示)。读不到时返回空列表，页面显示为空而不是崩溃。 */
    fun load(context: Context): Pair<List<PortalEntry>, String> {
        val raw = context.assets.open("portal/entries.json")
            .bufferedReader()
            .use { it.readText() }
        val root = JSONObject(raw)
        val array = root.optJSONArray("entries") ?: return emptyList<PortalEntry>() to ""
        val list = ArrayList<PortalEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            list.add(
                PortalEntry(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    subtitle = o.optString("subtitle"),
                    url = o.optString("url"),
                    category = o.optString("category", "服务"),
                    login = o.optBoolean("login", false),
                    insecure = o.optBoolean("insecure", false)
                )
            )
        }
        return list to root.optString("notice")
    }
}
