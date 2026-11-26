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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.data.pack.PackCheckResult
import app.nbult.lite.data.pack.ResourcePackChecker
import app.nbult.lite.data.pack.ResourcePackStore
import app.nbult.lite.data.pack.formatPackVersion
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.net.NetStatus
import app.nbult.lite.parser.ParseResult
import app.nbult.lite.parser.TimetableParser
import app.nbult.lite.ui.LocalAppContainer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val net by container.networkMonitor.status.collectAsStateWithLifecycle()
    // 规则自检：拿包里的脱敏样例跑一遍解析，结果贴在这里。
    // 规则可能来自资源包，改了没人编译拦住，只能靠它自己说"还能跑"。
    var selfTest by remember { mutableStateOf<app.nbult.lite.parser.pdf.SchoolSelfTest.Result?>(null) }
    var selfTestRunning by remember { mutableStateOf(false) }
    var diagPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        selfTestRunning = true
        selfTest = app.nbult.lite.parser.pdf.SchoolSelfTest.run(context)
        selfTestRunning = false
    }

    var sandboxText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var slotCount by remember { mutableStateOf(0) }
    var newsCount by remember { mutableStateOf(0) }
    var log by remember { mutableStateOf<String?>(null) }
    var packState by remember { mutableStateOf(ResourcePackStore.state(context)) }
    var packLog by remember { mutableStateOf<String?>(null) }

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

            // 资源包是独立于 App 版本的另一条更新通道：不弹窗、静默生效，
            // 所以它的状态只在这里露出，让反馈时能一眼看出「用的是内置还是远端那一版」
            DebugCard("资源包") {
                Text(
                    text = if (packState.isRemote) {
                        "当前：远端 v${formatPackVersion(packState.version)}"
                    } else {
                        "当前：内置"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "上次检查：${formatCheckTime(packState.lastCheckAt)}",
                    style = MaterialTheme.typography.labelSmall
                )
                if (packState.notes.isNotBlank()) {
                    Text(packState.notes, style = MaterialTheme.typography.labelSmall)
                }
                if (packState.failedVersion > 0L) {
                    Text(
                        text = "v${formatPackVersion(packState.failedVersion)} 应用失败，已跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                // 回退只在调试面板露出：它是维护动作（新包内容坏了先止损），
                // 普通用户看到「退回上一版」只会困惑，也不该让他们替我们判断包的好坏
                val rollbackTo = ResourcePackStore.previousVersion(context)
                var confirmRollback by remember { mutableStateOf(false) }
                if (rollbackTo != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "可回退：v${formatPackVersion(rollbackTo)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { confirmRollback = true }) { Text("回退上一版") }
                    }
                }
                if (confirmRollback && rollbackTo != null) {
                    AlertDialog(
                        onDismissRequest = { confirmRollback = false },
                        title = { Text("回退资源包？") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("回到 v${formatPackVersion(rollbackTo)}，当前这版会被记为「有问题」，24 小时内不再自动重试。")
                                Text(
                                    text = "回退不解决内容问题，只是先让应用回到能用的状态。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmRollback = false
                                val target = ResourcePackStore.rollbackToPrevious(context)
                                packState = ResourcePackStore.state(context)
                                packLog = if (target != null) {
                                    "已回退到 v${formatPackVersion(target)}"
                                } else {
                                    "没有可回退的版本"
                                }
                            }) { Text("回退") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmRollback = false }) { Text("取消") }
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            packLog = "检查中…"
                            val result = ResourcePackChecker.checkAndApply(context, container.settings)
                            packState = ResourcePackStore.state(context)
                            packLog = when (result) {
                                is PackCheckResult.Applied -> "已应用 v${formatPackVersion(result.version)}"
                                PackCheckResult.UpToDate -> "已是最新"
                                PackCheckResult.Skipped -> "该版本此前失败过，已跳过"
                                is PackCheckResult.Blocked -> "需要 App ${result.minAppVersion} 及以上"
                                is PackCheckResult.StructureTooNew ->
                                    "包结构 v${result.packSchema}，本 App 只支持到 v${result.supported}，请更新 App"
                                is PackCheckResult.Failed -> "失败：${result.reason}"
                            }
                        }
                    }) { Text("立即检查资源包") }
                }
                packLog?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                // 每个数据文件的实际来源。验收「内容来自包而不是内置」靠这一栏，
                // 界面本身看不出差别（0.38.4 起 APK 内只剩空壳）
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Text("文件来源", style = MaterialTheme.typography.labelSmall)
                packSources(context).forEach { (path, source) ->
                    Text(
                        text = "$path → $source",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (source.startsWith("pack@")) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            DebugCard("规则自检") {
                val r = selfTest
                when {
                    selfTestRunning -> Text("正在自检…", style = MaterialTheme.typography.bodySmall)
                    r == null -> Text("未运行", style = MaterialTheme.typography.bodySmall)
                    !r.ran -> Text("该校未提供自检样例（${r.schoolId}）", style = MaterialTheme.typography.bodySmall)
                    r.ok -> Text(
                        "通过 · ${r.actual} 条（期望 ≥${r.expected}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    else -> Column {
                        Text(
                            "失败 · ${r.actual} 条（期望 ≥${r.expected}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (r.missing.isNotEmpty()) Text("缺少课名：${r.missing.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                        if (r.forbidden.isNotEmpty()) Text("不该出现：${r.forbidden.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                        if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "资源包里的 selftest.json 决定这项；改坏规则会在这里变红。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        diagPath = app.nbult.lite.parser.pdf.DiagSnapshotStore.export(context)?.absolutePath
                    }) { Text("导出诊断快照") }
                    Text(
                        diagPath?.substringAfterLast('/') ?: "含最近一次导入的片段与解析结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DebugCard("环境") {
                Text("包名：${context.packageName}", style = MaterialTheme.typography.labelSmall)
                Text("构建：${app.nbult.lite.BuildConfig.BUILD_KIND}（${app.nbult.lite.BuildConfig.VERSION_NAME}）", style = MaterialTheme.typography.labelSmall)
                Text("资讯源：${app.nbult.lite.data.remote.NewsConfig.FEED_URL}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 0 表示从没检查过，别显示成 1970 年。 */
private fun formatCheckTime(millis: Long): String {
    if (millis <= 0L) return "从未"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
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

/** 列出所有会走资源包的数据文件及其当前来源。学校多一个，就多几行。 */
private fun packSources(context: android.content.Context): List<Pair<String, String>> {
    val paths = LinkedHashSet<String>()
    paths += "schools/index.json"
    paths += "portal/entries.json"
    paths += "news/sources.json"
    paths += "calendar/nbu_calendar.json"
    runCatching {
        app.nbult.lite.school.SchoolCatalog.entries(context).forEach { entry ->
            paths += entry.rulesPath
            app.nbult.lite.school.SchoolCatalog.rules(context, entry.id)?.let { rules ->
                rules.calendarPath?.let { paths += it }
                rules.newsPath?.let { paths += it }
                rules.portalPath?.let { paths += it }
            }
        }
    }
    return paths.map { it to ResourcePackStore.sourceOf(context, it) }
}
