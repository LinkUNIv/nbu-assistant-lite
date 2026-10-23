package app.nbult.lite.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.CalendarDates
import app.nbult.lite.domain.CalendarEvent
import app.nbult.lite.domain.CalendarEventType
import app.nbult.lite.domain.CalendarYear
import app.nbult.lite.domain.PeriodTable
import app.nbult.lite.domain.SchoolCalendar
import app.nbult.lite.ui.LocalAppContainer

@Composable
fun CalendarScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    var data by remember { mutableStateOf<SchoolCalendar?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var yearId by remember { mutableStateOf("") }
    var showPeriods by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { container.calendarRepository.load() }
            .onSuccess {
                data = it
                yearId = it.yearOf(AppClock.todayEpochDay())?.id ?: it.years.firstOrNull()?.id.orEmpty()
            }
            .onFailure { error = it.message ?: "校历加载失败" }
    }

    val calendar = data
    when {
        error != null -> CenterHint(error!!)
        calendar == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        else -> {
            val year = calendar.byId(yearId) ?: calendar.years.firstOrNull()
            if (year == null) {
                CenterHint("暂无校历数据")
            } else {
                CalendarContent(
                    calendar = calendar,
                    year = year,
                    onBack = onBack,
                    showPeriods = showPeriods,
                    onTogglePeriods = { showPeriods = !showPeriods },
                    onSelectYear = { yearId = it }
                )
            }
        }
    }
}

@Composable
private fun CalendarContent(
    calendar: SchoolCalendar,
    year: CalendarYear,
    onBack: () -> Unit,
    showPeriods: Boolean,
    onTogglePeriods: () -> Unit,
    onSelectYear: (String) -> Unit
) {
    val today = AppClock.todayEpochDay()
    val semester = year.semesterOf(today)
    val week = semester?.weekOf(today)
    val todayEvents = year.eventsOn(today)
    val upcoming = year.upcoming(today, 2)

    val grouped = remember(year) {
        year.events.groupBy { CalendarDates.monthKey(it.startDay) }
            .toSortedMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Text("校历", style = MaterialTheme.typography.titleMedium)
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                calendar.years.forEach { y ->
                    TextButton(onClick = { onSelectYear(y.id) }) {
                        Text(
                            text = y.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (y.id == year.id) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (y.id == year.id) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        // 官方校历原图（应用内查看 + 浏览器兜底）
        item { OfficialCalendarCard() }

        // 当前状态
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = if (semester != null && week != null) {
                            "${semester.name} · 第 $week 周"
                        } else {
                            "当前不在学期内"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "今天 ${CalendarDates.monthDay(today)} ${CalendarDates.weekday(today)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    if (todayEvents.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        todayEvents.forEach {
                            Text(
                                text = "· ${it.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else if (upcoming.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "接下来：" + upcoming.joinToString("；") {
                                "${CalendarDates.monthDay(it.startDay)} ${it.title}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        grouped.forEach { (monthKey, events) ->
            item(key = "h-$monthKey") {
                Text(
                    text = CalendarDates.monthLabel(events.first().startDay),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
                )
            }
            items(events, key = { "$monthKey-${it.startDay}-${it.title}" }) { event ->
                EventRow(event = event, isToday = event.covers(today))
            }
        }

        // 上课时间表
        item {
            TextButton(
                onClick = onTogglePeriods,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp)
            ) {
                Text(if (showPeriods) "收起上课时间表" else "查看上课时间表")
            }
        }
        if (showPeriods) {
            items(calendar.periods, key = { "p-${it.id}" }) { table ->
                PeriodTableCard(table)
            }
        }

        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "数据来源：${year.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!year.verified) {
                    Text(
                        text = "该学年数据尚待与教务处原文逐条核对，请以官方发布为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = "${calendar.notice}（整理于 ${calendar.updatedAt}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, isToday: Boolean) {
    val color = colorOf(event.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .background(
                if (isToday) color.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(28.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = CalendarDates.rangeText(event.startDay, event.endDay),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isToday) {
            Text(
                text = "今天",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
private fun PeriodTableCard(table: PeriodTable) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = table.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "上班时间：${table.office}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val rows = (table.items.size + 1) / 2
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0..1) {
                        val index = r + c * rows
                        val item = table.items.getOrNull(index)
                        Text(
                            text = item?.let {
                                "第${it.idx}节  ${it.start}-${it.end}"
                            } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (table.note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = table.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun colorOf(type: CalendarEventType): Color = when (type) {
    CalendarEventType.HOLIDAY -> Color(0xFF0F766E)
    CalendarEventType.REGISTER -> Color(0xFF2F6FED)
    CalendarEventType.CLASS -> Color(0xFF1D4ED8)
    CalendarEventType.SPORT -> Color(0xFF7C3AED)
    CalendarEventType.MAKEUP -> Color(0xFFB4530A)
    CalendarEventType.EXAM -> Color(0xFFB91C1C)
    CalendarEventType.OTHER -> Color(0xFF6B7280)
}
