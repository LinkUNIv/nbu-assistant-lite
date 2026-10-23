package app.nbult.lite.ui.timetable

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.nbult.lite.util.NbuUrls
import app.nbult.lite.util.openInBrowser
import app.nbult.lite.parser.ParseResult
import app.nbult.lite.parser.ParseSource

private val IMPORT_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "application/rtf",
    "text/rtf",
    "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportContent(
    preview: ParseResult?,
    previewError: String?,
    importing: Boolean,
    onFile: (String, ByteArray) -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
    onManualAdd: () -> Unit
) {
    var pickedName by remember { mutableStateOf<String?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }
    var jumpTip by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取该文件")
            val name = displayName(context, uri)
            pickedName = name
            readError = null
            onFile(name, bytes)
        }.onFailure {
            readError = it.message ?: "读取文件失败"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("导入课表", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "两种方式：导入教务导出的 Word / Excel 文件，或手动逐门输入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 大多数人是「卡在没有文件」这一步：把去教务导出做成一步能点到的动作，
        // 而不是让他自己去翻「入口」页找链接。仍然只跳转，不读页面、不碰登录态。
        OutlinedButton(
            onClick = {
                val opened = openInBrowser(context, NbuUrls.PORTAL)
                jumpTip = if (opened) null else "没有找到可用浏览器，链接已复制"
                if (!opened) clipboard.setText(AnnotatedString(NbuUrls.PORTAL))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("打开信息门户（登录后进教务）")
        }
        Text(
            text = "路径：信息门户登录 → 办事大厅 / 教务系统 → 我的课表 → 导出为 Excel 或 Word，" +
                "再回到这里点「① 导入」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "校外网络打不开，可先",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    val opened = openInBrowser(context, NbuUrls.WEBVPN)
                    jumpTip = if (opened) null else "没有找到可用浏览器，链接已复制"
                    if (!opened) clipboard.setText(AnnotatedString(NbuUrls.WEBVPN))
                },
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text("连接 WebVPN", style = MaterialTheme.typography.labelSmall)
            }
        }
        jumpTip?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        OutlinedButton(
            onClick = { filePicker.launch(IMPORT_MIME_TYPES) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("① 导入 Word / Excel 文件") }

        OutlinedButton(
            onClick = onManualAdd,
            modifier = Modifier.fillMaxWidth()
        ) { Text("② 手动输入课程") }

        pickedName?.let {
            Text(
                text = "已选择：$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        readError?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        previewError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (preview != null) {
            PreviewSummary(preview)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear) { Text("清除结果") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    enabled = !importing && preview.slots.isNotEmpty()
                ) { Text(if (importing) "导入中…" else "确认导入（覆盖当前课表）") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "timetable"
            }
        }
    return uri.lastPathSegment ?: "timetable"
}

@Composable
private fun PreviewSummary(preview: ParseResult) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "识别到 ${preview.courseCount} 门课 / ${preview.slots.size} 个时段",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = "来源：${sourceLabel(preview)}　待确认 ${preview.needsReview.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (preview.needsReview.isNotEmpty()) {
                LazyColumn(modifier = Modifier.height(96.dp)) {
                    items(preview.needsReview.take(20)) { slot ->
                        Text(
                            text = "· ${slot.name}（${slot.issues.joinToString("、")}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            preview.warnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun sourceLabel(preview: ParseResult): String = when (preview.source) {
    ParseSource.HTML_TABLE -> "网页表格"
    ParseSource.TEXT_GRID -> "文本"
    ParseSource.EXCEL -> "Excel"
    ParseSource.WORD -> "Word"
    ParseSource.UNKNOWN -> "未知"
}

@Composable
fun TimetableEmptyHint(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有课表", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "可以导入教务导出的 Word/Excel，或手动添加课程",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        // 原本这里是「导入课表」与「手动添加」两个按钮，但两者的 onClick 都是 onImport，
        // 打开的是同一个弹窗（手动输入在弹窗里是第二步），视觉上给了两个不同的承诺、
        // 行为却完全一致。合并成一个入口，避免"点哪个都进同一个地方"的困惑。
        Button(
            onClick = onImport,
            modifier = Modifier.padding(top = 20.dp)
        ) { Text("导入课表") }
        // 空态是「最不知道怎么开始」的位置，直接把导出入口摆在底下
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        TextButton(
            onClick = {
                if (!openInBrowser(context, NbuUrls.PORTAL)) {
                    clipboard.setText(AnnotatedString(NbuUrls.PORTAL))
                }
            },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text("没有文件？打开信息门户导出课表", style = MaterialTheme.typography.labelMedium)
        }
    }
}
