package app.nbult.lite.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment


@Composable
internal fun SignCodeScreen(message: String, digits: String, submitting: Boolean, onKey: (String) -> Unit, onOriginal: () -> Unit, success: Boolean, resultVersion: Int, onScan: (String) -> Unit, onStopScan: () -> Unit) {
    var camera by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf(0) }
    AttendanceTheme { _, _ ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            val enabled = !success && !submitting && (key != "确认" || digits.length == 4)
                            Surface(
                                modifier = Modifier.weight(1f).height(58.dp)
                                    .semantics {
                                        role = Role.Button
                                        if (!enabled) disabled()
                                        onClick { if (enabled) onKey(key); enabled }
                                    }
                                    .pointerInput(key, enabled) {
                                        if (enabled) detectTapGestures(onPress = {
                                            onKey(key)
                                            tryAwaitRelease()
                                        })
                                    },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(key) }
                            }
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
