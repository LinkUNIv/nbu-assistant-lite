package app.nbult.lite.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.BuildConfig
import app.nbult.lite.data.pack.PackCheckResult
import app.nbult.lite.data.pack.ResourcePackChecker
import app.nbult.lite.data.pack.ResourcePackStore
import app.nbult.lite.data.pack.formatPackVersion
import app.nbult.lite.data.update.AppUpdateChecker
import app.nbult.lite.data.update.UpdateCenter
import app.nbult.lite.data.update.UpdateInfo
import app.nbult.lite.data.update.UpdateState
import app.nbult.lite.ui.LocalAppContainer
import app.nbult.lite.util.ApkInstaller
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val context = LocalContext.current
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    val latestName by settings.updateLatestName.collectAsStateWithLifecycle("")
    val latestCode by settings.updateLatestCode.collectAsStateWithLifecycle(0L)

    var state by remember { mutableStateOf<VersionUi>(VersionUi.Idle) }

    val (title, subtitle) = when (val current = state) {
        VersionUi.Idle ->
            if (latestCode > BuildConfig.VERSION_CODE) {
                "发现新版本 $latestName" to "点击下载并安装"
            } else {
                "检查更新" to "当前 v${BuildConfig.VERSION_NAME}"
            }

        VersionUi.Checking -> "检查更新" to "正在检查…"
        VersionUi.Latest -> "检查更新" to "已是最新版本"
        is VersionUi.Found -> "发现 v${current.info.versionName}" to "点击下载并安装"
        is VersionUi.Downloading -> "正在下载 v${current.info.versionName}" to "${current.percent}%"
        is VersionUi.Error -> "检查更新" to current.message
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        LinkRow(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            onClick = {
                when (val current = state) {
                    is VersionUi.Found -> {
                        state = VersionUi.Downloading(0, current.info)
                        scope.launch {
                            install(context, current.info) { percent ->
                                state = VersionUi.Downloading(percent, current.info)
                            }.let { message -> if (message != null) state = VersionUi.Error(message) }
                        }
                    }

                    VersionUi.Checking, is VersionUi.Downloading -> Unit

                    else -> {
                        state = VersionUi.Checking
                        scope.launch {
                            state = when (val result = UpdateCenter.refresh(settings)) {
                                is UpdateState.Available -> VersionUi.Found(result.info)
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
        Text(
            text = "上次检查：${formatCheckTime(state.lastCheckAt)}",
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
            Text(
                text = "v${formatPackVersion(state.failedVersion)} 应用失败，已跳过",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        TextButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    log = when (val result = ResourcePackChecker.checkAndApply(context, settings)) {
                        is PackCheckResult.Applied -> "已应用 v${formatPackVersion(result.version)}"
                        PackCheckResult.UpToDate -> "已是最新"
                        PackCheckResult.Skipped -> "该版本此前失败过，已跳过"
                        is PackCheckResult.Blocked -> "需要应用更新到 ${result.minAppVersion} 以上才能使用"
                        is PackCheckResult.Failed -> "失败：${result.reason}"
                    }
                    busy = false
                }
            }
        ) { Text(if (busy) "检查中…" else "立即检查资源包") }

        log?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

// ---- 更新弹窗 ----

/**
 * 发现新版本时的弹窗。
 *
 * 正文直接放该版发布说明全文，可滚动：说明是手写的，压缩成三行会丢掉「已知问题」，
 * 而那恰恰是用户最该先看到的部分。
 */
@Composable
internal fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
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
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 说明可能很长，限高后滚动，避免弹窗顶到状态栏
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = info.notes.ifBlank { "本版本未提供更新说明。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { settings.setUpdateDismissed(info.versionCode.toLong()) }
                        onDismiss()
                    }) { Text("以后再说") }

                    Spacer(Modifier.width(4.dp))
                    Button(
                        enabled = progress < 0,
                        onClick = {
                            progress = 0
                            scope.launch {
                                val message = install(context, info) { progress = it }
                                if (message != null) {
                                    error = message
                                    progress = -1
                                }
                            }
                        }
                    ) {
                        Text(if (progress < 0) "立即更新" else "下载中 $progress%")
                    }
                }
            }
        }
    }
}

// ---- 内部 ----

private sealed interface VersionUi {
    data object Idle : VersionUi
    data object Checking : VersionUi
    data object Latest : VersionUi
    data class Found(val info: UpdateInfo) : VersionUi
    data class Downloading(val percent: Int, val info: UpdateInfo) : VersionUi
    data class Error(val message: String) : VersionUi
}

/**
 * 下载并交给系统安装器。返回 null 表示已交给系统，返回字符串表示失败原因。
 *
 * 签名不一致单独判：系统只会给出「安装失败(-7)」，不说人话。
 */
private suspend fun install(
    context: android.content.Context,
    info: UpdateInfo,
    onProgress: (Int) -> Unit
): String? = runCatching {
    val apk = AppUpdateChecker.download(context, info) { onProgress(it) }
    when {
        ApkInstaller.signatureMismatch(context, apk) -> "与已安装版本签名不同，需先卸载再安装"
        !ApkInstaller.install(context, apk) -> "请在系统设置中允许安装未知应用"
        else -> null
    }
}.getOrElse { it.message ?: "下载失败" }

/** 0 表示从没检查过，别显示成 1970 年。 */
private fun formatCheckTime(millis: Long): String {
    if (millis <= 0L) return "从未"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
