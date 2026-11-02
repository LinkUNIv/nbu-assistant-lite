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
    fun claim() { if (web != null) { transferring = true; web?.let { claimed.add(it) }; auth?.destroy(); (web?.parent as? android.view.ViewGroup)?.removeView(web) } }
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
    val now = remember(tick) { Instant.ofEpochMilli(AppClock.now()).atZone(ZoneId.systemDefault()).toLocalTime() }
    val names = if (!enabled || !foreground || state.todayIsTomorrow) emptySet() else state.todayEntries.filter { entry ->
        val start = state.periods.find { it.idx == entry.slot.startSection }?.startTime
        val end = state.periods.find { it.idx == entry.slot.endSection }?.endTime
        !entry.suspended && entry.ghostLabel.isEmpty() && runCatching {
            val minute = now.toSecondOfDay() / 60
            minute >= LocalTime.parse(start).toSecondOfDay()/60 - 15 && minute < LocalTime.parse(end).toSecondOfDay()/60
        }.getOrDefault(false)
    }.map { it.course.name.replace(Regex("\\s+"), "") }.toSet()
    DisposableEffect(names) { AttendanceSignal.active.value = false; if(names.isEmpty()) AttendanceSignal.state.value = "idle"; onDispose { AttendanceSignal.active.value = false } }
    if (names.isNotEmpty()) key(names, foreground) {
        var auth by remember { mutableStateOf<NativeAuthentication?>(null) }
        var browser by remember { mutableStateOf<WebView?>(null) }
        AndroidView(modifier = Modifier.size(1.dp).alpha(0f), factory = { context ->
            FrameLayout(context).apply {
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                val web = WebView(android.content.MutableContextWrapper(context)); browser = web
                PreloadedPage.web = web; PreloadedPage.transferring = false
                AttendanceSignal.state.value = "loading"
                addView(web, FrameLayout.LayoutParams(-1,-1))
                web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = BrowserIdentity.ua; allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                auth = NativeAuthentication(web, this, silent = true) { rows ->
                    val matching = rows.firstOrNull { it.open && it.title.replace(Regex("\\s+"), "") in names }
                    if (rows.isNotEmpty()) AttendanceSignal.state.value = "ready"
                    web.evaluateJavascript("window.__nbuAuthenticated===true") { if (it == "true" && !PreloadedPage.transferring && PreloadedPage.web === web) AttendanceSignal.state.value = "ready" }
                    AttendanceSignal.courseName = matching?.title
                    AttendanceSignal.active.value = matching != null
                }
                PreloadedPage.auth = auth
                var returned = false
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme != "https"
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { AttendanceSignal.active.value = false; AttendanceSignal.state.value = "loading"; auth?.pageStarted() }
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
            if (browser !in PreloadedPage.claimed) {
                browser?.stopLoading(); browser?.destroy()
                if (PreloadedPage.web === browser) PreloadedPage.web = null
            } else PreloadedPage.claimed.remove(browser)
        })
    }
}
