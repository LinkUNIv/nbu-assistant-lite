package app.nbult.lite.browser

import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.net.http.SslError
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nbult.lite.domain.AppClock
import app.nbult.lite.ui.timetable.TimetableUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime

internal object PreloadedPage {
    var web: WebView? = null
    var auth: NativeAuthentication? = null
    val claimed = mutableSetOf<WebView>()
    var transferring = false
    /** 隐形宿主。交还时要把 WebView 塞回这里，所以得留一份引用。 */
    var host: FrameLayout? = null

    fun claim() { if (web != null) { transferring = true; web?.let { claimed.add(it) }; auth?.destroy(); (web?.parent as? android.view.ViewGroup)?.removeView(web) } }

    /**
     * 交还：把 WebView 从点名页摘下、塞回隐形宿主，下次进点名不必重新加载整个 Nuxt 应用。
     *
     * 返回 false 表示宿主已经不在（预加载关闭或组合已销毁），调用方应照旧销毁 WebView。
     */
    fun release(web: WebView): Boolean {
        val target = host ?: return false
        if (!target.isAttachedToWindow) return false
        // 活动侧设置的两个客户端持有 Activity 引用；不解除的话，这一页留在宿主里
        // 会一直把已销毁的 Activity 吊着。下次 claim 时活动会重新设置。
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        // 换回应用上下文：沿用 Activity 当 baseContext 会把整页泄漏到下一次打开
        (web.context as? android.content.MutableContextWrapper)?.baseContext = target.context.applicationContext
        web.alpha = 0f
        target.addView(web, FrameLayout.LayoutParams(-1, -1))
        this.web = web
        transferring = false
        claimed.remove(web)
        return true
    }
}

internal object AttendanceSignal { var courseName: String? = null; val state = MutableStateFlow("idle"); val active = MutableStateFlow(false) }

/** Attached but invisible WebView: keeps the regular school's cookie jar and form handling. */
@Composable
internal fun AttendancePreload(state: TimetableUiState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled by AttendanceOptions.preload.collectAsState()
    LaunchedEffect(Unit) { AttendanceOptions.load(context) }
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { foreground = true; tick++ }
            if (event == Lifecycle.Event.ON_PAUSE) { foreground = false; AttendanceSignal.active.value = false }
        }
        owner.lifecycle.addObserver(observer)
        foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { owner.lifecycle.removeObserver(observer); AttendanceSignal.active.value = false }
    }
    LaunchedEffect(foreground) { while (foreground) { delay(30_000); tick++ } }
    val signedRevision by AttendanceSignedSessions.revision.collectAsState()
    val today = app.nbult.lite.domain.DateUtil.epochDayOf(AppClock.now())
    val now = remember(tick) { Instant.ofEpochMilli(AppClock.now()).atZone(ZoneId.systemDefault()).toLocalTime() }
    val targets = remember(state.todayEntries, state.periods, enabled, foreground, state.todayIsTomorrow, now, today, signedRevision) { if (!enabled || !foreground || state.todayIsTomorrow) emptyList() else state.todayEntries.filter { entry ->
        val start = state.periods.find { it.idx == entry.slot.startSection }?.startTime
        val end = state.periods.find { it.idx == entry.slot.endSection }?.endTime
        !entry.suspended && entry.ghostLabel.isEmpty() && runCatching {
            val minute = now.toSecondOfDay() / 60
            minute >= LocalTime.parse(start).toSecondOfDay()/60 - 15 && minute < LocalTime.parse(end).toSecondOfDay()/60
        }.getOrDefault(false)
    }.map { entry -> AttendanceTarget(entry.course.name,
        state.periods.find { it.idx == entry.slot.startSection }?.startTime.orEmpty(),
        state.periods.find { it.idx == entry.slot.endSection }?.endTime.orEmpty(), attendanceSessionKey(entry)) }
        .filter { !AttendanceSignedSessions.contains(context, today, it.sessionKey) } }
    val names = targets.map { attendanceCourseName(it.name) }.toSet()
    // AndroidView 的 factory 只会跑一次，闭包里必须能读到**最新**的课程列表。
    // 以前靠 key(targets, foreground) 强制重建来「传新值」，代价是每次切前后台
    // 都销毁并重新加载整个 Nuxt 应用；现在改为传引用，只在预加载开关变化时重建。
    val currentTargets by rememberUpdatedState(targets)
    val currentNames by rememberUpdatedState(names)
    DisposableEffect(names) { AttendanceSignal.active.value = false; if(names.isEmpty()) AttendanceSignal.state.value = "idle"; onDispose { AttendanceSignal.active.value = false } }
    if (enabled) {
        var auth by remember { mutableStateOf<NativeAuthentication?>(null) }
        var browser by remember { mutableStateOf<WebView?>(null) }
        LaunchedEffect(browser) {
            while (true) {
                delay(15_000)
                val current = browser
                if (current != null && !PreloadedPage.transferring && !AttendanceSignal.active.value &&
                    current.url?.substringBefore('?')?.trimEnd('/') == AttendanceLandingPolicy.SCHEDULE.trimEnd('/')) {
                    current.reload()
                }
            }
        }
        AndroidView(modifier = Modifier.size(1.dp).alpha(0f), factory = { context ->
            FrameLayout(context).apply {
                PreloadedPage.host = this
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                val web = WebView(android.content.MutableContextWrapper(context)); browser = web
                PreloadedPage.web = web; PreloadedPage.transferring = false
                AttendanceSignal.state.value = "loading"
                addView(web, FrameLayout.LayoutParams(-1,-1))
                web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = BrowserIdentity.ua; allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                var lastDetection = ""
                app.nbult.lite.util.AppLog.log(
                    "AttendancePreload",
                    // 没有待签课程时不要打一个空冒号，日志里那种行等于噪声
                    if (currentNames.isEmpty()) "开始匹配当前课程：无（当前无待签课程）"
                    else "开始匹配当前课程：${currentNames.joinToString()}"
                )
                auth = NativeAuthentication(web, this, silent = true) { rows ->
                    currentTargets.forEach { target ->
                        val signed = rows.filter { it.signed }.map { it.copy(open = true, signed = false) }
                        // With duplicate local names, require the web time to identify the occurrence.
                        val sameNameCount = currentTargets.count { attendanceCourseName(it.name) == attendanceCourseName(target.name) }
                        val identifiable = if (sameNameCount > 1) signed.filter { it.details.contains(target.start) && it.details.contains(target.end) } else signed
                        if (matchingAttendanceCourse(identifiable, listOf(target)) != null) AttendanceSignedSessions.mark(context,today,target)
                    }
                    val remaining = currentTargets.filterNot { AttendanceSignedSessions.contains(context,today,it.sessionKey) }
                    val matching = matchingAttendanceCourse(rows, remaining)
                    if (rows.isNotEmpty()) AttendanceSignal.state.value = "ready"
                    web.evaluateJavascript("window.__nbuAuthenticated===true") { if (it == "true" && !PreloadedPage.transferring && PreloadedPage.web === web) AttendanceSignal.state.value = "ready" }
                    val detection = "目标=${currentNames.joinToString()}；列表=" + rows.joinToString { "${it.title}[open=${it.open},signed=${it.signed},status=${it.status}]" }
                    // 列表为空说明页面还在加载，这种中间态不算「变化」。
                    // 之前空/满交替，每 15 秒就刷两行，一小时 480 行，日志上限只有 500 条。
                    if (rows.isNotEmpty() && lastDetection != detection) {
                        lastDetection = detection
                        app.nbult.lite.util.AppLog.log("AttendancePreload", detection)
                        app.nbult.lite.util.AppLog.log("AttendancePreload", "匹配结果=${matching?.title ?: "无"}")
                    }
                    if (matching != null) {
                        val local = remaining.filter { matchingAttendanceCourse(listOf(matching),listOf(it)) != null }.singleOrNull()
                        if (local != null) AttendanceSignedSessions.matchedSession = Triple(today,matching.title,local)
                    }
                    AttendanceSignal.courseName = matching?.title
                    AttendanceSignal.active.value = matching != null
                    if (matching != null && !PreloadedPage.transferring) {
                        val title = org.json.JSONObject.quote(matching.title)
                        web.evaluateJavascript("""(() => {
                            if(window.__nbuPreloadEntering)return;
                            const e=document.querySelectorAll('.list-item')[${matching.index}];
                            if(e?.querySelector('strong')?.innerText===$title){window.__nbuPreloadEntering=true;e.click();}
                        })()""", null)
                    }
                }
                PreloadedPage.auth = auth
                var returned = false
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme != "https"
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        if (url.substringBefore('?').trimEnd('/') != "https://attendance.nbu.edu.cn/h5/student/sign") {
                            AttendanceSignal.active.value = false; AttendanceSignal.state.value = "loading"
                        }
                        auth?.pageStarted()
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        CookieManager.getInstance().flush()
                        if (!returned && url.substringBefore('?').trimEnd('/') == "https://attendance.nbu.edu.cn/h5/student") {
                            returned = true; view.loadUrl(AttendanceLandingPolicy.SCHEDULE)
                        } else auth?.pageFinished(url)
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(web, """
                    (()=>{const send=XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.send=function(){this.addEventListener('loadend',()=>{try{
                    if(new URL(this.responseURL).pathname==='/api/curriculum/student/getCourse'){
                    const r=JSON.parse(this.responseText);window.__nbuAuthenticated=r.success===true||r.msg==='success';
                    }}catch(_){}},{once:true});return send.apply(this,arguments);};})();
                """.trimIndent(), setOf("https://attendance.nbu.edu.cn"))
                web.loadUrl(AttendanceLandingPolicy.SCHEDULE)
            }
        }, onRelease = {
            auth?.destroy()
            // 宿主随组合一起销毁了，交还的落点没了，下次只能重建
            PreloadedPage.host = null
            if (browser !in PreloadedPage.claimed) {
                browser?.stopLoading(); browser?.destroy()
                if (PreloadedPage.web === browser) PreloadedPage.web = null
            } else PreloadedPage.claimed.remove(browser)
        })
    }
}
