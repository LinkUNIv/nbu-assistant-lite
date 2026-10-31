package app.nbult.lite.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
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
import java.util.Calendar

/**
 * 课表小组件的三种状态。
 *
 * 从 `planForToday` 的结果里拆出来，而不是直接看 `isTomorrow`：
 * 那个函数把「今天还有课正在上」与「今天已结束、顺延到明天」放进同一个列表，
 * 只看它无法区分「正在上课」与「下一节还没开始」。
 */
internal enum class WidgetMode {
    /** 今天还有课没开始 */
    NEXT,

    /** 此刻正在某节课的时间区间内 */
    ONGOING,

    /** 今天的课都结束了，列表里其实是明天的课 */
    FINISHED
}

/**
 * 课表小组件，2×2。
 *
 * 视觉参数以对话里的 SVG 草图为准：无描边、圆角 22dp、内边距 14dp、
 * 右上角时间胶囊、红色分钟数占卡片约四成高度。
 */
class NextClassWidget : GlanceAppWidget() {

    /**
     * 只声明一档 2×2。
     *
     * 曾试过「四档 + LocalSize 分支」在同一个 provider 里区分卡片与列表，
     * 但 MIUI 只按自己选中的档位请求内容，拉伸后的尺寸与档位不一致，
     * `LocalSize` 拿到的仍是档位值，形态判断会失效。列表因此独立成
     * [TodayListWidget]。
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
    val mode: WidgetMode,
    /** 右上角胶囊：NEXT 显示下一节开始时间，ONGOING 显示当前时间，FINISHED 不显示 */
    val clock: String,
    val countValue: String,
    val countUnit: String,
    val courseName: String,
    val timeRange: String,
    val room: String,
    /** FINISHED 时下方的一句提示，避免卡片只剩四个字 */
    val hint: String,
    val today: List<TodayRow>,
    val isTomorrow: Boolean
)

/**
 * 从磁盘读一次数据，同时算出三种状态与今日列表。
 *
 * 不能依赖任何内存缓存：进程被 launcher 杀掉之后，widget 仍然要能画出来。
 */
internal suspend fun loadState(context: Context): WidgetState {
    val fallback = WidgetState(
        colors = widgetColors(SeedColor.Blue.id, isDarkNow(context, "system")),
        mode = WidgetMode.NEXT,
        clock = "",
        countValue = "",
        countUnit = "",
        courseName = "暂无课表",
        timeRange = "",
        room = "",
        hint = "",
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
            return@runCatching fallback.copy(colors = colors, courseName = "尚未导入课表")
        }

        val now = AppClock.now()
        // planForToday 已经剔除已结束的课；当天没课时会顺延到次日
        val plan = ScheduleService.planForToday(
            entries = snapshot.entries,
            term = snapshot.term,
            periods = snapshot.periods,
            overrides = snapshot.overrides,
            nowMillis = now
        )
        val first = plan.entries.firstOrNull()
            ?: return@runCatching fallback.copy(
                colors = colors,
                mode = WidgetMode.FINISHED,
                courseName = "",
                hint = "明天没有课"
            )

        fun timeOf(section: Int) =
            snapshot.periods.firstOrNull { it.idx == section }?.let { it.startTime to it.endTime }

        val nowMin = ScheduleService.minutesOfDay(now)

        // 正在上课：今天某节课的区间包含此刻。
        // plan 已剔除已结束的课，正在上的那节一定还在列表里
        val ongoing = if (plan.isTomorrow) null else plan.entries.firstOrNull { entry ->
            val start = timeOf(entry.slot.startSection)?.first?.let { minutesOf(it) }
            val end = timeOf(entry.slot.endSection)?.second?.let { minutesOf(it) }
            start != null && end != null && nowMin in start until end
        }

        val mode = when {
            plan.isTomorrow -> WidgetMode.FINISHED
            ongoing != null -> WidgetMode.ONGOING
            else -> WidgetMode.NEXT
        }

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

        // 结束态的提示：下一节其实是明天的第一节，写清楚是明天几点
        val tomorrowStart = if (plan.isTomorrow) timeOf(first.slot.startSection)?.first else null
        val hint = if (plan.isTomorrow) {
            if (tomorrowStart.isNullOrBlank()) "明天没有课"
            else "明天 $tomorrowStart ${stripCourseCode(first.course.name)}"
        } else {
            ""
        }

        val display = ongoing ?: first
        val startTime = timeOf(display.slot.startSection)?.first.orEmpty()
        val endTime = timeOf(display.slot.endSection)?.second.orEmpty()
        val count = if (mode == WidgetMode.NEXT) countdown(startTime, now) else ("" to "")

        WidgetState(
            colors = colors,
            mode = mode,
            clock = when (mode) {
                WidgetMode.NEXT -> startTime
                WidgetMode.ONGOING -> clockOf(now)
                WidgetMode.FINISHED -> ""
            },
            countValue = count.first,
            countUnit = count.second,
            courseName = stripCourseCode(display.course.name),
            timeRange = if (startTime.isNotEmpty() && endTime.isNotEmpty()) "$startTime – $endTime" else "",
            room = display.slot.room,
            hint = hint,
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

/** 毫秒时间戳 → `HH:mm`（东八区，与应用内一致）。 */
private fun clockOf(millis: Long): String {
    val calendar = Calendar.getInstance(AppClock.ZONE).apply { timeInMillis = millis }
    return "%02d:%02d".format(
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE)
    )
}

/**
 * 距离上课还有多久，拆成数值与单位两部分返回。
 *
 * 只算分钟数，不再判断「正在上课」—— 那是 [WidgetMode] 的职责。
 */
internal fun countdown(startTime: String, nowMillis: Long): Pair<String, String> {
    val start = minutesOf(startTime) ?: return "" to ""
    val current = ScheduleService.minutesOfDay(nowMillis)
    val diff = start - current
    return if (diff in 1..600) "$diff" to "分钟后" else "" to ""
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
 * 固定像素字号。
 *
 * `sp` 会跟随系统字号设置放大，而小组件的尺寸由 launcher 决定，并不跟着变。
 * 系统把字体调到 1.3 倍时，46sp 就变成实打实的 60sp，卡片装不下。
 * 除以 fontScale 后实际像素恒定，布局才可控。
 *
 * 代价是不再响应系统的无障碍字号设置 —— 在这个只有一张卡片、高度固定的场景里，
 * 排布稳定比跟随放大更重要。
 */
@Composable
internal fun fixedSp(value: Float): TextUnit {
    val scale = LocalContext.current.resources.configuration.fontScale.coerceAtLeast(0.5f)
    return (value / scale).sp
}

/**
 * 2×2 形态。
 *
 * 三种状态共用一套骨架：顶部标签与时间胶囊、中部主信息、底部课程。
 * 状态只改变中部的内容与颜色，卡片结构不变 —— 跨过上课时刻时不会整体重排。
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
            .cornerRadius(22.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                if (state.mode == WidgetMode.NEXT) {
                    Text(
                        text = "下一节",
                        style = TextStyle(
                            color = ColorProvider(colors.body),
                            fontSize = fixedSp(11f),
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }
                Spacer(GlanceModifier.defaultWeight())
                if (state.clock.isNotBlank()) {
                    Box(
                        modifier = GlanceModifier
                            .cornerRadius(12.dp)
                            .background(ColorProvider(colors.title.copy(alpha = 0.07f)))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = state.clock,
                            style = TextStyle(
                                color = ColorProvider(colors.body),
                                fontSize = fixedSp(11f)
                            ),
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(GlanceModifier.height(6.dp))

            when (state.mode) {
                WidgetMode.NEXT -> {
                    Text(
                        text = state.countValue,
                        style = TextStyle(
                            color = ColorProvider(colors.alert),
                            fontSize = fixedSp(46f),
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = state.countUnit,
                        style = TextStyle(
                            color = ColorProvider(colors.body),
                            fontSize = fixedSp(14f)
                        ),
                        maxLines = 1
                    )
                }

                WidgetMode.ONGOING -> Text(
                    text = "正在上课",
                    style = TextStyle(
                        color = ColorProvider(colors.alert),
                        fontSize = fixedSp(20f),
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )

                WidgetMode.FINISHED -> Text(
                    text = "课程结束",
                    style = TextStyle(
                        color = ColorProvider(colors.info),
                        fontSize = fixedSp(20f),
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }

            Spacer(GlanceModifier.defaultWeight())

            Column(modifier = GlanceModifier.fillMaxWidth()) {
                if (state.courseName.isNotBlank()) {
                    Text(
                        text = state.courseName,
                        style = TextStyle(
                            color = ColorProvider(colors.title),
                            fontSize = fixedSp(16f),
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2
                    )
                }
                if (detail.isNotBlank()) {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = detail,
                        style = TextStyle(
                            color = ColorProvider(colors.body),
                            fontSize = fixedSp(12f)
                        ),
                        maxLines = 2
                    )
                }
                // 结束态没有课程可显示，用一句提示占住底部，避免卡片下半截全空
                if (state.mode == WidgetMode.FINISHED && state.hint.isNotBlank()) {
                    Text(
                        text = state.hint,
                        style = TextStyle(
                            color = ColorProvider(colors.body),
                            fontSize = fixedSp(12f)
                        ),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/**
 * 列表形态：今日剩余课程。
 *
 * `wide` 为真时是 4×2（宽行，一屏 2 行）；为假时是 2×4（窄列，一屏 3 行）。
 * 行高按可用高度反推：4×2 减去内边距与标题后只剩 66dp，45dp 的行高放 1.4 行，
 * 所以压到 38dp。
 */
@Composable
internal fun TodayListBody(state: WidgetState, wide: Boolean) {
    val colors = state.colors
    val rowHeight = if (wide) 38.dp else 58.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .cornerRadius(22.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = if (state.isTomorrow) "明天" else "今天",
                    style = TextStyle(
                        color = ColorProvider(colors.title),
                        fontSize = fixedSp(11f),
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "还剩 ${state.today.size} 节",
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = fixedSp(10f)
                    ),
                    maxLines = 1
                )
            }

            if (state.today.isEmpty()) {
                Text(
                    text = if (state.isTomorrow) "明天没有课" else "今天没有课了",
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = fixedSp(11f)
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
                        fontSize = fixedSp(12f),
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                Text(
                    text = row.endTime,
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = fixedSp(9f)
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
                        fontSize = fixedSp(11f),
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }

        // 竖色条跟随主题色。用 Spacer 而不是 Box —— Glance 的 Box 必须带 content
        Spacer(
            modifier = GlanceModifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = if (wide) 5.dp else 8.dp)
                .background(ColorProvider(colors.accent))
                .cornerRadius(2.dp)
        )
        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.courseName,
                style = TextStyle(
                    color = ColorProvider(colors.title),
                    fontSize = fixedSp(if (wide) 13f else 12f),
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            if (!wide && row.room.isNotBlank()) {
                Text(
                    text = row.room,
                    style = TextStyle(
                        color = ColorProvider(colors.body),
                        fontSize = fixedSp(9f)
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
                        fontSize = fixedSp(9f)
                    ),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(8.dp))
            }
            Text(
                text = row.sections,
                style = TextStyle(
                    color = ColorProvider(colors.body),
                    fontSize = fixedSp(9f)
                ),
                maxLines = 1
            )
        }
    }
}
