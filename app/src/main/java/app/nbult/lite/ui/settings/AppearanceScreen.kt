package app.nbult.lite.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.ui.LocalAppContainer
import app.nbult.lite.ui.common.luminanceIsDark
import app.nbult.lite.ui.theme.Backdrop
import app.nbult.lite.ui.theme.DarkMode
import app.nbult.lite.ui.theme.SeedColor
import app.nbult.lite.ui.theme.brushFor
import kotlinx.coroutines.launch

/**
 * 外观设置。
 *
 * 三组互不相干的选择：主题色、深浅、背景。
 * 每改一项立刻写进 DataStore 并全局生效 —— 没有"应用"按钮，
 * 因为这三样都是所见即所得，选完就能看到结果。
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val themeColorId by container.settings.themeColor
        .collectAsStateWithLifecycle(initialValue = "blue")
    val darkModeId by container.settings.darkMode
        .collectAsStateWithLifecycle(initialValue = "system")
    val backdropId by container.settings.backdrop
        .collectAsStateWithLifecycle(initialValue = "plain")
    val showFullTimetable by container.settings.showFullTimetable
        .collectAsStateWithLifecycle(initialValue = false)

    val background = MaterialTheme.colorScheme.background
    val dark = background.luminanceIsDark()

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
                text = "外观",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCard(title = "主题色") {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SeedColor.entries.forEach { seed ->
                        val selected = seed.id == themeColorId
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (dark) seed.dark else seed.light)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { scope.launch { container.settings.setThemeColor(seed.id) } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = seed.label,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            SettingCard(title = "深浅模式") {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DarkMode.entries.forEach { mode ->
                        val selected = mode.id == darkModeId
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { scope.launch { container.settings.setDarkMode(mode.id) } }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            SettingCard(title = "背景") {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Backdrop.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { item ->
                                BackdropTile(
                                    backdrop = item,
                                    selected = item.id == backdropId,
                                    dark = dark,
                                    background = background,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { container.settings.setBackdrop(item.id) }
                                    }
                                )
                            }
                            if (pair.size == 1) {
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // 一个开关同时管两个轴：列（周末）与行（节次）。
            // 分成两个开关会让人以为可以"只显示周末不显示节次"，那不成立
            SettingCard(title = "课表布局") {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "显示部分", true to "显示全部").forEach { (full, label) ->
                            val selected = full == showFullTimetable
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        scope.launch {
                                            container.settings.setShowFullTimetable(full)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Text(
                        // 两个模式各自的取舍都写出来，避免"为什么周末不见了"这类疑问
                        text = if (showFullTimetable) {
                            "显示完整时间安排下的周内课程分布，牺牲部分宽度。"
                        } else {
                            "不显示无课的周末和本周最晚的一节课之后的课程。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "背景目前仅提供纯色和三款渐变，自定义图片将于后续版本加入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun BackdropTile(
    backdrop: Backdrop,
    selected: Boolean,
    dark: Boolean,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(backdrop.brushFor(dark, background))
        )
        Text(
            text = backdrop.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}
