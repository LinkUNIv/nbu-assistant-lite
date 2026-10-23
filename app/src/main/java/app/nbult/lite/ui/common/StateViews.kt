package app.nbult.lite.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun StatePanel(
    icon: ImageVector,
    title: String,
    description: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (actionText != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) {
                Text(actionText)
            }
        }
    }
}

@Composable
fun OfflinePanel(onRetry: () -> Unit) {
    StatePanel(
        icon = Icons.Outlined.CloudOff,
        title = "无网络连接",
        description = "资讯需要联网获取，课表可继续离线使用",
        actionText = "重试",
        onAction = onRetry
    )
}

@Composable
fun SourceErrorPanel(onRetry: () -> Unit) {
    StatePanel(
        icon = Icons.Outlined.ErrorOutline,
        title = "内容暂时无法获取",
        description = "请稍后再试",
        actionText = "重试",
        onAction = onRetry
    )
}

@Composable
fun EmptyTimetablePanel(onImport: () -> Unit) {
    StatePanel(
        icon = Icons.Outlined.EventBusy,
        title = "还没有课表",
        description = "从网上办事大厅「我的课表」复制整张表格后粘贴导入",
        actionText = "导入课表",
        onAction = onImport
    )
}
