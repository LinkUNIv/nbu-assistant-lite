package app.nbult.lite.ui.common

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 全应用**唯一**的底部抽屉实现。
 *
 * 之前三个抽屉（导入课表 / 手动添加 / 课程设置）各自写一遍 `ModalBottomSheet`，
 * 于是同一个坑要在三处分别踩、分别修。收成一个组件之后，下面三件事只做一次：
 *
 * ### 1. 窗口 insets：scrim 必须能压到状态栏
 * M3 1.2 把 `windowInsets` 加在**弹层根节点**上，而 scrim 就在那个根节点内部
 * （`Box(...).windowInsetsPadding(windowInsets) { BoxWithConstraints { Scrim(); Surface() } }`）。
 * 所以只要传了非零的垂直 insets，被内缩的不只是抽屉，连压暗层也一起缩 ——
 * 屏幕上会出现"状态栏那条亮、下面突然变灰"的断层。
 * 这里一律不索要系统栏：33+ 传 0（M3 自己会追加 `imePadding()`），
 * 33 以下只取 `ime` 底部；导航栏改由内容 `navigationBarsPadding()` 避让。
 *
 * ### 2. 进退都要有动画
 * 调用方通常用 `payload/visible` 表示"要显示"。如果状态一变 false 就把整块从组合里摘掉，
 * 抽屉是**啪地消失**的，M3 的下滑动画根本没机会播。这里留一份 `lastPayload`，
 * 先 `hide()` 播完下滑再移除 —— 于是"点关闭""点停掉这一节""保存成功"等
 * 所有关闭路径自动都带上了动画，不用给每个按钮单独包回调。
 *
 * ### 3. 内容高度变化要过渡
 * 抽屉里常有"切模式换内容块"（如正常上课 / 停课 / 调整时间），高度会当场跳变。
 * 统一的 `animateContentSize` 让抽屉整体上下浮动。用 tween 不用 spring：
 * 高度动画一旦过冲会短暂露出空白，比直接跳更难看。
 *
 * @param payload 抽屉要展示的数据；变 null 即触发退出动画后移除。
 * @param content 拿到**最后一次非空**的 payload 渲染内容，退出动画期间仍可正常显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Any> AppBottomSheet(
    payload: T?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var lastPayload by remember { mutableStateOf(payload) }
    LaunchedEffect(payload) {
        if (payload != null) {
            lastPayload = payload
        } else if (lastPayload != null) {
            // 先播下滑动画，再从组合里移除；hide() 对已隐藏的抽屉是空操作
            runCatching { sheetState.hide() }
            lastPayload = null
        }
    }

    val current = lastPayload ?: return
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        windowInsets = sheetWindowInsets(),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(
                modifier = modifier
                    .navigationBarsPadding()
                    .animateContentSize(tween(220, easing = MotionSpecs.Decelerate))
            ) {
                content(current)
            }
        }
    }
}

/**
 * 没有独立数据的抽屉（内容自己从外部状态读）：只需要一个可见开关。
 *
 * 用 `Unit` 当 payload —— 既复用了上面那套进退动画，又不必为"无数据"再造一份实现。
 */
@Composable
fun AppBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AppBottomSheet(
        payload = if (visible) Unit else null,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        content()
    }
}

/**
 * 抽屉弹层的窗口 insets —— 只处理键盘，不碰系统栏。
 *
 * 详细原因见 [AppBottomSheet] 的注释：任何垂直方向的系统栏内边距都会被加在
 * **包含 scrim 的根节点**上，导致压暗层缩在状态栏下方。
 */
@Composable
private fun sheetWindowInsets(): WindowInsets =
    if (Build.VERSION.SDK_INT >= 33) {
        WindowInsets(0, 0, 0, 0)
    } else {
        // 33 以下 M3 不代管键盘，只能自己顶；只取底部，避免再把顶部缩掉
        WindowInsets.ime.only(WindowInsetsSides.Bottom)
    }
