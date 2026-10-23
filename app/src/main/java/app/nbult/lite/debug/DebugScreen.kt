package app.nbult.lite.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.net.NetStatus
import app.nbult.lite.parser.ParseResult
import app.nbult.lite.parser.TimetableParser
import app.nbult.lite.ui.LocalAppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val net by container.networkMonitor.status.collectAsStateWithLifecycle()

    var sandboxText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var slotCount by remember { mutableStateOf(0) }
    var newsCount by remember { mutableStateOf(0) }
    var log by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        slotCount = container.timetableRepository.slotCount()
        newsCount = container.newsRepository.count()
    }

    val loadSample: (String) -> Unit = { asset ->
        scope.launch {
            val text = runCatching {
                context.assets.open(asset).bufferedReader().use { it.readText() }
            }.getOrElse { "" }
            sandboxText = text
            runCatching { TimetableParser.parse(text) }
                .onSuccess { result = it; error = null }
                .onFailure { result = null; error = it.message }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("调试面板", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DebugCard("网络状态模拟") {
                Text(
                    text = "当前：$net",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = net == NetStatus.ONLINE,
                        onClick = { container.networkMonitor.setDebugOverride(NetStatus.ONLINE) },
                        label = { Text("正常") }
                    )
                    FilterChip(
                        selected = net == NetStatus.OFFLINE,
                        onClick = { container.networkMonitor.setDebugOverride(NetStatus.OFFLINE) },
                        label = { Text("无网络") }
                    )
                    FilterChip(
                        selected = net == NetStatus.SOURCE_UNREACHABLE,
                        onClick = { container.networkMonitor.setDebugOverride(NetStatus.SOURCE_UNREACHABLE) },
                        label = { Text("源不可达") }
                    )
                }
                OutlinedButton(onClick = { container.networkMonitor.setDebugOverride(null) }) {
                    Text("恢复真实状态")
                }
            }

            DebugCard("解析沙盒") {
                OutlinedTextField(
                    value = sandboxText,
                    onValueChange = { sandboxText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    placeholder = { Text("粘贴课表文本或 HTML 表格") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching { TimetableParser.parse(sandboxText) }
                            .onSuccess { result = it; error = null }
                            .onFailure { result = null; error = it.message }
                    }) { Text("解析") }
                    OutlinedButton(onClick = { loadSample("samples/timetable_sample.txt") }) {
                        Text("表格样例")
                    }
                    OutlinedButton(onClick = { loadSample("samples/timetable_sample_plain.txt") }) {
                        Text("纯文本样例")
                    }
                }

                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                result?.let { r ->
                    Text(
                        "来源=${r.source} 课程=${r.courseCount} 时段=${r.slots.size} 待确认=${r.needsReview.size}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    r.slots.take(12).forEach { slot ->
                        Text(
                            text = "· ${slot.name} ${DateUtil.dayNames[slot.dayOfWeek - 1]} " +
                                "${slot.startSection}-${slot.endSection}节 " +
                                "${slot.room} ${slot.teacher} " +
                                "conf=${"%.2f".format(slot.confidence)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            DebugCard("数据与导入") {
                Text("课表时段：$slotCount 条　资讯：$newsCount 条", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val text = runCatching {
                                context.assets.open("samples/timetable_sample.txt")
                                    .bufferedReader().use { it.readText() }
                            }.getOrElse { "" }
                            runCatching {
                                val parsed = TimetableParser.parse(text)
                                container.timetableRepository.importResult(
                                    parsed = parsed,
                                    termName = "调试学期",
                                    startEpochDay = DateUtil.mondayOfWeek(DateUtil.todayEpochDay()),
                                    totalWeeks = 20
                                )
                            }.onSuccess {
                                slotCount = container.timetableRepository.slotCount()
                                log = "已导入样例课表：$it 个时段"
                            }.onFailure { log = it.message }
                        }
                    }) { Text("导入样例课表") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            container.timetableRepository.clearAll()
                            slotCount = container.timetableRepository.slotCount()
                            log = "课表已清空"
                        }
                    }) { Text("清空课表") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            container.newsRepository.clear()
                            newsCount = container.newsRepository.count()
                            log = "资讯已清空"
                        }
                    }) { Text("清空资讯") }

                    Button(onClick = {
                        scope.launch {
                            val text = runCatching {
                                context.assets.open("samples/news_sample.json")
                                    .bufferedReader().use { it.readText() }
                            }.getOrElse { "" }
                            runCatching {
                                container.newsRepository.importLocal(container.newsSource.parse(text))
                            }.onSuccess {
                                newsCount = container.newsRepository.count()
                                log = "已导入样例资讯：$it 条"
                            }.onFailure { log = it.message }
                        }
                    }) { Text("导入样例资讯") }
                }
                log?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }

            DebugCard("环境") {
                Text("包名：${context.packageName}", style = MaterialTheme.typography.labelSmall)
                Text("构建：debug=${app.nbult.lite.BuildConfig.DEBUG}", style = MaterialTheme.typography.labelSmall)
                Text("资讯源：${app.nbult.lite.data.remote.NewsConfig.FEED_URL}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DebugCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}
