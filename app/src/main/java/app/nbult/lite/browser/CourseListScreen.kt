package app.nbult.lite.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nbult.lite.ui.theme.Backdrop
import app.nbult.lite.ui.theme.brushFor

internal data class CourseRow(val index: Int, val title: String, val details: String, val status: String, val open: Boolean = false, val signed: Boolean = false)

@Composable
internal fun CourseListScreen(rows: List<CourseRow>, onRefresh: () -> Unit, onOriginal: () -> Unit, onCourse: (CourseRow) -> Unit) {
    AttendanceTheme { dark, backdrop ->
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            LazyColumn(Modifier.fillMaxSize().background(Backdrop.of(backdrop).brushFor(dark, colors.background)),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("点名课程", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("选择课程，进入学校签到页面", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (rows.isEmpty()) item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                        Text("暂无课程，或学校数据仍在加载。可稍候、刷新或查看原页面。", Modifier.padding(20.dp))
                    }
                }
                items(rows, key = { it.index }) { row ->
                    val pulse = if (row.open) {
                        val transition = rememberInfiniteTransition(label = "open course")
                        val value by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "border")
                        value
                    } else 0f
                    Card(onClick = { onCourse(row) }, modifier = Modifier.fillMaxWidth().alpha(if (row.signed || row.status.contains("已签到")) 0.55f else 1f), shape = RoundedCornerShape(16.dp),
                        border = if (row.open) BorderStroke(2.dp, colors.primary.copy(alpha = pulse)) else null,
                        colors = CardDefaults.cardColors(containerColor = if (row.open) colors.primaryContainer else colors.surface)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Text(row.details, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Text(if (row.open) "开放签到" else row.status.ifBlank { "查看签到" }, style = MaterialTheme.typography.labelMedium,
                                color = if (row.status.contains("未发起")) colors.onSurfaceVariant else colors.primary)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh) { Text("刷新课程") }
                        TextButton(onClick = onOriginal) { Text("查看学校原页面") }
                    }
                    Text("能否签到及考勤状态由学校决定。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
