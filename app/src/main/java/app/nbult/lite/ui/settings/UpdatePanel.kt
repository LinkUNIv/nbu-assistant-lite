package app.nbult.lite.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.BuildConfig
import app.nbult.lite.data.changelog.ChangelogCatalog
import app.nbult.lite.data.pack.PackCheckResult
import app.nbult.lite.data.pack.ResourcePackChecker
import app.nbult.lite.data.pack.ResourcePackStore
import app.nbult.lite.data.pack.formatPackVersion
import app.nbult.lite.data.update.AppUpdateChecker
import app.nbult.lite.data.update.InstallState
import app.nbult.lite.data.update.UpdateCenter
import app.nbult.lite.data.update.UpdateInfo
import app.nbult.lite.data.update.UpdateState
import app.nbult.lite.ui.LocalAppContainer
import app.nbult.lite.ui.common.SmoothSwitch
import app.nbult.lite.util.ApkInstaller
import app.nbult.lite.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** 两个入口各自要不要亮红点。 */
internal data class UpdateBadges(val version: Boolean, val pack: Boolean) {
    val any: Boolean get() = version || pack
}

/**
 * 红点的判定。
 *
 * 版本：远端最新版高于当前安装版本就一直亮着 —— 更新完成后
 * `BuildConfig.VERSION_CODE` 变成新版，条件自然不成立，不需要额外清理。
 *
 * 资源：**发现**新包就亮（应用成功后仍然亮着），用户进「关于 → 资源」看过才熄灭。
 * 这样「有更新但被最低应用版本挡住」的情况也能被看见 —— 那种情况不会自动生效，
 * 如果只在应用成功后才亮，用户永远不会知道有资源更新。
 */
@Composable
internal fun rememberUpdateBadges(): UpdateBadges {
    val context = LocalContext.current
    val settings = LocalAppContainer.current.settings

    val latestCode by settings.updateLatestCode.collectAsStateWithLifecycle(0L)
    val packAck by settings.packAckVersion.collectAsStateWithLifecycle(0L)
    val packPending by settings.packPendingVersion.collectAsStateWithLifecycle(0L)
    val revisions by ResourcePackStore.revisions.collectAsStateWithLifecycle()

    val packState = remember(revisions) { ResourcePackStore.state(context) }
    val packDot = packPending > 0L || packState.version > packAck

    return UpdateBadges(
        version = latestCode > BuildConfig.VERSION_CODE,
        pack = packDot
    )
}

/** 8dp 的圆点。刻意不做动画：它表示状态，不是提醒你注意它。 */
@Composable
internal fun RedDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.error, CircleShape)
    )
}

// ---- 关于页的两个入口 ----

/** 「版本」栏里的检查更新行。 */
@Composable
internal fun VersionEntry(badge: Boolean) {
    val container = LocalAppContainer.current
    val settings = container.settings
    val installer = container.updateInstaller
    val scope = rememberCoroutineScope()
    val latestName by settings.updateLatestName.collectAsStateWithLifecycle("")
    val latestCode by settings.updateLatestCode.collectAsStateWithLifecycle(0L)
    // 进度来自应用容器，不来自这个 Composable：切页、返回都不会打断下载
    val install by installer.state.collectAsStateWithLifecycle()

    var state by remember { mutableStateOf<VersionUi>(VersionUi.Idle) }
    // 查文件系统算一次就够，别每次重组都 stat 一遍
    val cachedReady = remember(latestCode, install) { installer.cachedApk(latestCode.toInt()) }
    // 查到新版本就直接弹窗（含更新内容与体积），不再停在「发现新版本」这种什么都不发生的状态
    var dialogInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    val (title, subtitle) = when (val running = install) {
        is InstallState.Downloading -> "正在下载 v${running.versionName}" to "${running.percent}%"
        is InstallState.ReadyToInstall -> "可安装 v${running.versionName}" to "下载已完成，点击安装"
        is InstallState.HandedOff -> "等待系统安装" to "请在系统弹窗中确认安装 v${running.versionName}"
        is InstallState.Failed -> "重试更新" to running.message
        InstallState.Idle -> when (val current = state) {
            VersionUi.Idle ->
                when {
                    // 上次下好但没装（关弹窗或重启过），这里给回入口，否则等于「下了装不了」
                    latestCode > BuildConfig.VERSION_CODE && cachedReady != null ->
                        "可安装 v$latestName" to "下载已完成，点击安装"

                    latestCode > BuildConfig.VERSION_CODE ->
                        "发现新版本 $latestName" to "点击查看并下载"

                    else -> "检查更新" to "当前 v${BuildConfig.VERSION_NAME}"
                }

            VersionUi.Checking -> "检查更新" to "正在检查…"
            VersionUi.Latest -> "检查更新" to "已是最新版本"
            is VersionUi.Found -> "发现 v${current.info.versionName}" to "点击下载并安装"
            is VersionUi.Error -> "检查更新" to current.message
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        LinkRow(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            onClick = {
                when {
                    // 下载完成：这时才装
                    install is InstallState.ReadyToInstall -> installer.installNow()
                    // 内存里没状态但有包：装缓存里的那份
                    install is InstallState.Idle && state is VersionUi.Idle && cachedReady != null -> {
                        installer.installCached(latestCode.toInt(), latestName)
                    }
                    // 正在跑：连点不该再开一份
                    install is InstallState.Downloading || install is InstallState.HandedOff -> Unit
                    // 上一次失败：先清结果，再重新查一次
                    install is InstallState.Failed -> {
                        installer.reset()
                        state = VersionUi.Checking
                        scope.launch {
                            state = when (val result = UpdateCenter.refresh(settings)) {
                                is UpdateState.Available -> { dialogInfo = result.info; VersionUi.Found(result.info) }
                                UpdateState.UpToDate -> VersionUi.Latest
                                is UpdateState.Failed -> VersionUi.Error(result.message)
                            }
                        }
                    }
                    // 已经查到：弹窗，由用户点「下载」
                    state is VersionUi.Found -> dialogInfo = (state as VersionUi.Found).info
                    state is VersionUi.Checking -> Unit
                    else -> {
                        state = VersionUi.Checking
                        scope.launch {
                            state = when (val result = UpdateCenter.refresh(settings)) {
                                // 检查到就弹窗 —— 内容、体积、下载按钮一次给全
                                is UpdateState.Available -> { dialogInfo = result.info; VersionUi.Found(result.info) }
                                UpdateState.UpToDate -> VersionUi.Latest
                                is UpdateState.Failed -> VersionUi.Error(result.message)
                            }
                        }
                    }
                }
            }
        )
        if (badge) {
            Spacer(Modifier.width(8.dp))
            RedDot()
        }
    }
    dialogInfo?.let { info ->
        UpdateDialog(
            info = info,
            install = install,
            onDownload = { installer.start(info) },
            onInstall = { installer.installNow() },
            onCancel = { dialogInfo = null }
        )
    }
}

/** 「版本」栏里检查更新下面那行开关：只管自动弹窗，不拦手动检查。 */
@Composable
internal fun UpdatePopupEntry() {
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    val enabled by settings.updatePopupEnabled.collectAsStateWithLifecycle(initialValue = true)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("更新弹窗", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "启动时检查到新版本自动提醒；关闭后仍可在这里手动检查",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SmoothSwitch(
            checked = enabled,
            onCheckedChange = { on ->
                AppLog.log("Update", "弹窗开关 ${if (on) "on" else "off"} 来源=关于页")
                scope.launch { settings.setUpdatePopupEnabled(on) }
            }
        )
    }
}

/** 「资源」栏：当前版本、来源、上次检查与手动检查。 */
@Composable
internal fun PackEntry() {
    val context = LocalContext.current
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    val revisions by ResourcePackStore.revisions.collectAsStateWithLifecycle()
    val state = remember(revisions) { ResourcePackStore.state(context) }
    var log by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 进到这一栏就算「看过了」，红点熄灭
    LaunchedEffect(revisions) { settings.setPackAck(state.version) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (state.isRemote) "当前：远端 v${formatPackVersion(state.version)}" else "当前：内置",
            style = MaterialTheme.typography.bodyMedium
        )
        // 显示「上次尝试」而不是「上次成功」：后者会让人以为根本没查过
        Text(
            text = "上次检查：${formatCheckTime(if (state.lastAttemptAt > 0L) state.lastAttemptAt else state.lastCheckAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.notes.isNotBlank()) {
            Text(
                text = state.notes,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.failedVersion > 0L) {
            // 把原因原样显示出来。只写「失败」等于让用户带着一个问号来反馈，
            // 而这句话恰好就是上一次定位问题丢掉的那条信息
            Text(
                text = "v${formatPackVersion(state.failedVersion)} 应用失败：" +
                    state.failedReason.ifBlank { "未知原因" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        TextButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    // 手动检查一律 force：绕过 1 小时间隔与「此前失败」标记，
                    // 否则用户点了按钮也只会再看到一次「已跳过」
                    log = when (val result = ResourcePackChecker.checkAndApply(context, settings, force = true)) {
                        is PackCheckResult.Applied -> "已应用 v${formatPackVersion(result.version)}"
                        PackCheckResult.UpToDate -> "已是最新"
                        PackCheckResult.Skipped -> "该版本此前失败过，已跳过"
                        is PackCheckResult.Blocked -> "需要应用更新到 ${result.minAppVersion} 以上才能使用"
                        is PackCheckResult.Failed -> "失败：${result.reason}"
                    }
                    busy = false
                }
            }
        ) {
            Text(
                when {
                    busy -> "检查中…"
                    state.failedVersion > 0L -> "重试"
                    else -> "立即检查资源包"
                }
            )
        }

        log?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

// ---- 更新弹窗 ----

/** 清单没给体积时不要编一个数字，如实说不知道。 */
private fun formatApkSize(bytes: Long): String = when {
    bytes <= 0L -> "安装包大小未知"
    bytes >= 1024 * 1024 -> "安装包 ${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    else -> "安装包 ${bytes / 1024} KB"
}

/**
 * 发现新版本时的弹窗。
 *
 * 正文直接放该版发布说明全文，可滚动：说明是手写的，压缩成三行会丢掉「已知问题」，
 * 而那恰恰是用户最该先看到的部分。
 */
@Composable
internal fun UpdateDialog(
    info: UpdateInfo,
    install: InstallState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit
) {
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    var noRemind by remember { mutableStateOf(false) }

    // 勾了「不再提醒」就是一个独立意愿，点确认还是取消都该记住
    fun rememberChoice() {
        if (noRemind) {
            scope.launch { settings.setUpdateDismissed(info.versionCode.toLong()) }
            AppLog.log("Update", "勾选不再提醒 ${info.versionName}")
        }
    }

    Dialog(onDismissRequest = { rememberChoice(); AppLog.log("Update", "取消更新 ${info.versionName}"); onCancel() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "发现新版本 ${info.versionName}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                // 体积写在标题下面：下载前就该知道要花多少流量，而不是等进度条出现才发现
                Text(
                    text = formatApkSize(info.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                // 说明是 Markdown（Release 正文原样同步）。以前直接当纯文本塞进 Text，
                // 于是 `**加粗**`、`- `、反引号全都原样显示出来，字号还只有 12sp。
                val (notesTitle, notesBlocks) = remember(info.notes) {
                    ChangelogCatalog.parseFragment(info.notes)
                }
                NotesScrollArea {
                    if (notesTitle.isBlank() && notesBlocks.isEmpty()) {
                        Text(
                            text = "本版本未提供更新说明。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        if (notesTitle.isNotBlank()) {
                            Text(
                                text = inline(notesTitle),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        notesBlocks.forEach { block ->
                            ChangelogBlockView(block, MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = noRemind, onCheckedChange = { noRemind = it })
                    Text(
                        text = "不再提醒这个版本",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val downloading = install as? InstallState.Downloading
                if (downloading != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { downloading.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        rememberChoice()
                        AppLog.log("Update", "关闭更新弹窗 ${info.versionName}")
                        onCancel()
                    }) { Text(if (downloading != null) "后台下载" else "取消") }

                    Spacer(Modifier.width(4.dp))
                    Button(
                        enabled = install !is InstallState.HandedOff,
                        onClick = {
                            rememberChoice()
                            when (install) {
                                is InstallState.ReadyToInstall -> {
                                    AppLog.log("Update", "安装 ${info.versionName}")
                                    onInstall()
                                }
                                is InstallState.Downloading -> Unit
                                else -> {
                                    AppLog.log("Update", "开始下载 ${info.versionName}")
                                    onDownload()
                                }
                            }
                        }
                    ) {
                        Text(
                            when (install) {
                                is InstallState.Downloading -> "下载中 ${install.percent}%"
                                is InstallState.ReadyToInstall -> "安装"
                                is InstallState.HandedOff -> "等待系统安装"
                                else -> "下载"
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---- 内部 ----

/**
 * 说明的滚动区。
 *
 * 内容超长时底部渐隐并提示可以下滑 —— 只做滚动不给提示，用户会以为内容被截断了
 * （0.37.8 的弹窗就是这个观感）。
 */
@Composable
private fun NotesScrollArea(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    val canScroll by remember { derivedStateOf { scrollState.canScrollForward } }
    Box(
        Modifier
            .fillMaxWidth()
            // 限高后滚动，避免弹窗顶到状态栏
            .heightIn(max = 380.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
        if (canScroll) {
            // 渐变的终点必须和弹窗真实底色一致：外层 Surface 带 tonalElevation = 3.dp，
            // 直接拿 colorScheme.surface 会在滚动时露出一条浅色接缝
            val fadeTo = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, fadeTo))
                    )
            )
            Text(
                text = "下滑查看全部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
            )
        }
    }
}

/**
 * 「这一版更新了什么」。
 *
 * 与 [UpdateDialog] 是两回事：那个是「远端有个新版本，要不要下」，
 * 这个是「你已经装上并正在运行这一版」。混用会让人分不清自己在看哪一份说明。
 */
@Composable
internal fun WhatsNewDialog(versionName: String, notes: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "已更新到 $versionName",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(10.dp))
                val (notesTitle, notesBlocks) = remember(notes) {
                    ChangelogCatalog.parseFragment(notes)
                }
                NotesScrollArea {
                    if (notesTitle.isNotBlank()) {
                        Text(text = inline(notesTitle), style = MaterialTheme.typography.titleSmall)
                    }
                    notesBlocks.forEach { block ->
                        ChangelogBlockView(block, MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("知道了") }
                }
            }
        }
    }
}

private sealed interface VersionUi {
    data object Idle : VersionUi
    data object Checking : VersionUi
    data object Latest : VersionUi
    data class Found(val info: UpdateInfo) : VersionUi
    data class Error(val message: String) : VersionUi
}

/** 0 表示从没检查过，别显示成 1970 年。 */
private fun formatCheckTime(millis: Long): String {
    if (millis <= 0L) return "从未"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
