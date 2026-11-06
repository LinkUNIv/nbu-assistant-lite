package app.nbult.lite.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nbult.lite.ui.LocalAppContainer
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.launch

/**
 * 课表编辑的触发方式。
 *
 * 做这个页面是因为 0.35.49 把「点击课程块打开编辑器」悄悄改成了长按（`onClick` 直接写成空），
 * 界面上没有任何提示，用户只能自己试出来。与其猜一个默认值，不如把选择摆出来。
 */
@Composable
fun EditGestureScreen(onBack: () -> Unit) {
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    val gesture by settings.editGesture.collectAsStateWithLifecycle(initialValue = "tap")

    fun choose(id: String, label: String) {
        if (gesture == id) return
        AppLog.log("Settings", "课表编辑方式=$label")
        scope.launch { settings.setEditGesture(id) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "课表编辑",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCard(title = "编辑方式") {
                GestureOption(
                    title = "点击",
                    subtitle = "轻点课程块打开编辑器，默认方式",
                    selected = gesture != "long_press"
                ) { choose("tap", "点击") }
                GestureOption(
                    title = "长按",
                    subtitle = "按住课程块约半秒打开，滑动浏览时不容易误触",
                    selected = gesture == "long_press"
                ) { choose("long_press", "长按") }
            }

            Text(
                text = "周视图里的课程块和「今天」卡片里的课程行用同一个设置。" +
                    "编辑面板可以随时取消，所以误触不会改动任何数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun GestureOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = null, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
