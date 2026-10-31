package app.nbult.lite.ui.settings

import android.content.Intent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.data.update.AppUpdateChecker
import app.nbult.lite.data.update.UpdateInfo
import app.nbult.lite.data.update.UpdateState
import app.nbult.lite.util.ApkInstaller
import app.nbult.lite.BuildConfig
import app.nbult.lite.data.SettingsStore
import app.nbult.lite.ui.common.SmoothSwitch
import app.nbult.lite.ui.theme.DarkMode
import app.nbult.lite.ui.theme.SeedColor
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
    onOpenDebug: () -> Unit,
    onOpenAppearance: () -> Unit,
    onNavigateSection: (String) -> Unit = {},
    onBack: () -> Unit = {},
    /** null = 入口列表；否则只渲染对应那一段。 */
    section: String? = null
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
    val motionBlur by settings.motionBlur.collectAsStateWithLifecycle(
        initialValue = settings.lastKnownMotionBlur
    )
    // 入口页的「状态回显」用：不点进去也要能看出当前是什么设置
    val reminderOn by settings.classReminder.collectAsStateWithLifecycle(
        initialValue = settings.lastKnownClassReminder
    )
    val themeColor by settings.themeColor.collectAsStateWithLifecycle(initialValue = "blue")
    val darkModeId by settings.darkMode.collectAsStateWithLifecycle(initialValue = "system")
    val campusPreset by settings.campusPreset.collectAsStateWithLifecycle(initialValue = "main")
    var showClearConfirm by remember { mutableStateOf(false) }

    val totalWeeks = (state.term?.totalWeeks ?: 20).coerceAtLeast(1)

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
            // 顶部 22dp = 原 16 + 6：与右侧圆钮的中线对齐
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (section == null) {
            Text("我的", style = MaterialTheme.typography.headlineSmall)

            // 概览三个数字，回答「现在第几周 / 本周几节课 / 提醒开没开」——
            // 之前这三件事都得点进去才知道，设置页最容易缺的就是这一层
            SettingsOverview(
                week = state.week,
                totalWeeks = totalWeeks,
                weekSections = state.currentWeekEntries.size,
                reminderOn = reminderOn
            )

            // 右侧一律显示当前值：设置项只有带上状态才像「设置」，
            // 否则六张一模一样的白卡读起来像六个按钮，分不出轻重
            SettingsEntry(
                title = "外观",
                subtitle = "主题色、深浅模式、背景、课表布局",
                trailing = "${SeedColor.of(themeColor).label} · ${DarkMode.of(darkModeId).label}"
            ) { onOpenAppearance() }
            SettingsEntry(
                title = "学期",
                subtitle = "开学日期、总周数、来源",
                trailing = "第 ${state.week} / $totalWeeks 周"
            ) { onNavigateSection("term") }
            SettingsEntry(
                title = "上课提醒",
                subtitle = "课前提醒、课堂常驻",
                trailing = if (reminderOn) "已开启" else "未开启"
            ) { onNavigateSection("reminder") }
            SettingsEntry(
                title = "时间与作息",
                subtitle = "校时、上下课时间",
                trailing = if (campusPreset == "meishan") "梅山校区" else "主校区"
            ) { onNavigateSection("time") }
            SettingsEntry(
                title = "数据与诊断",
                subtitle = "清空课表、恢复出厂、工作日志",
                trailing = "$logCount 条日志"
            ) { onNavigateSection("more") }
            SettingsEntry(
                title = "关于",
                subtitle = "开源许可、调试面板",
                trailing = "v${BuildConfig.VERSION_NAME}"
            ) { onNavigateSection("about") }
        } else {
            SettingsSectionHeader(title = sectionTitle(section), onBack = onBack)

            if (section == "term") {
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
            }

            if (section == "reminder") {

        ReminderCard()

        LiveUpdateStateCard()

        BackgroundRunCard()
            }

            if (section == "time") {
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
            }

            if (section == "time") {
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
            TextButton(onClick = { onNavigateSection("schedule") }) {
                Text("逐节编辑")
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
            }

            if (section == "more") {
SettingCard(title = "数据") {
            Text(
                text = "只删除课表本身，作息、提醒、外观等设置都会保留。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showClearConfirm = true }) {
                Text("清空当前课表", color = MaterialTheme.colorScheme.error)
            }
        }
            }

            if (section == "more") {
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
            }

            if (section == "more") {
SettingCard(title = "高级") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("精致动效（切页淡入缩放）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "关闭时切页仅左右横移，高刷屏更流畅；开启会叠加淡入与轻微缩放。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SmoothSwitch(
                    checked = motionBlur,
                    onCheckedChange = { on ->
                        AppLog.log("Settings", "精致动效=$on")
                        scope.launch { settings.setMotionBlur(on) }
                    }
                )
            }

        }
            }

            if (section == "more") {
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
            }

            if (section == "about") {
SettingCard(title = "关于") {
            Text(
                text = "宁大助手 Lite 是非官方第三方工具，与宁波大学无隶属关系。应用不收集个人信息、不需要登录、不保存账号密码。资讯内容来自宁波大学新闻网公开发布内容，仅展示标题、摘要与原文链接，版权归原作者与学校所有。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 许可全文与变更记录都随包分发，入口进独立页。
            // 摘要不再重复写在这里 —— 两处都写，换字体或改版本时必然改一处忘一处
            LinkRow(
                title = "开源许可",
                subtitle = "字体与运行时依赖的许可全文",
                onClick = { onNavigateSection("licenses") }
            )
            LinkRow(
                title = "更新日志",
                subtitle = "各版本的改动说明，最新在前",
                onClick = { onNavigateSection("changelog") }
            )
            UpdateEntry()
            // 版本号放在「关于」页里，因为用户反馈问题时常被要求「报一下版本」，
            // 截图这一页最方便。入口列表里显示在「关于」行右侧，两处不在同一屏，不算重复
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
            }

            if (section == "about") {
SettingCard(title = "调试") {
                TextButton(onClick = onOpenDebug) { Text("打开调试面板") }
            }
            }
        }


        // 版本号只保留在「关于」行右侧一处。原来这里还有一行「版本 0.30.x」，
        // 和右侧重复了 —— 同一个信息出现两次，读者会以为其中一个是别的东西
    }

    // 清空课表要确认：它和「恢复出厂」一样不可撤销，但入口更靠前、更容易误触
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空当前课表？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("将删除全部课程，以及基于它们做的调课与停课记录，无法撤销。")
                    Text(
                        text = "作息、提醒、外观等设置会保留。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.log("Settings", "清空课表（已确认）")
                    showClearConfirm = false
                    timetableVm.clearAll()
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
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

/** 「检查更新」在界面上要表达的状态。 */
private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object Latest : UpdateUi
    data class Found(val info: UpdateInfo) : UpdateUi
    data class Downloading(val percent: Int, val info: UpdateInfo) : UpdateUi
    data class Error(val message: String) : UpdateUi
}

/**
 * 检查更新。
 *
 * 一个入口承担「检查 → 下载 → 交给安装器」三步：这三步是同一件事的三个阶段，
 * 拆成三个按钮只会让用户在中间态无所适从。
 *
 * 下载前不弹二次确认 —— 用户点「发现新版本」这个动作本身就是要装。
 */
@Composable
private fun UpdateEntry() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }

    val (title, subtitle) = when (val current = state) {
        UpdateUi.Idle -> "检查更新" to "当前 v${BuildConfig.VERSION_NAME}"
        UpdateUi.Checking -> "检查更新" to "正在检查…"
        UpdateUi.Latest -> "检查更新" to "已是最新版本"
        is UpdateUi.Found -> "发现 v${current.info.versionName}" to "点击下载并安装"
        is UpdateUi.Downloading -> "正在下载 v${current.info.versionName}" to "${current.percent}%"
        is UpdateUi.Error -> "检查更新" to current.message
    }

    LinkRow(
        title = title,
        subtitle = subtitle,
        onClick = {
            when (val current = state) {
                is UpdateUi.Found -> {
                    state = UpdateUi.Downloading(0, current.info)
                    scope.launch {
                        runCatching {
                            val apk = AppUpdateChecker.download(context, current.info) { percent ->
                                state = UpdateUi.Downloading(percent, current.info)
                            }
                            if (!ApkInstaller.install(context, apk)) {
                                state = UpdateUi.Error("请在系统设置中允许安装未知应用")
                            }
                        }.onFailure {
                            state = UpdateUi.Error(it.message ?: "下载失败")
                        }
                    }
                }

                UpdateUi.Checking, is UpdateUi.Downloading -> Unit

                else -> {
                    state = UpdateUi.Checking
                    scope.launch {
                        state = when (val result = AppUpdateChecker.check()) {
                            is UpdateState.Available -> UpdateUi.Found(result.info)
                            UpdateState.UpToDate -> UpdateUi.Latest
                            is UpdateState.Failed -> UpdateUi.Error(result.message)
                        }
                    }
                }
            }
        }
    )
}

/**
 * 跳转到子页面的行：左侧标题与说明，右侧箭头。
 *
 * 「关于」页里已有两条这样的入口，样式必须完全一致 ——
 * 复制一份出来改，早晚会有一条忘了改内边距。
 */
@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SettingCard(title: String, content: @Composable () -> Unit) {
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

/**
 * 入口行。整行可点，右侧显示「当前值 + 箭头」。
 *
 * 带 trailing 是为了让六条设置读起来是「设置」而不是「六个按钮」：
 * 不点进去也能看出主题色是什么、提醒开没开。
 */
@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    trailing: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 右侧带了值之后可用宽度变窄，副标题必须单行省略，
                    // 否则「调试面板」会被折成两行、把整行撑高
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 入口页顶部概览。
 *
 * 三个数字回答三件最常被问的事：现在第几周、本周几节课、提醒开没开。
 * 用 `primaryContainer` 是为了和下面六张白卡拉开层级 —— 它是"信息"，
 * 下面那些是"入口"。
 */
@Composable
private fun SettingsOverview(week: Int, totalWeeks: Int, weekSections: Int, reminderOn: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OverviewStat("第 $week 周", "共 $totalWeeks 周")
            OverviewStat("$weekSections 节", "本周课程")
            OverviewStat(if (reminderOn) "已开启" else "未开启", "上课提醒")
        }
    }
}

@Composable
private fun OverviewStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

private fun sectionTitle(section: String): String = when (section) {
    "term" -> "学期"
    "reminder" -> "上课提醒"
    "time" -> "时间与作息"
    "more" -> "数据与诊断"
    "about" -> "关于"
    else -> "设置"
}
