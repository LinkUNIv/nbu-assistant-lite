@file:OptIn(ExperimentalFoundationApi::class)

package app.nbult.lite.ui.common

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 上下滑动的滚轮选择器（秒表那种数字轮）。
 *
 * **不用 LazyColumn**。上一版用 LazyList 的 `item.offset` 反推中线，结果方框和真正选中的项
 * 差了两行（选中的跑到底部、方框停在中间）——它的坐标系（含不含 contentPadding、以谁为原点）
 * 在换行数时会变，靠约定去对齐迟早出错。
 *
 * 现在只有一个真相：**像素滚动量 [scrollPx]**。
 * - 中线项 = `scrollPx / itemHeight`（浮点索引）；
 * - 每项的位移 = `(index - 中线项) * itemHeight`，由 `graphicsLayer` 每帧算（只失效图层，不重组）；
 * - 方框固定画在容器正中，而 `d == 0` 的项也必然落在容器正中 —— 两者同源，不可能再错位。
 *
 * 手感：拖动 1:1 跟手；松手后按速度折算还能滑几格（[FLING_DAMPING] 越小越"重"），
 * 再用一条偏硬的 spring 吸附到整格；越过一格打一次 TICK 震动。
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 34.dp,
    visibleItems: Int = 5,
    haptics: Boolean = true
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val itemH = with(density) { itemHeight.toPx() }
    val cameraDistancePx = 14f * density.density
    val half = visibleItems / 2
    val maxScroll = items.lastIndex * itemH

    var scrollPx by remember {
        mutableFloatStateOf(selectedIndex.coerceIn(0, items.lastIndex) * itemH)
    }
    var dragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // 文字只需在"越过半格"时换一批；位移/缩放/模糊每帧在图层里算
    val baseIndex by remember(itemH, items.size) {
        derivedStateOf { (scrollPx / itemH).roundToInt().coerceIn(0, items.lastIndex) }
    }

    val blurSmall = rememberBlurEffect(2.5f)
    val blurLarge = rememberBlurEffect(5f)
    val wheelHaptics = rememberAppHaptics()
    val settleSpec = remember { spring<Float>(dampingRatio = 0.92f, stiffness = 260f) }

    /** 松手：按速度折算目标格，再吸附过去。velocity 正 = 手指往下（回到更早的项）。 */
    fun settleWith(velocity: Float) {
        settleJob?.cancel()
        val floatIndex = scrollPx / itemH
        val projected = floatIndex - velocity / itemH * FLING_DAMPING
        val target = projected.roundToInt().coerceIn(0, items.lastIndex)
        settleJob = scope.launch {
            // 这里**不再**回调 onSelect：值的变化由下面的实时上报负责。
            // 以前只在吸附结束时回调一次，用户在"还在回弹"时点保存，父级拿到的还是上一格 ——
            // 于是"红字提示要第二次点击才出现"（第一次存的是旧周次，被吞掉后又被回调清空）。
            Animatable(scrollPx).animateTo(target * itemH, settleSpec) { scrollPx = value }
        }
    }

    if (haptics) {
        // drop(1)：刚打开时不要先震一下
        LaunchedEffect(itemH, items.size) {
            snapshotFlow { (scrollPx / itemH).roundToInt() }
                .distinctUntilChanged()
                .drop(1)
                .collect { wheelHaptics.wheelTick() }
        }
    }

    // items 变少（周数上限缩了）时把滚动量夹回来
    LaunchedEffect(items.size, itemH) {
        val clamped = scrollPx.coerceIn(0f, maxScroll)
        if (clamped != scrollPx) scrollPx = clamped
    }

    // 外部把值改了（开始周被顶到结束周之后）→ 轮子自己滚过去
    LaunchedEffect(selectedIndex, itemH, items.size) {
        val target = selectedIndex.coerceIn(0, items.lastIndex) * itemH
        // settleJob 在跑时不能插手：父级值现在会随滚动实时变化，否则每次上报都会和吸附打架
        if (!dragging && settleJob?.isActive != true && abs(scrollPx - target) > 0.5f) {
            Animatable(scrollPx).animateTo(target, settleSpec) { scrollPx = value }
        }
    }

    /**
     * 实时上报：中线最近的一格一变，就立刻告诉父级。
     *
     * 父级拿到的值因此**总是和画面上一致**，不需要等吸附动画结束；
     * 保存类操作用的一定是用户眼前那一格，而不是上一格。
     * 只在"越过半格"时才触发，滚动一整屏也就十来次，开销可以忽略。
     */
    val liveIndex by remember(itemH, items.size) {
        derivedStateOf { (scrollPx / itemH).roundToInt().coerceIn(0, items.lastIndex) }
    }
    LaunchedEffect(liveIndex) { onSelect(liveIndex) }

    val dragState = rememberDraggableState { delta ->
        scrollPx = (scrollPx - delta).coerceIn(0f, maxScroll)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight * visibleItems)
            .clipToBounds()
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = {
                    dragging = true
                    settleJob?.cancel()
                },
                onDragStopped = { velocity ->
                    dragging = false
                    settleWith(velocity)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // 选中框：固定在容器正中。d == 0 的项同样落在正中，所以方框里永远是选中的那一项
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                    shape = RoundedCornerShape(12.dp)
                )
        )

        // 只铺当前中线圈附近 ±(半屏+1) 个槽；越界的项根本不组进树
        for (k in -half - 1..half + 1) {
            val index = baseIndex + k
            if (index !in items.indices) continue
            val selected = index == baseIndex
            key(index) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            val d = index - scrollPx / itemH
                            val near = abs(d)
                            translationY = d * itemH
                            rotationX = d.coerceIn(-1.6f, 1.6f) * 12f
                            this.cameraDistance = cameraDistancePx
                            transformOrigin = TransformOrigin.Center
                            val scale = 1f - near.coerceAtMost(2f) * 0.05f
                            scaleX = scale
                            scaleY = scale
                            alpha = (1f - near * 0.3f).coerceIn(0.12f, 1f)
                            // 模糊只给最外那一行：紧邻中线的行只是变淡变小，
                            // 整片糊掉会显得发虚，反而不像轮子
                            renderEffect = when {
                                near < 1.4f -> null
                                near < 2.2f -> blurSmall
                                else -> blurLarge
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = items[index],
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/** 松手速度折算成"还能滑几格"的系数：越小越重。 */
private const val FLING_DAMPING = 0.2f

/** 只在 API 31+ 且没开省电模式时给模糊；其余返回 null，自动退化成纯透明衰减。 */
@Composable
private fun rememberBlurEffect(radius: Float): BlurEffect? {
    val context = LocalContext.current
    val powerSave = remember(context) {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.isPowerSaveMode
        }.getOrNull() ?: false
    }
    return remember(radius, powerSave) {
        if (Build.VERSION.SDK_INT >= 31 && !powerSave) BlurEffect(radius, radius) else null
    }
}

/**
 * 节次输入：`开始　第 [ 3 ] 节　　　　+ −`
 *
 * 加减键右对齐、+ 在左 − 在右；数字框两侧夹"第 / 节"，一眼能读出这是第几节。
 *
 * 光标：状态用 TextFieldValue 而不是 String，选区不会被父级重组冲掉；
 * 按键盘"完成"立即收焦点并收起键盘 —— 这是"编辑完光标还一直闪"的根因。
 * 获得焦点时主动 bringIntoView，抽屉被键盘顶起后输入框要自动停在键盘上方。
 */
@Composable
fun SectionStepper(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    range: IntRange = 1..12,
    onChange: (Int) -> Unit
) {
    var field by remember { mutableStateOf(TextFieldValue(value.toString())) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 父级把值顶过来（结束节被拉到开始节）时同步文本，但不打断正在输入的内容
    LaunchedEffect(value) {
        if (field.text.toIntOrNull() != value) {
            val text = value.toString()
            field = TextFieldValue(text, selection = TextRange(text.length))
        }
    }

    // 键盘弹出 → 窗口重新布局 → 再滚。等一拍，否则滚到位了又被顶回去
    LaunchedEffect(focused) {
        if (focused) {
            delay(240)
            requester.bringIntoView()
        }
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp)
        )
        Text(
            text = "第",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 34.dp)
                .bringIntoViewRequester(requester)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (focused) 0.6f else 0.3f
                    ),
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = field,
                onValueChange = { tfv ->
                    // 只收数字、最多两位：手滑输入字母或粘贴一长串时不会把状态写坏
                    val digits = tfv.text.filter { it.isDigit() }.take(2)
                    field = TextFieldValue(digits, selection = TextRange(digits.length))
                    digits.toIntOrNull()?.let { if (it in range) onChange(it) }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() }
                }
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "节",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepperKey("+") { onChange((value + 1).coerceIn(range.first, range.last)) }
            StepperKey("−") { onChange((value - 1).coerceIn(range.first, range.last)) }
        }
    }
}

@Composable
private fun StepperKey(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 34.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
