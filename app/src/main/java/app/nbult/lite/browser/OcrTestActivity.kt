package app.nbult.lite.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*

class OcrTestActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        setContent {
            var code by remember { mutableStateOf("尚未识别") }
            AttendanceTheme { _, _ ->
                SignCameraDialog(success = false, resultVersion = 0, message = code,
                    onCode = { code = it }, onDismiss = { finish() }, testMode = true)
            }
        }
    }
}
