package app.nbult.lite.browser

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.SystemClock
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.MotionEvent
import java.util.concurrent.TimeUnit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun SignCameraDialog(success: Boolean, resultVersion: Int, message: String, onCode: (String) -> Unit, onDismiss: () -> Unit, testMode: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var status by remember { mutableStateOf("请让完整投影或屏幕画面进入取景范围，无需单独框住签到码") }
    var zoom by remember { mutableStateOf(1f) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var focusStatus by remember { mutableStateOf("") }
    var inFlight by remember { mutableStateOf(false) }
    val tried = remember { mutableSetOf<String>() }
    val currentOnCode by rememberUpdatedState(onCode)
    val stopped = remember { AtomicBoolean(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (!it) status = "需要相机权限，请授权后重试。" }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(resultVersion) {
        if (!testMode && resultVersion > 0) {
            if (success) { stopped.set(true); onDismiss() }
            else { kotlinx.coroutines.delay(200); inFlight = false; status = "上次未通过，继续识别新的签到码。同一码不重复提交。" }
        }
    }
    LaunchedEffect(inFlight) {
        if (inFlight) { kotlinx.coroutines.delay(15_000); status = "等待学校验证结果中，暂不重复提交。可停止后手动查看提示。" }
    }
    Dialog(onDismissRequest = { stopped.set(true); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (testMode) "相机 OCR 测试" else "相机自动验证", style = MaterialTheme.typography.headlineSmall)
                Text(if (testMode) "只显示识别到的四位数字，不提交签到；手动退出前持续识别。" else "仅在本机识别，不保存或上传图片。识别到有效四位数字即提交；成功后停止。", style = MaterialTheme.typography.bodySmall)
                if (granted) {
                    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER } }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.Center).fillMaxSize().padding(6.dp).border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary)))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("双指缩放 · 点击对焦 · ${"%.1f".format(zoom)}×", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            camera?.let { c ->
                                val range = c.cameraInfo.zoomState.value
                                zoom = 1f.coerceIn(range?.minZoomRatio ?: 1f, range?.maxZoomRatio ?: 1f)
                                c.cameraControl.setZoomRatio(zoom); c.cameraControl.cancelFocusAndMetering(); focusStatus = ""
                            }
                        }) { Text("复位") }
                    }
                    if (focusStatus.isNotBlank()) Text(focusStatus, style = MaterialTheme.typography.bodySmall)
                    DisposableEffect(preview, owner) {
                        val executor = Executors.newSingleThreadExecutor()
                        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                        val main = ContextCompat.getMainExecutor(context)
                        val providerFuture = ProcessCameraProvider.getInstance(context)
                        var provider: ProcessCameraProvider? = null
                        var analysis: ImageAnalysis? = null
                        var previewUse: Preview? = null
                        var lastFrame = 0L
                        var frame = 0
                        providerFuture.addListener({
                            if (!stopped.get()) runCatching {
                                provider = providerFuture.get()
                                previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                                analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(1280,720))
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { use ->
                                        use.setAnalyzer(executor) { proxy ->
                                            val now = SystemClock.elapsedRealtime()
                                            if (stopped.get() || now - lastFrame < 150) { proxy.close(); return@setAnalyzer }
                                            lastFrame = now
                                            var processed: Bitmap? = null
                                            try {
                                                val raw = proxy.toBitmap()
                                                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }, true)
                                                processed = SignImagePreprocessor.prepare(rotated,frame++)
                                                setOf(raw,rotated).forEach { it.recycle() }
                                                val bitmap = requireNotNull(processed)
                                                recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener(main) { text ->
                                                    if (!stopped.get() && owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                                                        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { OcrLine(line.text,it.left,it.top,it.right,it.bottom) } }
                                                        val candidate = SignCodeRecognition.candidate(lines)
                                                        if (candidate != null) {
                                                            if (!inFlight) status = "识别到 $candidate"
                                                            if (testMode) {
                                                                status = "识别结果：$candidate（持续识别中）"
                                                                currentOnCode(candidate)
                                                            }
                                                            if (!testMode && !inFlight && candidate !in tried) {
                                                                tried.add(candidate); inFlight = true; status = "正在验证 $candidate…"
                                                                currentOnCode(candidate)
                                                            }
                                                        }
                                                    }
                                                }.addOnFailureListener(main) { if (!stopped.get()) status = "识别失败，请调整距离或光线。" }
                                                    .addOnCompleteListener { bitmap.recycle(); proxy.close() }
                                            } catch (_: Exception) { processed?.recycle(); proxy.close() }
                                        }
                                    }
                                camera = provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *listOfNotNull(previewUse, analysis).toTypedArray())
                                val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                                        camera?.let { c ->
                                            val range = c.cameraInfo.zoomState.value ?: return true
                                            zoom = (zoom * detector.scaleFactor).coerceIn(range.minZoomRatio, range.maxZoomRatio)
                                            c.cameraControl.setZoomRatio(zoom)
                                        }; return true
                                    }
                                })
                                var pinched = false
                                val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                                    override fun onDown(e: MotionEvent) = true
                                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                                        if (pinched || scales.isInProgress) return true
                                        focusStatus = "正在对焦…"
                                        camera?.let { c ->
                                            val point = preview.meteringPointFactory.createPoint(e.x,e.y)
                                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
                                            val future = c.cameraControl.startFocusAndMetering(action)
                                            future.addListener({ if (!stopped.get()) focusStatus = runCatching { if (future.get().isFocusSuccessful) "对焦完成" else "已测光，请调整距离后再试" }.getOrDefault("对焦未完成，请重试") }, main)
                                        }
                                        preview.performClick(); return true
                                    }
                                })
                                preview.setOnTouchListener { _, e ->
                                    if (e.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
                                    if (e.pointerCount > 1) pinched = true
                                    scales.onTouchEvent(e); taps.onTouchEvent(e); true
                                }
                            }.onFailure { status = "无法打开相机，请关闭其他相机应用后重试。" }
                        }, main)
                        onDispose {
                            stopped.set(true); preview.setOnTouchListener(null); camera = null; analysis?.clearAnalyzer()
                            listOfNotNull(previewUse, analysis).forEach { provider?.unbind(it) }
                            executor.shutdown(); recognizer.close()
                        }
                    }
                } else {
                    TextButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授予相机权限") }
                }
                Text(status)
                if (message.isNotBlank()) Text(message, style = if (testMode) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.bodySmall)
                Button(onClick = { stopped.set(true); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(if (testMode) "退出 OCR 测试" else "停止识别 / 返回手动输入") }
            }
        }
    }
}
