package app.nbult.lite.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import androidx.compose.runtime.mutableStateOf
import org.json.JSONTokener

/** Native controls forward input to the trusted university form; no credential logging or JavaScript bridge. */
internal class NativeAuthentication(private val web: WebView, private val container: FrameLayout, private val silent: Boolean = false, private val preferredCourse: String? = null, private val onCourses: (List<CourseRow>) -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private var panel: ComposeView? = null
    private var mode = ""
    private var useOriginal = false
    private var autoAttempted = false
    private var generation = 0
    private var authenticationPageFinished = false
    private var autoCourseEntered = false
    private var lastCourseSignature = ""
    private val resolvingPreferred = mutableStateOf(preferredCourse != null)
    private var coursePolls = 0
    private val signMessage = mutableStateOf("")
    private val signSuccess = mutableStateOf(false)
    private val signVersion = mutableStateOf(0)
    private var signProbeVersion = 0
    private var scanGeneration = 0
    private fun submitScan(code: String) {
        if (!code.matches(Regex("[0-9]{4}")) || mode != "sign" || signSuccess.value) return
        val attempt = ++scanGeneration
        val keys = listOf("重置") + code.map { it.toString() } + "确认"
        fun next(index: Int) {
            if (mode != "sign" || attempt != scanGeneration) return
            val key = JSONObject.quote(keys[index])
            web.evaluateJavascript("""(() => {
                if(location.origin!=='https://attendance.nbu.edu.cn'||!location.pathname.startsWith('/h5/student/sign'))return false;
                const e=[...document.querySelectorAll('span,button,div')].find(e=>e.childElementCount===0&&e.textContent.trim()===$key&&e.getClientRects().length);
                if(!e)return false;e.click();return true;
            })()""") { result ->
                if (result != "true") { signMessage.value = "学校数字键未就绪，已停止本次提交。"; signVersion.value++ }
                else if (index < keys.lastIndex) handler.postDelayed({ next(index+1) },30)
                else signMessage.value = "正在验证识别结果…"
            }
        }
        next(0)
    }
    fun signResult(response: JSONObject) {
        signSuccess.value = response.optBoolean("success") || response.optString("msg") == "success"
        signVersion.value++
        signMessage.value = if (response.optBoolean("success") || response.optString("msg") == "success") "签到成功" else response.optString("msg", "签到未完成，请查看学校提示。")
    }
    private fun signKey(value: String) {
        signMessage.value = if (value == "确认") "正在提交…" else ""
        val key = JSONObject.quote(value)
        web.evaluateJavascript( """(() => {
          if(location.origin!=='https://attendance.nbu.edu.cn'||!location.pathname.startsWith('/h5/student/sign'))return false;
          const e=[...document.querySelectorAll('span,button,div')].find(e=>e.childElementCount===0&&e.textContent.trim()===$key&&e.getClientRects().length);
          if(e){e.click();return true;}return false;
        })()""") { if (it != "true") signMessage.value = "学校按键尚未就绪，请稍候或查看原页面。" }
    }
    private fun inspectSign() {
        if (mode != "sign") {
            hide(); mode = "sign"; web.alpha = 0f; signMessage.value = ""; signSuccess.value = false; signVersion.value = 0; signProbeVersion = 0
            web.evaluateJavascript("delete window.__nbuSignResponse", null)
            signKey("重置")
            panel = ComposeView(web.context).apply {
                setBackgroundColor(attendanceBackground(web.context))
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { SignCodeScreen(signMessage.value, onKey = ::signKey, onOriginal = { useOriginal = true; reveal() }, success = signSuccess.value, resultVersion = signVersion.value, onScan = ::submitScan, onStopScan = { scanGeneration++ }) }
            }
            container.addView(panel, FrameLayout.LayoutParams(-1,-1))
        }
        web.evaluateJavascript("""(() => {
            if(!window.__nbuSignProbe){window.__nbuSignProbe=true;const send=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send=function(){this.addEventListener('loadend',()=>{try{
                    if(new URL(this.responseURL).pathname==='/api/coursePlan/signByCourseCode')window.__nbuSignResponse={version:(window.__nbuSignResponse?.version||0)+1,result:JSON.parse(this.responseText)};
                }catch(_){}},{once:true});return send.apply(this,arguments);};}
            return JSON.stringify(window.__nbuSignResponse||{});
        })()""") { raw ->
            if(mode=="sign") runCatching {
                val data=JSONObject(JSONTokener(raw).nextValue() as String)
                if(data.optInt("version")>signProbeVersion){signProbeVersion=data.getInt("version");signResult(data.getJSONObject("result"))}
            }
        }
        web.evaluateJavascript("""JSON.stringify((document.body?.innerText||'').split('\n').map(s=>s.trim()).filter(s=>s&&!/^[0-9\s]+${'$'}/.test(s)&&!['签到','请输入签到码','重置','确认'].includes(s)).join('\n'))""") { raw ->
            if (mode == "sign") runCatching { val text = JSONTokener(raw).nextValue() as String; if (text.isNotBlank()) signMessage.value = text }
        }
    }
    private val courses = mutableStateOf<List<CourseRow>>(emptyList())
    private fun schedule() = web.url?.substringBefore('?')?.trimEnd('/') == "https://attendance.nbu.edu.cn/h5/student/schedule"
    init { show("loading") }
    private val poll = object : Runnable {
        override fun run() {
            inspect()
            if (!useOriginal) handler.postDelayed(this, if (schedule()) 250 else 1000)
        }
    }
    fun pageStarted() {
        generation++; authenticationPageFinished = false; useOriginal = false; handler.removeCallbacks(poll)
        show("loading"); handler.post(poll)
    }
    fun pageFinished(url: String = web.url.orEmpty()) {
        if (url == web.url) authenticationPageFinished = true
        handler.removeCallbacks(poll)
        if (useOriginal) return
        handler.post(poll)
    }
    fun resume() { handler.removeCallbacks(poll); if (!useOriginal) handler.post(poll) }
    fun adoptPage() {
        resume()
        if (!trusted()) return
        val adoptedGeneration = generation
        web.evaluateJavascript("document.readyState==='complete'") { raw ->
            if (raw == "true" && generation == adoptedGeneration && trusted()) {
                authenticationPageFinished = true; resume()
            }
        }
    }
    fun pause() { scanGeneration++; handler.removeCallbacks(poll) }
    fun destroy() { handler.removeCallbacksAndMessages(null); hide() }
    private fun trusted() = AttendanceLandingPolicy.isAuthentication(web.url.orEmpty())
    private fun hide() { panel?.let { it.disposeComposition(); container.removeView(it) }; panel = null; mode = "" }
    private fun reveal() { hide(); web.alpha = 1f }
    private fun inspect() {
        if (useOriginal) return
        if (schedule()) { inspectCourses(); return }
        if (web.url?.substringBefore('?')?.trimEnd('/') == "https://attendance.nbu.edu.cn/h5/student/sign") { if (!silent) inspectSign(); return }
        if (!trusted()) { if (panel != null) reveal(); return }
        if (!authenticationPageFinished) return
        val inspectedGeneration = generation
        web.evaluateJavascript("""(() => {
          if(location.origin!=='https://uis.nbu.edu.cn') return 'web';
          if(document.readyState!=='complete')return 'loading';
          const visible=e=>e && getComputedStyle(e).display!=='none' && e.getClientRects().length>0;
          const trust=document.querySelector('.trust-device-modal');
          if(visible(trust)) return trust.querySelector('.trust-con-btn') && trust.querySelector('.trust-cancel-btn') ? 'trust' : 'web';
          if (window.needCaptcha===true || [...document.querySelectorAll('#captchaDiv')].some(visible)) return 'web';
          const pwd=document.querySelector('.pwd_login input[type=password],#pwdFromId input[type=password]');
          if(pwd && typeof window.startLogin==='function') return 'login';
          const code=document.querySelector('#dynamicCode');
          if(location.pathname.includes('/reAuthCheck/') && visible(code) && document.querySelector('#reAuthSubmitBtn,.submit_btn')) return 'code';
          return 'web';
        })()""") { raw ->
            if (generation != inspectedGeneration || !trusted() || useOriginal) return@evaluateJavascript
            val next = raw.trim('"')
            if (next == "loading") return@evaluateJavascript
            if (next == "web") { if (mode != "unsupported") show("unsupported"); return@evaluateJavascript }
            if (next in listOf("login", "code", "trust") && mode != next) show(next)
        }
    }

    private fun inspectCourses() {
        if (mode != "courses") {
            hide(); mode = "courses"; courses.value = emptyList(); web.alpha = 0f
            if (silent || AttendanceOptions.ui(web.context)) panel = ComposeView(web.context).apply {
                setBackgroundColor(attendanceBackground(web.context))
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { if (resolvingPreferred.value) AttendanceTheme { _, _ ->
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                            androidx.compose.material3.CircularProgressIndicator()
                            androidx.compose.material3.Text("正在进入签到页面…")
                        }
                    }
                } else CourseListScreen(courses.value, onRefresh = { web.reload() }, onOriginal = {
                    useOriginal = true; reveal()
                }, onCourse = { row ->
                    val title = JSONObject.quote(row.title)
                    web.evaluateJavascript("""(() => {
                      if(location.origin!=='https://attendance.nbu.edu.cn'||location.pathname.replace(/\/${'$'}/,'')!=='/h5/student/schedule')return false;
                      const e=document.querySelectorAll('.list-item')[${row.index}];
                      if(!e || e.querySelector('strong')?.innerText!==$title)return false;
                      e.click();return true;
                    })()""", null)
                }) }
            }
            if (panel != null) container.addView(panel, FrameLayout.LayoutParams(-1, -1)) else web.alpha = 1f
        }
        val currentGeneration = generation
        web.evaluateJavascript("""(() => {
          const elements=[...document.querySelectorAll('.list-item')];
          let component=null;
          for(let n=document.querySelector('.h5-page');n&&!component;n=n.parentElement){
            for(let v=n.__vue__;v;v=v.${'$'}parent){if(Array.isArray(v.list)&&v.list.length===elements.length){component=v;break;}}
          }
          return JSON.stringify(elements.map((e,index)=>{
            const item=component?.list[index];
            const verified=item&&item.courseName===e.querySelector('strong')?.innerText;
            return {index,title:e.querySelector('strong')?.innerText||'',
              details:[...e.querySelectorAll('span')].filter(s=>!s.closest('.el-tag')).map(s=>s.innerText).filter(Boolean).join(' · '),
              status:[...e.querySelectorAll('.el-tag')].map(s=>s.innerText).join(' · '),
              open:!!(verified&&item.coursePlanId&&item.attendanceState==0),
              signed:!!(verified&&item.attendanceState==1)};
          }));
        })()""") { raw ->
            if (!schedule() || mode != "courses" || currentGeneration != generation) return@evaluateJavascript
            runCatching {
                val array = org.json.JSONArray(JSONTokener(raw).nextValue() as String)
                courses.value = (0 until array.length()).map { i -> array.getJSONObject(i).let {
                    CourseRow(it.getInt("index"), it.optString("title"), it.optString("details"), it.optString("status"), it.optBoolean("open"), it.optBoolean("signed"))
                } }
                onCourses(courses.value)
                val signature = courses.value.toString()
                val open = courses.value.filter { it.open }
                coursePolls++
                val target = if (preferredCourse != null) open.firstOrNull { it.title == preferredCourse } else open.singleOrNull()
                if (target == null && coursePolls >= 6) resolvingPreferred.value = false
                if (!silent && AttendanceOptions.auto(web.context) && !autoCourseEntered && target != null) {
                    autoCourseEntered = true
                    val row = target
                    val title = JSONObject.quote(row.title)
                    web.evaluateJavascript("""(() => {
                        if(location.origin!=='https://attendance.nbu.edu.cn'||!location.pathname.startsWith('/h5/student/schedule'))return;
                        const e=document.querySelectorAll('.list-item')[${row.index}];
                        if(e?.querySelector('strong')?.innerText===$title)e.click();
                    })()""", null)
                }
                lastCourseSignature = signature
            }
        }
    }
    private fun run(script: String) {
        if (!trusted()) { hide(); return }
        web.evaluateJavascript("(() => { if(location.origin!=='https://uis.nbu.edu.cn')return; $script })()", null)
    }
    private fun show(next: String) {
        web.alpha = 0f
        hide(); mode = next
        val allowAutoLogin = next == "login" && !autoAttempted
        if (allowAutoLogin) autoAttempted = true
        val view = ComposeView(web.context).apply {
                setBackgroundColor(attendanceBackground(web.context))
            // Never save authentication input into the activity's saved state.
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        panel = view
        view.setContent {
            AuthenticationScreen(
                mode = next,
                autoLogin = allowAutoLogin,
                onSubmit = { account, secret ->
                    if (panel === view && mode == next && trusted()) submit(next, account, secret)
                },
                onRequestCode = { run("const b=document.querySelector('#getDynamicCode'); if(b)b.click();") },
                onTrust = { trust ->
                    if (panel === view && mode == "trust") {
                        val selector = if (trust) ".trust-con-btn" else ".trust-cancel-btn"
                        run("""const m=document.querySelector('.trust-device-modal');
                            if(m && getComputedStyle(m).display!=='none' && m.getClientRects().length>0){
                                const b=m.querySelector('$selector');if(b)b.click();
                            }""")
                        handler.post { inspect() }
                    }
                },
                onOriginal = { useOriginal = true; reveal() }
            )
        }
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun submit(next: String, account: String, secret: String) {
        val value = JSONObject.quote(secret)
        if (next == "login") {
            val username = JSONObject.quote(account)
            run("""const pwd=document.querySelector('.pwd_login input[type=password],#pwdFromId input[type=password]');
                if(!pwd)return;const form=pwd.closest('form');const u=form.querySelector('input[name=username]');
                u.value=$username;pwd.removeAttribute('readonly');pwd.value=$value;
                const remember=form.querySelector('input[name=rememberMe]');
                if(remember){remember.checked=true;remember.dispatchEvent(new Event('change',{bubbles:true}));}
                [u,pwd].forEach(e=>{e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));});
                const b=form.querySelector('#login_submit');if(b)b.click();""")
        } else if (next == "code") {
            run("""const e=document.querySelector('#dynamicCode');if(!e)return;e.value=$value;
                e.dispatchEvent(new Event('input',{bubbles:true}));
                const b=document.querySelector('#reAuthSubmitBtn,.submit_btn');if(b)b.click();""")
        }
    }
}
