package app.nbult.lite.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.nbult.lite.R
import androidx.compose.ui.unit.sp
import app.nbult.lite.domain.AppClock
import app.nbult.lite.ui.common.RailMetrics
import app.nbult.lite.ui.common.acrylicPanel
import java.util.Calendar

/** 侧栏顶部那块要显示的三种状态。 */
sealed interface RailProgress {

    /** 没课：显示下一节的开始时刻。 */
    data class NextClass(val startAt: Long) : RailProgress

    /**
     * 上课中。
     *
     * [remainingMin] 是本节课剩余分钟（向下取整到整分，最小 1）；
     * [fraction] 是 **剩余 / 总长** —— 注意语义是"还剩多少"，不是"过了多少"。
     */
    data class InClass(val remainingMin: Int, val fraction: Float) : RailProgress

    /** 没有学期、课表为空、或者往后都没课。 */
    object None : RailProgress
}

/** 动画用的稳定 key：分钟变化时值是同一个，所以不会触发转场。 */
private val RailProgress.kind: String
    get() = when (this) {
        is RailProgress.NextClass -> "next"
        is RailProgress.InClass -> "class"
        is RailProgress.None -> "none"
    }

private val NumberSlotHeight = 34.dp

/**
 * 标签槽高度。
 *
 * 0.27.0 这里是 13dp，结果「剩余」下半截被切掉了 —— `Modifier.height()` 同时设 min 和 max，
 * 它就成了 Text 的**最大高度约束**，字体行高一超过 13dp 就被压掉。
 * 现在给到 20dp，并且一律用 `heightIn(min = …)`：盒子能随字号长大，不再压字。
 */
private val LabelSlotHeight = 20.dp

/**
 * 顶部进度胶囊。
 *
 * 三个槽从上到下：主数字 / 小标签 / 次数字。三种状态共用同一副骨架，
 * 只换槽里的内容 —— 这样切换时轮廓不动，`AnimatedContent` 才有东西可演。
 *
 * 进度用**内缩一圈的胶囊描边**表示，不新增任何元素：
 * 路径从 12 点起顺时针构建，`PathMeasure` 截取"剩余比例 × 周长"那一段，
 * 时间流逝时截取末端沿原路逆时针收回，回到 12 点即归零。
 * 之所以不用圆环：胶囊不是圆，硬套圆环会打架；也不用填充排空，那会盖住两行数字。
 */
@Composable
fun ProgressCapsule(progress: RailProgress, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    val inClass = progress as? RailProgress.InClass
    // 只有上课中才画彩色段；其余状态整圈都是中性轨道，形状保持一致
    val fraction by animateFloatAsState(
        targetValue = (inClass?.fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "railRing"
    )

    val slidePx = with(LocalDensity.current) { 6.dp.roundToPx() }

    Box(
        modifier
            .width(RailMetrics.Width)
            .height(RailMetrics.ProgressHeight)
            .acrylicPanel(RoundedCornerShape(RailMetrics.Width / 2)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(RailMetrics.ProgressHeight)) {
            val inset = 5.dp.toPx()
            val strokeWidth = 1.5.dp.toPx()
            val radius = (size.width - inset * 2f) / 2f
            val left = inset
            val right = size.width - inset
            val top = inset
            val bottom = size.height - inset

            // 从 12 点起、顺时针画一圈。方向很重要 ——
            // 反过来的话"逆时针回缩"会变成"顺时针生长"
            val path = Path().apply {
                moveTo(left + radius, top)
                arcTo(Rect(left, top, right, top + radius * 2f), -90f, 90f, false)
                lineTo(right, bottom - radius)
                arcTo(Rect(left, bottom - radius * 2f, right, bottom), 0f, 180f, false)
                lineTo(left, top + radius)
                arcTo(Rect(left, top, right, top + radius * 2f), 180f, 90f, false)
                close()
            }

            drawPath(path, track, style = Stroke(strokeWidth))

            if (fraction > 0.004f) {
                val measure = PathMeasure().apply { setPath(path, false) }
                val segment = Path()
                if (measure.getSegment(0f, measure.length * fraction, segment, true)) {
                    drawPath(
                        segment,
                        primary,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
        }

        AnimatedContent(
            targetState = progress,
            contentKey = { it.kind },
            contentAlignment = Alignment.Center,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(220)) { slidePx }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(220)) { -slidePx })
            },
            label = "railSlots"
        ) { state ->
            Slots(state)
        }
    }
}

@Composable
private fun Slots(progress: RailProgress) {
    val top: String
    val label: String
    val bottom: String
    var bottomIsUnit = false

    when (progress) {
        is RailProgress.NextClass -> {
            val calendar = Calendar.getInstance(AppClock.ZONE).apply { timeInMillis = progress.startAt }
            top = twoDigits(calendar.get(Calendar.HOUR_OF_DAY))
            label = "下一节"
            bottom = twoDigits(calendar.get(Calendar.MINUTE))
        }

        is RailProgress.InClass -> {
            val minutes = progress.remainingMin.coerceAtLeast(1)
            if (minutes >= 60) {
                // 时刻补两位、时长不补 —— "1 / 剩余 / 20" 读作 1 小时 20 分
                top = (minutes / 60).toString()
                label = "剩余"
                bottom = twoDigits(minutes % 60)
            } else {
                // 跨过 60 分这一档，下槽从数字换成单位，
                // 这是唯一能让人分清"1:20 是时长不是十点二十"的线索
                top = minutes.toString()
                label = "剩余"
                bottom = "min"
                bottomIsUnit = true
            }
        }

        is RailProgress.None -> {
            top = "—"
            label = "无课"
            bottom = ""
        }
    }

    val numberColor = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // 数字用内嵌的 Noto Serif 子集，不再用 FontFamily.Serif。
    // 后者是"这台机器的衬线"：小米是自家字体、AOSP 是 Noto Serif、
    // 个别 ROM 的 Serif 还会回退成无衬线（数字直接变正体）——
    // 同一份 apk 在不同机型上字形不一致，只能靠打包字体解决。
    //
    // 子集只含 `0-9` 与「—」，2.5KB；要加字符必须同步改 tools/subset-font.py 的 CHARS，
    // 否则缺字是**静默**回退到系统字体，不报错也不容易发现。
    val numberFamily = FontFamily(Font(R.font.noto_serif_subset))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Slot(
            text = top,
            height = NumberSlotHeight,
            size = if (top.length > 2) 22.sp else 28.sp,
            family = numberFamily,
            color = numberColor
        )
        Box(
            Modifier.heightIn(min = LabelSlotHeight).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, fontSize = 10.sp, color = muted, maxLines = 1)
        }
        Slot(
            text = bottom,
            height = NumberSlotHeight,
            size = if (bottomIsUnit) 12.sp else if (bottom.length > 2) 22.sp else 28.sp,
            // 「min」这类单位仍走默认 sans；只有数字用衬线，两者混排是有意的
            family = if (bottomIsUnit) FontFamily.Default else numberFamily,
            color = if (bottomIsUnit) muted else numberColor
        )
    }
}

@Composable
private fun Slot(
    text: String,
    height: Dp,
    size: TextUnit,
    family: FontFamily,
    color: androidx.compose.ui.graphics.Color
) {
    Box(
        // heightIn 而不是 height：后者会变成 Text 的最大高度，字号一放大就切字
        Modifier.heightIn(min = height).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = size,
            fontFamily = family,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()
