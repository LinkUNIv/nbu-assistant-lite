package app.nbult.lite.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.nbult.lite.MainActivity
import app.nbult.lite.NbuLiteApp
import app.nbult.lite.domain.AppClock
import app.nbult.lite.domain.ScheduleService
import app.nbult.lite.ui.theme.SeedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 课表小组件，三种形态共用同一个 provider。
 *
 * - 2×2：只显示下一节课（倒计时 + 课程）
 * - 4×2：今日剩余课程列表，宽行，右侧带节次
 * - 2×4：同上但窄列，教室放在课名下
 *
 * 形态由 `LocalSize` 判断，不为每种尺寸单独注册 receiver ——
 * 那样面板里会出现三个名字相近的条目，用户反而不知道该加哪个。
 */
class NextClassWidget : GlanceAppWidget() {

    /**
     * 只声明一档 2×2。
     *
     * 曾经试过「四档 + LocalSize 分支」在同一个 provider 里区分卡片与列表，
     * 但 MIUI 只按自己选中的档位请求内容，实际拉伸后的尺寸与档位不一致，
     * `LocalSize` 拿到的仍是档位值，形态判断会失效。列表因此独立成
     * [TodayListWidget] 这个 provider。
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState(context)
        provideContent { NextClassBody(state) }
    }
}

class NextClassReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()
}

/** 列表里的一行。时间已转成 `HH:mm`，节次已转成 `3-4` 形式。 */
internal data class TodayRow(
    val startTime: String,
    val endTime: String,
    val courseName: String,
    val room: String,
    val sections: String
)

internal data class WidgetState(
    val colors: WidgetColors,
    val label: String,
    val courseName: String,
    val timeRange: String,
    val room: String,
    /** 倒计时的数值部分（`238`）。与单位分开，是为了单独放大。 */
    val countValue: String,
    /** 倒计时的单位部分（`分钟后` / `上课`）。 */
    val countUnit: String,
    val today: List<TodayRow>,
    val isTomorrow: Boolean
)

/**
 * 从磁盘读一次数据，同时算出「下一节」与「今日剩余列表」。
 *
 * 不能依赖任何内存缓存：进程被 launcher 杀掉之后，widget 仍然要能画出来。
 */
internal suspend fun loadState(context: Context): WidgetState {
    val fallback = WidgetState(
        colors = widgetColors(SeedColor.Blue.id, isDarkNow(context, "system")),
        label = "下一节",
        courseName = "暂无课表",
        timeRange = "",
        room = "",
        countValue = "",
        countUnit = "",
        today = emptyList(),
        isTomorrow = false
    )

    val container = (context.applicationContext as? NbuLiteApp)?.container ?: return fallback

    return runCatching {
        val seedId = container.settings.themeColor.first()
        val modeId = container.settings.darkMode.first()
        val colors = widgetColors(seedId, isDarkNow(context, modeId))

        val snapshot = withContext(Dispatchers.IO) {
            container.timetableRepository.observeSnapshot().first()
        }
        if (snapshot.term == null) {
            return@runCatching fallback.copy(colors = colors, courseName = "尚未导入课表", label = "")
        }

        val now = AppClock.now()
        // planForToday 已经剔除已结束的课，并且当天没课时会顺延到次日
        val plan = ScheduleService.planForToday(
            entries = snapshot.entries,
            term = snapshot.term,
            periods = snapshot.periods,
            overrides = snapshot.overrides,
            nowMillis = now
        )
        val next = plan.entries.firstOrNull()
            ?: return@runCatching fallback.copy(colors = colors, courseName = "没有课了", label = "")

        fun timeOf(section: Int) =
            snapshot.periods.firstOrNull { it.idx == section }?.let { it.startTime to it.endTime }

        // 列表最多取 8 条：再多也滚不完，只会让每次更新的 RemoteViews 更大
        val today = plan.entries.take(8).map { entry ->
            val (start, end) = timeOf(entry.slot.startSection) ?: ("" to "")
            TodayRow(
                startTime = start,
                endTime = end,
                courseName = stripCourseCode(entry.course.name),
                room = entry.slot.room,
                sections = if (entry.slot.startSection == entry.slot.endSection) {
                    "${entry.slot.startSection}"
                } else {
                    "${entry.slot.startSection}-${entry.slot.endSection}"
                }
            )
        }

        val startTime = timeOf(next.slot.startSection)?.first.orEmpty()
        val endTime = timeOf(next.slot.endSection)?.second.orEmpty()
        val count = countdown(startTime, now)

        WidgetState(
            colors = colors,
            label = if (plan.isTomorrow) "明天" else "下一节",
            courseName = stripCourseCode(next.course.name),
            timeRange = if (startTime.isNotEmpty() && endTime.isNotEmpty()) "$startTime – $endTime" else "",
            room = next.slot.room,
            countValue = count.first,
            countUnit = count.second,
            today = today,
            isTomorrow = plan.isTomorrow
        )
    }.getOrElse { fallback }
}

/** `15:30` → `930`（当天第几分钟）。解析失败返回 null。 */
internal fun minutesOf(time: String): Int? {
    val parts = time.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    return hour * 60 + minute
}

/**
 * 距离上课还有多久，拆成数值与单位两部分返回。
 *
 * 只给到分钟：widget 最快也要 30 分钟才刷新一次，精确到秒既刷不出来，
 * 也会让人以为它在实时走。
 */
internal fun countdown(startTime: String, nowMillis: Long): Pair<String, String> {
    val start = minutesOf(startTime) ?: return "" to ""
    val current = ScheduleService.minutesOfDay(nowMillis)
    val diff = start - current
    return when {
        diff in -120..0 -> "正在" to "上课"
        diff in 1..600 -> "$diff" to "分钟后"
        else -> "" to ""
    }
}

/**
 * 去掉课名前面的课程代码。
 *
 * 教务导出的课名常写成 `101G02A-模拟电子技术`，代码对使用者没有意义。
 * 前缀必须是「至少三位、且含数字」才剥，避免把「高等数学-上」这类正常连写也切掉。
 */
internal fun stripCourseCode(name: String): String {
    val match = Regex("""^([0-9A-Za-z]{3,})[-—－](\S.*)$""").find(name.trim()) ?: return name
    if (match.groupValues[1].none { it.isDigit() }) return name
    return match.groupValues[2].trim().ifBlank { name }
}

/**
 * 2×2 形态：只显示下一节课。
 *
 * 倒计时贴顶、课程信息贴底，中间用权重间隔推开 —— 两块各占一头，
 * 任何高度下都不会挤成一段。
 */
@Composable
private fun NextClassBody(state: WidgetState) {
    val colors = state.colors
    val detail = listOf(state.timeRange, state.room)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .cornerRadius(20.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(GlanceModifier.fillMaxSize()) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                if (state.label.isNotBlank()) {
                    Text(
                        text = state.label,
                        style = TextStyle(
                            color = ColorProvider(colors.title),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }
                if (state.countValue.isNotBlank()) {
                    // 48sp 是 94dp 内容宽下三位数的安全上限；单位另起一行才放得下
                    Text(
                        text = state.countValue,
                        style = TextStyle(
                            color = ColorProvider(colors.alert),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = state.countUnit,
                        style = TextStyle(
                            color = ColorProvider(colors.body),
                            fontSize = 10.sp
                        ),
                        maxLines = 1
                    )
                }
            }

            Spacer(GlanceModifier.defaultWeight())

            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = state.courseName,
                    style = TextStyle(
                        color = ColorProvider(colors.title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 2
                )
                if (detail.isNotBlank()) {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = detail,
                        style = TextStyle(
                            color = ColorProvider(colors.body),
                            fontSize = 9.sp
                        ),
                        maxLines = 2
                    )
                }
                // 课程块整体上移 4dp，不贴着卡片下沿
                Spacer(GlanceModifier.height(4.dp))
            }
        }
    }
}

/**
 * 列表形态：今日剩余课程。
 *
 * `wide` 为真时是 4×2（宽行，右侧带教室与节次）；为假时是 2×4（窄列，教室放在课名下）。
 * 两种尺寸共用同一套行结构，只有列宽、行高与右侧列不同。
 */
@Composable
internal fun TodayListBody(state: WidgetState, wide: Boolean) {
    val colors = state.colors
    val rowHeight = if (wide) 45.dp else 58.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .cornerRadius(20.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = if (wide) 12.dp else 8.dp, vertical = 8.dp)
    ) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = if (state.isTomorrow) "明天" else "今天",
                    style = TextStyle(
                        color = ColorProvider(colors.title),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "还剩 ${state.today.size} 节",
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = 10.sp
                    ),
                    maxLines = 1
                )
            }

            if (state.today.isEmpty()) {
                Text(
                    text = "今天没有课了",
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = 11.sp
                    ),
                    maxLines = 1
                )
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(state.today) { row ->
                        CourseRow(row = row, colors = colors, wide = wide, rowHeight = rowHeight)
                    }
                }
            }
        }
    }
}

/**
 * 一行课程。
 *
 * 列宽全部写死，不用自适应：滚动列表在 RemoteViews 里每行独立渲染，
 * 只要有一列随内容伸缩，行与行之间就会错开。
 */
@Composable
internal fun CourseRow(
    row: TodayRow,
    colors: WidgetColors,
    wide: Boolean,
    rowHeight: Dp
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        if (wide) {
            Column(modifier = GlanceModifier.width(46.dp)) {
                Text(
                    text = row.startTime,
                    style = TextStyle(
                        color = ColorProvider(colors.title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                Text(
                    text = row.endTime,
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = 9.sp
                    ),
                    maxLines = 1
                )
            }
        } else {
            Column(modifier = GlanceModifier.width(40.dp)) {
                Text(
                    text = row.startTime,
                    style = TextStyle(
                        color = ColorProvider(colors.title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }

        // 竖色条：左侧一条主题色，用来区分相邻两门课。
        // 用 Spacer 而不是 Box —— Glance 的 Box 必须带 content，
        // 纯装饰的空盒子写不出来
        Spacer(
            modifier = GlanceModifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .background(ColorProvider(colors.accent))
                .cornerRadius(2.dp)
        )
        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.courseName,
                style = TextStyle(
                    color = ColorProvider(colors.title),
                    fontSize = if (wide) 13.sp else 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            if (!wide && row.room.isNotBlank()) {
                Text(
                    text = row.room,
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = 9.sp
                    ),
                    maxLines = 1
                )
            }
        }

        if (wide) {
            if (row.room.isNotBlank()) {
                Text(
                    text = row.room,
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = 9.sp
                    ),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(8.dp))
            }
            Text(
                text = row.sections,
                style = TextStyle(
                    color = ColorProvider(colors.body),
                    fontSize = 9.sp
                ),
                maxLines = 1
            )
        }
    }
}
