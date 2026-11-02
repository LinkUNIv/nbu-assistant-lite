package app.nbult.lite.account

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.nbult.lite.browser.AttendanceTheme
import app.nbult.lite.ui.theme.Backdrop
import app.nbult.lite.ui.theme.brushFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountActivity : ComponentActivity() {
    private fun resetSession() {
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        setContent {
            AttendanceTheme { dark, backdrop ->
                val store = remember { AccountStore(this@AccountActivity) }
                val scope = rememberCoroutineScope()
                var loading by remember { mutableStateOf(true) }
                var busy by remember { mutableStateOf(false) }
                var saved by remember { mutableStateOf<String?>(null) }
                var editing by remember { mutableStateOf(false) }
                var username by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var message by remember { mutableStateOf("") }
                var confirmClear by remember { mutableStateOf(false) }
                val view = LocalView.current
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                LaunchedEffect(Unit) {
                    val result = withContext(Dispatchers.IO) { runCatching { store.credentials()?.first } }
                    saved = result.getOrNull()
                    username = saved.orEmpty()
                    if (result.isFailure) message = "已保存的凭据无法读取，请重新保存或清除账号数据。"
                    loading = false
                }
                val colors = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize(), color = colors.background) {
                    Column(Modifier.fillMaxSize().background(Backdrop.of(backdrop).brushFor(dark, colors.background))
                        .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                            Text("账号", style = MaterialTheme.typography.headlineSmall)
                        }
                        Text("管理统一身份认证账号", style = MaterialTheme.typography.titleMedium, color = colors.primary)
                        Text("保存后，点名登录将自动填写。密码在本机加密保存，二次验证码仍需自行输入。",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (loading) CircularProgressIndicator()
                                else if (saved != null && !editing) {
                                    Icon(Icons.Outlined.Person, null, tint = colors.primary)
                                    Text(saved.orEmpty(), style = MaterialTheme.typography.titleLarge)
                                    Text("密码已保存 · 不可查看", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                    Button(onClick = { editing = true; username = saved.orEmpty(); password = ""; message = "" },
                                        enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("更改账号密码") }
                                } else {
                                    OutlinedTextField(username, { username = it }, label = { Text("学生账号") },
                                        enabled = !busy, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(password, { password = it }, label = { Text(if (editing) "输入新密码" else "密码") },
                                        enabled = !busy, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                                    Button(onClick = {
                                        if (username.isBlank() || password.isEmpty()) message = "请填写账号和密码"
                                        else {
                                            val account = username.trim(); val secret = password
                                            busy = true
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { runCatching { store.save(account, secret) } }
                                                result.onSuccess {
                                                    password = ""; saved = account; editing = false; resetSession()
                                                    message = "已保存，下次点名将尝试自动登录。"
                                                }.onFailure { message = "保存失败，请重试。" }
                                                busy = false
                                            }
                                        }
                                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                        Text(if (busy) "正在保存…" else "保存账号")
                                    }
                                    if (saved != null) TextButton(onClick = { editing = false; password = ""; message = "" }, enabled = !busy) { Text("取消更改") }
                                }
                                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        if (!loading) OutlinedButton(onClick = { confirmClear = true }, enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清除账号数据") }
                        Text("仅用于宁波大学统一身份认证。更改或清除账号会退出点名浏览器的登录状态，不影响课表。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false }, title = { Text("清除账号数据？") },
                    text = { Text("删除本机保存的账号密码，并退出点名浏览器的登录状态。不会删除课表。") },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
                    confirmButton = { TextButton(onClick = {
                        confirmClear = false; busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { store.clear() } }
                            result.onSuccess {
                                resetSession(); saved = null; username = ""; password = ""; editing = false; message = "账号数据已清除。"
                            }.onFailure { message = "清除失败，请重试。" }
                            busy = false
                        }
                    }) { Text("清除") } }
                )
            }
        }
    }
}
