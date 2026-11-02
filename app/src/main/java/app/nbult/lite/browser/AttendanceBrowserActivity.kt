package app.nbult.lite.browser

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.os.SystemClock
import android.view.WindowInsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.File

/** Persistent first-party WebView. Logs deliberately exclude credentials and page bodies. */
class AttendanceBrowserActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var logFile: File
    private var status by mutableStateOf("attendance.nbu.edu.cn")
    private lateinit var nativeAuth: NativeAuthentication
    private val session = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    private var earlyObserver = false
    private val createLog = 704
    private var authenticationSeen = false
    private var returnApplied = false
    companion object {
        private val lock = Any()
        private val logExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "attendance-log").apply { isDaemon = true } }
        const val UA = "Mozilla/5.0 (Linux; Android 15; PKR110 Build/AP3A.240617.008; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/155.0.8059.43 Mobile Safari/537.36 cpdaily/9.9.10 wisedu/9.9.10"
    }
    private fun safeUrl(value: String): String = runCatching {
        val u = Uri.parse(value)
        if (u.scheme !in listOf("https", "http")) return@runCatching "[${u.scheme}]"
        u.buildUpon().encodedAuthority(u.host).clearQuery().fragment(null).build().toString()
    }.getOrDefault("[invalid URL]")
    private fun record(event: String, url: String = "", extra: String = "") {
        val line = JSONObject().put("time", System.currentTimeMillis()).put("event", event)
            .put("session", session).put("sequence", sequence.incrementAndGet())
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("url", safeUrl(url)).put("detail", extra.take(if (app.nbult.lite.BuildConfig.DEBUG) 100_000 else 2000)).toString()
        logExecutor.execute {
            runCatching {
                synchronized(lock) {
                    if (logFile.length() > if (app.nbult.lite.BuildConfig.DEBUG) 50_000_000 else 5_000_000) {
                        val previous = File(logFile.parentFile, "previous.jsonl")
                        previous.delete()
                        logFile.renameTo(previous)
                    }
                    logFile.appendText(line + "\n")
                }
            }.onFailure { android.util.Log.e("AttendanceBrowser", "Cannot write diagnostic log") }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val initialBackground = attendanceBackground(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(initialBackground))
        if (AttendanceSignal.active.value && !PreloadedPage.transferring) {
            if (!intent.hasExtra("preferred_course")) intent.putExtra("preferred_course", AttendanceSignal.courseName)
            PreloadedPage.claim()
        }
        authenticationSeen = state?.getBoolean("attendance_auth_seen") ?: false
        returnApplied = state?.getBoolean("attendance_return_applied") ?: false
        logFile = File(filesDir, "attendance-browser/current.jsonl").also { it.parentFile?.mkdirs() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(initialBackground) }
        root.addView(ComposeView(this).apply {
            setContent {
                AttendanceTheme { dark, _ ->
                    val background = MaterialTheme.colorScheme.background
                    SideEffect {
                        root.setBackgroundColor(background.toArgb())
                        WindowCompat.getInsetsController(window, root).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { if (web.canGoBack()) web.goBack() else finish() }) { Text("返回") }
                                TextButton(onClick = { web.reload() }) { Text("刷新") }
                                TextButton(onClick = {
                                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                                        putExtra(Intent.EXTRA_TITLE, "attendance-browser-${System.currentTimeMillis()}.jsonl")
                                    }, createLog)
                                }) { Text("导出日志") }
                            }
                            Text(status, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                        }
                    }
                }
            }
        })
        val reused = PreloadedPage.web?.takeIf { PreloadedPage.transferring }
        web = reused ?: WebView(this)
        if (reused != null) {
            (web.context as? android.content.MutableContextWrapper)?.baseContext = this
            PreloadedPage.web = null; PreloadedPage.transferring = false
        }
        web.setBackgroundColor(initialBackground)
        web.alpha = 0f
        val browserFrame = FrameLayout(this).apply { setBackgroundColor(initialBackground) }
        browserFrame.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(browserFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        nativeAuth = NativeAuthentication(web, browserFrame, preferredCourse = intent.getStringExtra("preferred_course"))
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; userAgentString = UA
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && AttendanceLandingPolicy.isAuthentication(request.url.toString())) authenticationSeen = true
                val allowed = request.url.scheme == "https"
                record(if (allowed) "navigate" else "blocked_navigation", request.url.toString())
                return !allowed
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                record("request", request.url.toString(), "method=${request.method};mainFrame=${request.isForMainFrame}")
                return null // Never replay requests: POST bodies, cookies and redirects remain WebView-owned.
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                nativeAuth.pageStarted()
                if (AttendanceLandingPolicy.isAuthentication(url)) authenticationSeen = true
                status = Uri.parse(url).host ?: "载入中"; record("page_start", url)
            }
            override fun onPageFinished(view: WebView, url: String) {
                record("page_finish", url); CookieManager.getInstance().flush()
                nativeAuth.pageFinished(url)
                if (!earlyObserver) view.evaluateJavascript(OBSERVER, null)
                // 仅处理最终落地页，不打断认证表单或 ticket 回调；至多自动返回一次。
                if (view.url == url && !returnApplied && AttendanceLandingPolicy.shouldReturn(url, authenticationSeen)) {
                    returnApplied = true
                    record("return_to_schedule", AttendanceLandingPolicy.SCHEDULE)
                    view.loadUrl(AttendanceLandingPolicy.SCHEDULE)
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                record("http_error", request.url.toString(), "status=${response.statusCode}")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                record("network_error", request.url.toString(), "code=${error.errorCode}")
                if (request.isForMainFrame) status = "加载失败，请刷新或查看日志"
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); record("tls_error", error.url, "code=${error.primaryError}")
                status = "证书验证失败"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Never persist arbitrary site console messages: they can contain credentials.
                if (message.message().startsWith("NBU_TRACE:")) runCatching {
                    val obj = JSONObject(message.message().removePrefix("NBU_TRACE:"))
                    record("js_" + obj.optString("kind").take(30), obj.optString("url"),
                        "method=${obj.optString("method").take(12)};status=${obj.optInt("status")};duration=${obj.optLong("duration")};tag=${obj.optString("tag").take(20)}" +
                            if (app.nbult.lite.BuildConfig.DEBUG) ";diagnostic=${obj.optString("diagnostic").take(90000)}" else "")
                }
                else if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    record("console_error", message.sourceId(), "line=${message.lineNumber()};message=redacted")
                }
                return true
            }
            override fun onProgressChanged(view: WebView, progress: Int) {
                status = (Uri.parse(view.url ?: "").host ?: "attendance.nbu.edu.cn") + if (progress < 100) " · $progress%" else ""
            }
        }
        earlyObserver = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (earlyObserver) WebViewCompat.addDocumentStartJavaScript(web, OBSERVER, setOf("https://*.nbu.edu.cn", "https://nbu.edu.cn"))
        record("session_start", AttendanceLandingPolicy.SCHEDULE, "UA=$UA; earlyObserver=$earlyObserver; webview=${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}; coverage=navigation,requests,http-errors,fetch-xhr,clicks,forms,history; diagnostics=debug-attendance-api-bodies-and-sign-page; excludes=auth-bodies,headers,cookies,passwords,tokens; service-worker and native-bridge traffic may be missing")
        if (reused != null) { web.evaluateJavascript(OBSERVER, null); nativeAuth.adoptPage() } else if (state == null || web.restoreState(state) == null) web.loadUrl(AttendanceLandingPolicy.SCHEDULE)
    }
    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == createLog && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            logExecutor.execute {
                runCatching {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                        synchronized(lock) {
                            listOf(File(logFile.parentFile, "previous.jsonl"), logFile)
                                .filter { it.exists() }.forEach { file -> file.forEachLine { out.appendLine(it) } }
                        }
                    } ?: error("No output stream")
                }.onSuccess { runOnUiThread { Toast.makeText(this, "日志已导出", Toast.LENGTH_SHORT).show() } }
                 .onFailure { runOnUiThread { Toast.makeText(this, "日志导出失败", Toast.LENGTH_LONG).show() } }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("attendance_auth_seen", authenticationSeen)
        outState.putBoolean("attendance_return_applied", returnApplied)
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { nativeAuth.pause(); CookieManager.getInstance().flush(); web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) { web.onResume(); nativeAuth.resume() } }
    override fun onDestroy() { nativeAuth.destroy(); web.stopLoading(); web.destroy(); super.onDestroy() }
    private val OBSERVER by lazy { """
        (() => {
          if (window.__nbuTrace) return; window.__nbuTrace = true;
          const emit = x => console.log('NBU_TRACE:' + JSON.stringify(x));
          const clean = v => { try { const u = new URL(v, location.href); return u.origin + u.pathname; } catch (_) { return ''; } };
          document.addEventListener('click', e => emit({kind:'click', url:clean(location.href), tag:e.target.tagName}), true);
          document.addEventListener('submit', () => emit({kind:'submit',url:clean(location.href)}), true);
          window.addEventListener('error', e => emit({kind:'script_error',url:clean(e.filename||location.href)}));
          window.addEventListener('unhandledrejection', () => emit({kind:'promise_error',url:clean(location.href)}));
          ['pushState','replaceState'].forEach(key => {
            const fn=history[key]; history[key]=function(){ const r=fn.apply(this,arguments); emit({kind:key,url:clean(location.href)}); return r; };
          });
          window.addEventListener('hashchange',()=>emit({kind:'hashchange',url:clean(location.href)}));
          window.addEventListener('popstate',()=>emit({kind:'popstate',url:clean(location.href)}));
          const original = window.fetch;
          window.fetch = async function(input, init) {
            const url = clean(typeof input === 'string' ? input : (input instanceof URL ? input.href : input.url)), start=Date.now();
            const method = (init && init.method) || (input && input.method) || 'GET';
            try { const r = await original.apply(this, arguments); emit({kind:'fetch',url,method,status:r.status,duration:Date.now()-start}); return r; }
            catch(e) { emit({kind:'fetch_error',url,method,duration:Date.now()-start}); throw e; }
          };
          const open=XMLHttpRequest.prototype.open, send=XMLHttpRequest.prototype.send;
          const meta=new WeakMap();
          XMLHttpRequest.prototype.open=function(method,url) { meta.set(this,{method,url:clean(url)}); return open.apply(this,arguments); };
          XMLHttpRequest.prototype.send=function() { const m=meta.get(this)||{}, start=Date.now(); this.addEventListener('loadend',()=>emit({kind:'xhr',url:m.url,method:m.method,status:this.status,duration:Date.now()-start}),{once:true}); return send.apply(this,arguments); };
        })();
    """.trimIndent() + if (app.nbult.lite.BuildConfig.DEBUG) assets.open("attendance-diagnostics.js").bufferedReader().use { it.readText() } else "" }
}
