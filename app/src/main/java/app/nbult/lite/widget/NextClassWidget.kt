package app.nbult.lite.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.layout.padding
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
 * 2×2 小组件：只显示下一节课。
 *
 * 这是三个尺寸里最小的一个，也是「Glance 到底能不能在这台机器上跑」的验证切片 ——
 * 不带列表、不做滚动、不依赖 collection RemoteViews，出错范围最小。
 */
class NextClassWidget : GlanceAppWidget() {

    /**
     * 两档预生成尺寸，高度都是 110dp（2 格）。
     *
     * 一开始给了四档（含 220dp 高），MIUI 从中挑了 220dp 那档，落到桌面是 2×4，
     * 内容是四行文字，下方空出六成。收敛成「高度只有一档」之后，
     * 竖长形状没有可选档位，launcher 只能按 2 格高放置。
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp),
            DpSize(250.dp, 110.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadState(context)
        provideContent { WidgetBody(state) }
    }
}

class NextClassReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()
}

/** 一次刷新要显示的全部内容。widget 没有状态，每次更新都整体重算。 */
private data class WidgetState(
    val colors: WidgetColors,
    val label: String,
    val courseName: String,
    val timeRange: String,
    val room: String,
    /** 倒计时的数值部分（`238`）。与单位分开，是为了单独放大。 */
    val countValue: String,
    /** 倒计时的单位部分（`分钟后` / `进行中`）。 */
    val countUnit: String
)

/**
 * 从磁盘读一次数据并算出「下一节」。
 *
 * 不能依赖任何内存缓存：进程被 launcher 杀掉之后，widget 仍然要能画出来。
 * 因此每次都读 DataStore 与 Room，代价是一次查询，换来的是冷启动也能出图。
 */
private suspend fun loadState(context: Context): WidgetState {
    val fallback = WidgetState(
        colors = widgetColors(SeedColor.Blue.id, isDarkNow(context, "system")),
        label = "下一节",
        courseName = "暂无课表",
        timeRange = "",
        room = "",
        countValue = "",
        countUnit = ""
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
        // planForToday 已经剔除已结束的课，并且当天没课时会顺延到次日，
        // 这里不需要再判一次「今天还有没有课」
        val plan = ScheduleService.planForToday(
            entries = snapshot.entries,
            term = snapshot.term,
            periods = snapshot.periods,
            overrides = snapshot.overrides,
            nowMillis = now
        )
        val next = plan.entries.firstOrNull()
            ?: return@runCatching fallback.copy(colors = colors, courseName = "没有课了", label = "")

        val startTime = snapshot.periods.firstOrNull { it.idx == next.slot.startSection }?.startTime.orEmpty()
        val endTime = snapshot.periods.firstOrNull { it.idx == next.slot.endSection }?.endTime.orEmpty()

        val count = countdown(startTime, now)
        WidgetState(
            colors = colors,
            label = if (plan.isTomorrow) "明天" else "下一节",
            courseName = stripCourseCode(next.course.name),
            timeRange = if (startTime.isNotEmpty() && endTime.isNotEmpty()) "$startTime – $endTime" else "",
            room = next.slot.room,
            countValue = count.first,
            countUnit = count.second
        )
    }.getOrElse { fallback }
}

/** `15:30` → `930`（当天第几分钟）。解析失败返回 null。 */
private fun minutesOf(time: String): Int? {
    val parts = time.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    return hour * 60 + minute
}

/**
 * 距离上课还有多久，拆成数值与单位两部分返回。
 *
 * 只给到分钟：widget 每小时才刷新一次（系统下限 30 分钟），
 * 精确到秒既刷不出来，也会让人以为它在实时走。
 */
private fun countdown(startTime: String, nowMillis: Long): Pair<String, String> {
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
 * 教务导出的课名常写成 `101G02A-模拟电子技术`，代码对使用者没有意义，
 * 占的却是最金贵的第一行。前缀必须是「至少三位、且含数字」才剥，
 * 避免把「高等数学-上」这类正常的连写也切掉。
 */
private fun stripCourseCode(name: String): String {
    val match = Regex("""^([0-9A-Za-z]{3,})[-—－](\S.*)$""").find(name.trim()) ?: return name
    if (match.groupValues[1].none { it.isDigit() }) return name
    return match.groupValues[2].trim().ifBlank { name }
}

@Composable
private fun WidgetBody(state: WidgetState) {
    val colors = state.colors
    // 数字与单位分成两行。
    //
    // 同一行放下时，三位数加「分钟后」正好占满 90dp 的内容宽，字号最多到 32sp；
    // 换成上下两行后数字可以到 42sp，上半区才被倒计时填满
    val numberSize = 42.sp
    val unitSize = 11.sp
    val detail = listOf(state.timeRange, state.room)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .cornerRadius(20.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(GlanceModifier.fillMaxSize()) {
            // 上半区贴顶。倒计时是这张卡第一眼要看到的东西，位置不随课名长短浮动
            Column(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.Top
            ) {
                if (state.label.isNotBlank()) {
                    Text(
                        text = state.label,
                        style = TextStyle(
                            color = ColorProvider(colors.title),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }
                if (state.countValue.isNotBlank()) {
                    Text(
                        text = state.countValue,
                        style = TextStyle(
                            color = ColorProvider(colors.alert),
                            fontSize = numberSize,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = state.countUnit,
                        style = TextStyle(
                            color = ColorProvider(colors.body),
                            fontSize = unitSize
                        ),
                        maxLines = 1
                    )
                }
            }

            // 中间吃掉剩余高度，把下面这块推到底部。
            // Glance 的 fillMaxHeight 没有比例重载，用权重间隔是等效做法，
            // 且不受 widget 被拖成多高影响
            Spacer(GlanceModifier.defaultWeight())

            // 下半区贴底：课程信息贴近卡片下沿，与上方倒计时各占一头
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = state.courseName,
                    style = TextStyle(
                        color = ColorProvider(colors.title),
                        fontSize = 13.sp,
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
                            fontSize = 8.sp
                        ),
                        maxLines = 2
                    )
                }
            }
        }
    }
}
