package app.nbult.lite.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.nbult.lite.BuildConfig
import app.nbult.lite.data.changelog.ChangelogBlock
import app.nbult.lite.data.changelog.ChangelogCatalog
import app.nbult.lite.data.changelog.ChangelogVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首屏展开的版本数。
 *
 * 只放最新一版：多数人打开这一页只想确认「刚更新的这版改了什么」，
 * 其余收成单行，需要时再点开。
 */
private const val RECENT_VERSIONS = 1

/**
 * 更新日志页。
 *
 * 内容来自仓库根目录的 `CHANGELOG.md`（构建时复制进 assets），
 * 因此这里没有第二份文案需要维护。
 *
 * 折叠策略：只展开最近几个版本，更早的收成一行标题，点开即读。
 * 变更记录会一路涨到上百版，全展开会让「这个版本改了什么」这个问题
 * 反而更难回答 —— 首屏先给最近几次，历史按需展开。
 */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var versions by remember { mutableStateOf<List<ChangelogVersion>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    // 折叠区里被逐个点开的版本
    val openedLater = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        versions = withContext(Dispatchers.IO) { ChangelogCatalog.load(context) }
        loaded = true
    }

    // 包名后缀由构建类型决定，正式版没有后缀，因此按短横线截断再比较
    val currentVersion = remember { BuildConfig.VERSION_NAME.substringBefore('-') }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "更新日志",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loaded && versions.isEmpty()) {
                item {
                    Text(
                        text = "变更记录读取失败。完整内容随包分发在 assets/changelog/ 下。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 最近几个版本直接铺开内容；更早的默认只留一行标题，点开再读
            val recent = versions.take(RECENT_VERSIONS)
            val earlier = versions.drop(RECENT_VERSIONS)

            items(recent, key = { it.version + it.channel }) { version ->
                VersionCard(
                    version = version,
                    current = version.version == currentVersion,
                    expandable = false,
                    expanded = true
                )
            }

            if (showAll) {
                items(earlier, key = { "earlier:" + it.version + it.channel }) { version ->
                    val key = version.version + version.channel
                    val open = openedLater[key] == true
                    VersionCard(
                        version = version,
                        current = version.version == currentVersion,
                        expandable = true,
                        expanded = open,
                        onToggle = { openedLater[key] = !open }
                    )
                }
            } else if (earlier.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { showAll = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("展开更早的 ${earlier.size} 个版本")
                    }
                }
            }
        }
    }
}

/**
 * 单个版本卡片。
 *
 * `expandable` 为真时整卡可点折叠；首屏的几个版本不收，直接铺开内容。
 */
@Composable
private fun VersionCard(
    version: ChangelogVersion,
    current: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (expandable && onToggle != null) Modifier.clickable { onToggle() } else Modifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = version.version,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (version.channel.isNotBlank()) {
                    Text(
                        text = version.channel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                if (current) {
                    CurrentBadge(Modifier.padding(start = 8.dp))
                }
                Box(Modifier.weight(1f))
                Text(
                    text = version.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (expandable) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(18.dp)
                            .rotate(if (expanded) 180f else 0f)
                    )
                }
            }

            // 折叠态不显示标题：折叠行是两行高，九十个版本叠起来仍然过长。
            // 版本号与日期足够定位，点开就能看到内容
            if (version.headline.isNotBlank() && (expanded || !expandable)) {
                Text(
                    text = inline(version.headline),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                    version.blocks.forEach { block -> BlockView(block) }
                }
            }
        }
    }
}

/** 「当前」标记。用色块而不是文字，是为了在列表里一眼扫到本机版本。 */
@Composable
private fun CurrentBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "当前",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BlockView(block: ChangelogBlock) {
    when (block) {
        is ChangelogBlock.Heading -> Text(
            text = inline(block.text),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp)
        )

        is ChangelogBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row {
                    // 圆点用文本而非 Canvas：行首缩进随字号自动对齐，不用手算基线
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = inline(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is ChangelogBlock.Paragraph -> Text(
            text = inline(block.text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is ChangelogBlock.Code -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(8.dp)
        )
    }
}

/** 行内 Markdown：`**加粗**` 与 `` `代码` ``，其余按原样输出。 */
private val INLINE_MARKUP = Regex("""\*\*(.+?)\*\*|`([^`]+?)`""")

@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    return remember(text, codeBg) { annotate(text, codeBg) }
}

private fun annotate(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE_MARKUP.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]
        val code = match.groups[2]
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold.value) }
            code != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            ) { append(code.value) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
