package app.nbult.lite.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.NbuLiteApp
import app.nbult.lite.account.AccountStore
import app.nbult.lite.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Input is deliberately held only in composition memory, never rememberSaveable. */
@Composable
internal fun AuthenticationScreen(
    mode: String,
    autoLogin: Boolean,
    onSubmit: (String, String) -> Unit,
    onRequestCode: () -> Unit,
    onTrust: (Boolean) -> Unit,
    onOriginal: () -> Unit
) {
    val context = LocalContext.current
    AttendanceTheme { dark, backdropId ->
        if (mode == "loading" || mode == "unsupported") {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mode == "loading") CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                    Text(if (mode == "loading") "正在加载点名页面…" else "需要在学校页面继续验证",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onOriginal) { Text("打开学校原页面") }
                }
            }
            return@AttendanceTheme
        }
        var account by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        var trust by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        val focus = LocalFocusManager.current
        val login = mode == "login"
        val device = mode == "trust"
        fun submit() {
            if (secret.isBlank() || (login && account.isBlank())) {
                invalid = true
                message = if (login) "请填写账号和密码" else "请输入验证码"
            } else {
                invalid = false
                focus.clearFocus()
                onSubmit(account, secret)
                secret = ""
                message = "已提交学校验证。若未跳转，可查看原网页提示。"
            }
        }
        LaunchedEffect(autoLogin) {
            if (autoLogin) {
                val saved = withContext(Dispatchers.IO) { runCatching { AccountStore(context).credentials() } }
                saved.onSuccess { credentials ->
                    if (credentials != null) {
                        account = credentials.first
                        secret = credentials.second
                        submit()
                    }
                }.onFailure { message = "保存的账号无法读取，请到“我的 → 账号”重新保存。" }
            }
        }
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier.fillMaxSize()
                .background(Backdrop.of(backdropId).brushFor(dark, colors.background))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
                    Icon(
                        if (device) Icons.Outlined.Shield else Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp).size(26.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("点名", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Text(
                        when (mode) { "login" -> "统一身份认证"; "code" -> "二次验证"; else -> "设备信任" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                when (mode) {
                    "login" -> "使用宁波大学账号登录，继续查看点名课表。"
                    "code" -> "输入学校发送的验证码，完成身份确认。"
                    else -> "选择此设备的登录方式，确认后继续。"
                },
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
            )
            Card(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (device) {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrustChoice("信任此设备", "在这台个人设备上继续使用", trust) { trust = true }
                            TrustChoice("仅本次登录", "适合临时或共用设备", !trust) { trust = false }
                        }
                        Text("信任有效期及后续验证要求由学校决定。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                        if (login) OutlinedTextField(
                            value = account, onValueChange = { account = it; invalid = false },
                            label = { Text("学生账号") },
                            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                            isError = invalid && account.isBlank(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
                        )
                        OutlinedTextField(
                            value = secret, onValueChange = { secret = it; invalid = false },
                            label = { Text(if (login) "密码" else "验证码") },
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                            isError = invalid && secret.isBlank(),
                            visualTransformation = if (login) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (login) KeyboardType.Password else KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() })
                        )
                        if (!login) OutlinedButton(
                            onClick = { onRequestCode(); message = "已请求验证码，请留意学校通知；重发间隔由学校控制。" },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("获取验证码") }
                        if (login) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = true, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text("7 日内免登录", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (message.isNotBlank()) Text(
                        message, style = MaterialTheme.typography.bodySmall,
                        color = if (invalid) colors.error else colors.onSurfaceVariant
                    )
                    Button(
                        onClick = { if (device) { focus.clearFocus(); onTrust(trust) } else submit() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text(if (device) "确认并继续" else if (login) "登录" else "验证并继续") }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("宁波大学 · uis.nbu.edu.cn", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                TextButton(onClick = onOriginal) { Text("使用学校原网页 / 查看提示") }
            }
        }
    }
}

@Composable
private fun TrustChoice(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** Share the main app's live appearance settings with the browser chrome and forms. */
@Composable
internal fun AttendanceTheme(content: @Composable (Boolean, String) -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as NbuLiteApp).container.settings
    val darkId by settings.darkMode.collectAsStateWithLifecycle(initialValue = if (attendanceDark(context)) "dark" else "light")
    val colorId by settings.themeColor.collectAsStateWithLifecycle(initialValue = "blue")
    val backdropId by settings.backdrop.collectAsStateWithLifecycle(initialValue = "plain")
    val dark = when (DarkMode.of(darkId)) {
        DarkMode.Dark -> true
        DarkMode.Light -> false
        DarkMode.System -> isSystemInDarkTheme()
    }
    NbuLiteTheme(darkTheme = dark, seed = SeedColor.of(colorId)) { content(dark, backdropId) }
}
