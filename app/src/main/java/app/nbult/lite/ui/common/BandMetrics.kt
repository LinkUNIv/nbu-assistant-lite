package app.nbult.lite.ui.common

import androidx.compose.ui.unit.dp

/**
 * 顶部横带的公共尺寸。
 *
 * 右侧圆钮和课表周次栏要"同顶同高"才看起来像一条横带，而它们分别住在
 * `ui/nav/SideRail` 和 `ui/timetable/TimetableScreen` 两个互不相干的
 * Composable 里。数值写在任意一边，改一边忘一边就会错位，所以单独放这里。
 */
object BandMetrics {

    /** 状态栏下沿到横带顶边的距离。 */
    val TopInset = 6.dp

    /**
     * 横带高度 = 圆钮直径 = 周次栏卡片高度 = **56dp**。
     *
     * 56 不是随手挑的：胶囊与圆钮要"半径相同"，而两者的半径都等于自身高度的一半
     * （`CircleShape` 取最短边一半，`percent = 50` 的胶囊取短边一半）。
     * 想统一成 28dp，圆钮就必须是 56×56 —— `RoundedCornerShape(28.dp)` 加在 48dp 上
     * 会被钳回 24，改不动。
     */
    val Height = 56.dp

    /**
     * 周次栏三档阈值，按**胶囊可用宽**判断（= 屏宽 − 64 栏 − 16 内边距）。
     *
     * 顶栏固定开销约 202dp（箭头 48×2 + 「本周」58 + ＋48），行内再加 30dp，
     * 所以中心列可用宽 ≈ 屏宽 − 296 —— 360dp 屏只剩 64dp，而日期行要 114dp，
     * 不分档就必然被裁。
     */
    val HeaderFullMin = 306.dp

    val HeaderCompactMin = 250.dp

    /** 系统字号 ≥ 这个值时整体降一档，否则放大后的文字会把余量吃回去。 */
    const val FontScaleDemote = 1.3f
}

/** 右侧栏的尺寸。栏、进度胶囊、导航块都从这里取，免得三处各写一套。 */
object RailMetrics {

    /** 栏宽。页面要为它让出 `Width + Gutter`。 */
    val Width = 56.dp

    /** 栏与屏幕右缘的间距。 */
    val Gutter = 8.dp

    /** 与 [BandMetrics.Height] 同值：撑满栏宽，半径自然等于 28。 */
    val DarkToggle = 56.dp

    val ProgressHeight = 100.dp

    val NavHeight = 212.dp

    /**
     * 导航块在**手势区之外**再让的距离。
     *
     * 系统导航栏 inset 在部分 ROM 的全面屏手势模式下是 0，真正的手势区在
     * `mandatorySystemGestures` 里；两者取大之后再加这个值，才是设计想要的
     * "离屏底约 36dp"。固定值试过，在 12.6dp inset 的机器上岛会贴到底。
     */
    val NavBottomExtra = 12.dp

    /** 圆钮与进度胶囊之间的间距。 */
    val ToggleToProgress = 12.dp
}
