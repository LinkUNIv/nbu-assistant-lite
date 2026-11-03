package app.nbult.lite.browser

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

internal object AttendanceOptions {
    val preload = MutableStateFlow(true)
    private fun prefs(c: Context) = c.getSharedPreferences("attendance_options", Context.MODE_PRIVATE)
    fun load(c: Context) { preload.value = prefs(c).getBoolean("preload", true) }
    fun ui(c: Context) = prefs(c).getBoolean("ui", true)
    fun auto(c: Context) = prefs(c).getBoolean("preload", true) || prefs(c).getBoolean("auto", true)
    fun set(c: Context, key: String, value: Boolean) {
        prefs(c).edit().apply {
            putBoolean(key, value)
            if (key == "preload" && value) putBoolean("auto", true)
            if (key == "auto" && !value) putBoolean("preload", false)
        }.apply()
        load(c)
    }
}

class AttendanceSettingsActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            var revision by remember { mutableStateOf(0) }
            AttendanceTheme { _, _ ->
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { finish() }) { Text("返回") }
                        Text("点名设置", style = MaterialTheme.typography.headlineSmall)
                        val options = listOf(
                            Triple("ui", "更改课程列表 UI", AttendanceOptions.ui(this@AttendanceSettingsActivity)),
                            Triple("auto", "启用自动跳转", AttendanceOptions.auto(this@AttendanceSettingsActivity)),
                            Triple("preload", "后台预加载", getSharedPreferences("attendance_options", MODE_PRIVATE).getBoolean("preload", true))
                        )
                        key(revision) { options.forEach { (id, title, enabled) ->
                            Card {
                                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(title, modifier = Modifier.weight(1f))
                                    Switch(enabled, { AttendanceOptions.set(this@AttendanceSettingsActivity, id, it); revision++ })
                                }
                            }
                        } }
                        OutlinedButton(onClick = {
                            startActivity(android.content.Intent(this@AttendanceSettingsActivity, OcrTestActivity::class.java))
                        }, modifier = Modifier.fillMaxWidth()) { Text("相机 OCR 测试") }
                        Text("开启预加载会同时开启自动跳转；关闭自动跳转会同时关闭预加载。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
