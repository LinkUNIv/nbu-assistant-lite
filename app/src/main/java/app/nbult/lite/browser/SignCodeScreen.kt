package app.nbult.lite.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SignCodeScreen(message: String, digits: String, submitting: Boolean, onKey: (String) -> Unit, onOriginal: () -> Unit, success: Boolean, resultVersion: Int, onScan: (String) -> Unit, onStopScan: () -> Unit) {
    var camera by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf(0) }
    AttendanceTheme { _, _ ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("课程签到", style = MaterialTheme.typography.headlineSmall)
                Text("输入老师提供的四位签到码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { index ->
                        Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                            Text(digits.getOrNull(index)?.toString() ?: "—", Modifier.padding(18.dp), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("重置","0","确认")).forEach { keys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        keys.forEach { key ->
                            FilledTonalButton(onClick = {
                                onKey(key)
                            }, enabled = !success && !submitting && (key != "确认" || digits.length == 4), modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(18.dp)) { Text(key) }
                        }
                    }
                }
                OutlinedButton(onClick = { baseline = resultVersion; camera = true }, enabled = !success && !submitting, modifier = Modifier.fillMaxWidth()) { Text("相机识别签到码") }
                if (camera) SignCameraDialog(success, resultVersion - baseline, message, onCode = onScan, onDismiss = { onStopScan(); camera = false })
                Text(message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOriginal) { Text("查看学校原页面") }
            }
        }
    }
}
