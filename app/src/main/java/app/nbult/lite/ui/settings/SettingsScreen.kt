package app.nbult.lite.ui.settings

import android.content.Intent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.BuildConfig
import app.nbult.lite.data.SettingsStore
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.DateUtil
import app.nbult.lite.domain.PeriodPresets
import app.nbult.lite.ui.LocalAppContainer
import app.nbult.lite.ui.timetable.TimetableViewModel
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    timetableVm: TimetableViewModel,
    onOpenDebug: () -> Unit
) {
    val state by timetableVm.state.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val settings: SettingsStore = container.settings

    var showDatePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var timeOffset by remember { mutableStateOf(0L) }
    var lastSync by remember { mutableStateOf(0L) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val appContext = LocalContext.current
    var logPath by remember { mutableStateOf<String?>(null) }
    var logCount by remember { mutableStateOf(0) }
    var autoSource by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }
    var resolveMessage by remember { mutableStateOf<String?>(null) }
    var showFactoryReset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    val motionBlur by settings.motionBlur.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(Unit) {
        timeOffset = container.settings.timeOffset.first()
        lastSync = container.settings.lastTimeSync.first()
        autoSource = container.settings.autoTermSource.first()
        logCount = AppLog.entries().size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)

        SettingCard(title = "学期设置") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("开学第一周周一", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = state.term?.let { DateUtil.formatEpochDay(it.startEpochDay) } ?: "未设置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "第 ${state.week} 周",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("学期总周数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    state.term?.let { timetableVm.updateTotalWeeks(it.totalWeeks - 1) }
                }) { Icon(Icons.Outlined.Remove, contentDescription = "减少") }
                Text("${state.term?.totalWeeks ?: 20}", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    state.term?.let { timetableVm.updateTotalWeeks(it.totalWeeks + 1) }
                }) { Icon(Icons.Outlined.Add, contentDescription = "增加") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("开学时间来源", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = if (autoSource.isBlank()) {
                            "手动设置或尚未识别"
                        } else {
                            "来源：$autoSource（校历优先于新闻识别）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    if (resolving) return@TextButton
                    resolving = true
                    resolveMessage = "优先读取校历，其次抓取新闻…"
                    timetableVm.refreshTermStart()
                    scope.launch {
                        kotlinx.coroutines.delay(600)
                        autoSource = container.settings.autoTermSource.first()
                        resolveMessage = "开学时间已更新为 ${DateUtil.formatEpochDay(state.term?.startEpochDay ?: 0L)}"
                        resolving = false
                    }
                }) { Text(if (resolving) "识别中…" else "自动识别") }
            }
            resolveMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        SettingCard(title = "数据") {
            TextButton(onClick = { timetableVm.clearAll() }) {
                Text("清空当前课表", color = MaterialTheme.colorScheme.error)
            }
        }

        SettingCard(title = "存储与重置") {
            Text(
                text = "清除本机全部数据，回到首次安装状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showFactoryReset = true }, enabled = !resetting) {
                Text(
                    text = if (resetting) "正在恢复…" else "恢复出厂设置",
                    color = MaterialTheme.colorScheme.error
                )
            }
            resetMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        SettingCard(title = "高级") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("精致动效（切页高斯模糊）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "关闭时切页仅左右横移，高刷屏更流畅；开启会增加一帧离屏渲染",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = motionBlur,
                    onCheckedChange = { scope.launch { settings.setMotionBlur(it) } }
                )
            }

        }

        SettingCard(title = "时间与时区") {
            Text(
                text = "设备时区：${AppClock.deviceZoneId()}${if (AppClock.deviceIsShanghai()) "（东八区）" else "（非东八区）"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "课表计算固定按东八区（UTC+8），避免跨时区或设备时区异常导致「今天第几周」算错。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!AppClock.deviceIsShanghai()) {
                Text(
                    text = "当前设备不在东八区，日期已按东八区自动换算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = if (lastSync == 0L) "尚未校准服务器时间" else
                    "上次校时：${DateUtil.formatTime(lastSync)}　偏差 ${timeOffset / 1000} 秒",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (syncing) return@TextButton
                    syncing = true
                    scope.launch {
                        container.timeSync.sync()
                            .onSuccess { result ->
                                AppClock.setOffset(result.offsetMillis)
                                container.settings.setTimeOffset(
                                    result.offsetMillis,
                                    System.currentTimeMillis()
                                )
                                timeOffset = result.offsetMillis
                                lastSync = System.currentTimeMillis()
                                syncMessage = "服务器时间 ${DateUtil.formatTime(result.serverMillis)}"
                            }
                            .onFailure { syncMessage = "校时失败：${it.message}" }
                        syncing = false
                    }
                }) { Text(if (syncing) "校时中…" else "立即校时") }
                TextButton(onClick = {
                    scope.launch {
                        AppClock.setOffset(0L)
                        container.settings.setTimeOffset(0L, 0L)
                        timeOffset = 0L
                        lastSync = 0L
                        syncMessage = "已恢复使用设备时间"
                    }
                }) { Text("恢复设备时间") }
            }
            syncMessage?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingCard(title = "作息时间") {
            Text(
                text = "当前 ${state.periods.size} 节：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.periods.take(12).chunked(3).forEach { row ->
                Text(
                    text = row.joinToString("　") { "${it.idx}. ${it.startTime}-${it.endTime}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    timetableVm.applyPeriodPreset(PeriodPresets.HEADQUARTERS.periods)
                }) { Text("主校区") }
                TextButton(onClick = {
                    timetableVm.applyPeriodPreset(PeriodPresets.MEISHAN.periods)
                }) { Text("梅山校区") }
            }
            Text(
                text = "数据依据学校公布的校历上课时间表。本部 5/6/8 号楼有单双层错时打铃，如有需要可切换校区模板后逐节核对。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingCard(title = "关于") {
            Text(
                text = "宁大助手 Lite 是非官方第三方工具，与宁波大学无隶属关系。应用不收集个人信息、不需要登录、不保存账号密码。资讯内容来自宁波大学新闻网公开发布内容，仅展示标题、摘要与原文链接，版权归原作者与学校所有。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (BuildConfig.DEBUG) {
            SettingCard(title = "调试") {
                TextButton(onClick = onOpenDebug) { Text("打开调试面板") }
            }
        }

        SettingCard(title = "工作日志") {
            Text(
                text = "记录网络状态、解析结果与网页加载过程，用于排查问题。当前 $logCount 条；日志与导出文件保留 3 天、最多 5 个文件，超期自动删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (remember(logCount) { AppLog.hasCrashRecord() }) {
                Text(
                    text = "上次运行出现过崩溃，导出的日志末尾附带完整堆栈（版本 / 机型 / 线程 / 调用栈）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        val file = runCatching { AppLog.export(appContext) }.getOrElse { null }
                        logCount = AppLog.entries().size
                        logPath = file?.absolutePath ?: "导出失败"
                    }
                }) { Text("导出日志") }
                TextButton(onClick = {
                    AppLog.clear()
                    logCount = 0
                    logPath = null
                }) { Text("清空") }
            }
        }

        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (showFactoryReset) {
        AlertDialog(
            onDismissRequest = { if (!resetting) showFactoryReset = false },
            title = { Text("恢复出厂设置？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("将删除以下内容，且无法撤销：")
                    Text(
                        "· 全部课程与周次设置\n" +
                            "· 已缓存的资讯\n" +
                            "· 作息时间、开学时间等全部设置\n" +
                            "· 网页缓存与登录状态\n" +
                            "· 工作日志与导出文件",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "清除后应用回到首次安装状态。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetting = true
                        scope.launch {
                            runCatching { container.factoryReset() }
                                .onSuccess { resetMessage = "已恢复出厂设置" }
                                .onFailure { resetMessage = "恢复失败：${it.message}" }
                            logCount = 0
                            logPath = null
                            autoSource = ""
                            timeOffset = 0L
                            lastSync = 0L
                            resetting = false
                            showFactoryReset = false
                        }
                    }
                ) {
                    Text(if (resetting) "清除中…" else "确认清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryReset = false }, enabled = !resetting) {
                    Text("取消")
                }
            }
        )
    }

    val exported = logPath
    if (exported != null) {
        AlertDialog(
            onDismissRequest = { logPath = null },
            title = { Text("日志已导出") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(exported, style = MaterialTheme.typography.labelSmall)
                    Text(
                        "路径位于应用专属目录，可通过「分享」发送出来。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "宁大助手 Lite 工作日志")
                            putExtra(Intent.EXTRA_TEXT, AppLog.dump())
                        }
                        appContext.startActivity(Intent.createChooser(send, "分享日志"))
                    }
                    logPath = null
                }) { Text("分享") }
            },
            dismissButton = {
                TextButton(onClick = { logPath = null }) { Text("关闭") }
            }
        )
    }

    if (showDatePicker && state.term != null) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.term!!.startEpochDay * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        timetableVm.updateTermStart(Math.floorDiv(millis, 86_400_000L))
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            content()
        }
    }
}
