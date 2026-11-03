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
import app.nbult.lite.util.AppLog

/** Native controls forward input to the trusted university form; no credential logging or JavaScript bridge. */
internal class NativeAuthentication(private val web: WebView, private val container: FrameLayout, private val silent: Boolean = false, private val preferredCourse: String? = null, private val onCourses: (List<CourseRow>) -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private var panel: ComposeView? = null
    private var mode = ""
    private var useOriginal = false
    private var autoAttempted = false
    private var generation = 0
    private var authenticationPageFinished = false
    /**
     * 自动进入过的那一门课。
     *
     * 原来是个布尔，一旦置真就再不复位 —— 相当于「一次会话只自动进一次」，
     * 当天第二门课开放签到时要用户自己点。记课程名而不是记「进过没」，
     * 抑制的就只是同一门课；这门课不再开放签到时自动解除（见 [inspectCourses]）。
     */
    private var autoEnteredCourse: String? = null
    private var lastCourseSignature = ""
    private val resolvingPreferred = mutableStateOf(preferredCourse != null)
    private var coursePolls = 0
    private val signMessage = mutableStateOf("")
    private val signSuccess = mutableStateOf(false)
    private val signVersion = mutableStateOf(0)
    private var signProbeVersion = 0
    private val signDigits = mutableStateOf("")
    private val signSubmitting = mutableStateOf(false)
    private val signKeys = java.util.ArrayDeque<String>()
    private var signKeyRunning = false
    private var signInputGeneration = 0
    private val signInputScript by lazy { web.context.assets.open("sign-input.js").bufferedReader().use { it.readText() } }
    private fun cancelSignInput() {
        signInputGeneration++; signKeys.clear(); signKeyRunning = false; signSubmitting.value = false
    }
    private fun submitScan(code: String) {
        if (!code.matches(Regex("[0-9]{4}")) || mode != "sign" || signSuccess.value || signSubmitting.value || signKeyRunning) return
        signKeys.clear()
        signKeys.add("重置")
        code.forEach { signKeys.add(it.toString()) }
        pumpSignKeys()
    }
    fun signResult(response: JSONObject) {
        signSuccess.value = response.optBoolean("success") || response.optString("msg") == "success"
        signVersion.value++
        signSubmitting.value = false
        signMessage.value = if (signSuccess.value) "签到成功" else response.optString("msg", "签到未完成，请查看学校提示。")
        // 只记结果不记签到码：导出日志后可能被贴到公开的地方，四位码等于一次有效签到。
        AppLog.log("Web", if (signSuccess.value) "sign_ok" else "sign_fail")
        if (signSuccess.value) {
            // Keep the completed course list visible instead of automatically entering again.
            // 抑制靠 autoEnteredCourse 记着的课程名，这里不需要额外动作：
            // 签完这门课就不再是「开放签到」，下一次 inspectCourses 会自动解除抑制。
            resolvingPreferred.value = false
            AttendanceSignal.active.value = false
            AttendanceSignal.courseName = null
            AttendanceSignal.state.value = "ready"
            cancelSignInput()
            web.loadUrl(AttendanceLandingPolicy.SCHEDULE)
        } else {
            cancelSignInput()
            signDigits.value = ""
            signKeys.add("重置"); pumpSignKeys()
        }
    }
    private fun signKey(value: String) {
        if (mode != "sign" || signSuccess.value || signSubmitting.value) return
        if (value == "重置") signKeys.clear()
        if (value == "确认" || value == "重置" || value.matches(Regex("[0-9]"))) {
            signKeys.add(value); pumpSignKeys()
        }
    }
    private fun pumpSignKeys() {
        if (signKeyRunning || signKeys.isEmpty() || mode != "sign" || signSubmitting.value) return
        val value = signKeys.removeFirst()
        if ((value == "确认" && signDigits.value.length != 4) || (value.length == 1 && signDigits.value.length >= 4)) { pumpSignKeys(); return }
        val expected = when(value) { "重置" -> ""; "确认" -> signDigits.value; else -> signDigits.value + value }
        val epoch = signInputGeneration
        signKeyRunning = true
        web.evaluateJavascript(signInputScript + "\nwindow.__nbuSignInput.click(" + JSONObject.quote(value) + ")") { clicked ->
            if (epoch != signInputGeneration || mode != "sign") return@evaluateJavascript
            if (clicked != "true") {
                signKeyRunning = false; signKeys.clear()
                signMessage.value = "学校按键尚未就绪，请稍候或查看原页面。"; signVersion.value++
                return@evaluateJavascript
            }
            if (value == "确认") {
                signSubmitting.value = true; signKeyRunning = false; signKeys.clear(); signMessage.value = "正在提交…"
                AppLog.log("Web", "sign_submit")
                handler.postDelayed({
                    if (epoch == signInputGeneration && mode == "sign" && signSubmitting.value) {
                        AppLog.log("Web", "sign_timeout")
                        signResult(JSONObject().put("success", false).put("msg", "提交超时，请检查网络后重新输入。"))
                    }
                }, 15000)
                return@evaluateJavascript
            }
            fun acknowledge(remaining: Int) {
                handler.postDelayed({
                    if (epoch != signInputGeneration || mode != "sign") return@postDelayed
                    web.evaluateJavascript("window.__nbuSignInput.read()") ack@ { raw ->
                        if (epoch != signInputGeneration || mode != "sign") return@ack
                        val actual = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                        if (actual == expected) {
                            signDigits.value = actual; signKeyRunning = false
                            if (actual.length == 4) { signKeys.clear(); signKeys.add("确认") }
                            pumpSignKeys()
                        } else if (remaining > 0) acknowledge(remaining - 1)
                        else {
                            signDigits.value = actual.orEmpty(); signKeyRunning = false; signKeys.clear()
                            signMessage.value = "网页未接收本次输入，请重新输入。"; signVersion.value++
                        }
                    }
                }, 30)
            }
            acknowledge(15)
        }
    }
    private fun inspectSign() {
        if (mode != "sign") {
            cancelSignInput(); signDigits.value = ""; hide(); mode = "sign"; web.alpha = 0f; signMessage.value = ""; signSuccess.value = false; signVersion.value = 0; signProbeVersion = 0
            web.evaluateJavascript("delete window.__nbuSignResponse", null)
            signKey("重置")
            panel = ComposeView(web.context).apply {
                setBackgroundColor(attendanceBackground(web.context))
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { SignCodeScreen(signMessage.value, digits = signDigits.value, submitting = signSubmitting.value, onKey = ::signKey, onOriginal = { useOriginal = true; reveal() }, success = signSuccess.value, resultVersion = signVersion.value, onScan = ::submitScan, onStopScan = { cancelSignInput() }) }
            }
            container.addView(panel, FrameLayout.LayoutParams(-1,-1))
        }
        web.evaluateJavascript("""(() => {
            if(!window.__nbuSignProbe){window.__nbuSignProbe=true;const send=XMLHttpRequest.prototype.send,open=XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open=function(method,url){this.__nbuSignUrl=String(url);return open.apply(this,arguments);};
                XMLHttpRequest.prototype.send=function(){this.addEventListener('loadend',()=>{try{
                    if(new URL(this.responseURL||this.__nbuSignUrl||location.href,location.href).pathname==='/api/coursePlan/signByCourseCode'){
                        let result;try{result=JSON.parse(this.responseText)}catch(_){result={success:false,msg:'提交失败，请检查网络后重新输入。'}}
                        if(this.status<200||this.status>=300)result={success:false,msg:'提交失败，请检查网络后重新输入。'};
                        window.__nbuSignResponse={version:(window.__nbuSignResponse?.version||0)+1,result};
                    }
                }catch(_){}},{once:true});return send.apply(this,arguments);};}
            return JSON.stringify(window.__nbuSignResponse||{});
        })()""") { raw ->
            if(mode=="sign") runCatching {
                val data=JSONObject(JSONTokener(raw).nextValue() as String)
                if(data.optInt("version")>signProbeVersion){signProbeVersion=data.getInt("version");signResult(data.getJSONObject("result"))}
            }
        }
        if (!signKeyRunning) {
            val epoch = signInputGeneration
            web.evaluateJavascript(signInputScript + "\nwindow.__nbuSignInput.read()") { raw ->
                if (mode == "sign" && epoch == signInputGeneration && !signKeyRunning) {
                    val actual = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    if (actual != null) signDigits.value = actual
                }
            }
        }
        web.evaluateJavascript("""JSON.stringify((document.body?.innerText||'').split('\n').map(s=>s.trim()).filter(s=>s&&!/^[0-9\s]+${'$'}/.test(s)&&!['签到','请输入签到码','重置','确认'].includes(s)).join('\n'))""") { raw ->
            if (mode == "sign") runCatching { val text = JSONTokener(raw).nextValue() as String; if (text.isNotBlank() && signMessage.value.isBlank()) signMessage.value = text }
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
        cancelSignInput(); generation++; authenticationPageFinished = false; useOriginal = false; handler.removeCallbacks(poll)
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
    fun pause() { cancelSignInput(); handler.removeCallbacks(poll) }
    fun destroy() { cancelSignInput(); handler.removeCallbacksAndMessages(null); hide() }
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
                    // 手动点过的课也算「进过了」：否则签到失败退回列表后，
                    // 自动跳转会把用户又弹进同一门课，跟用户刚做的动作打架。
                    autoEnteredCourse = row.title
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
                // 自动进入的那门课不再开放签到（已签到或已关闭）就解除抑制。
                // 判之前必须要求列表非空：签到成功后的整页重载会先清空再填，
                // 在空列表上判「它不在了」会立刻解除，然后被学校还没更新的状态再弹进去一次。
                if (courses.value.isNotEmpty() && autoEnteredCourse != null &&
                    courses.value.none { it.title == autoEnteredCourse && it.open }) {
                    autoEnteredCourse = null
                }
                val target = if (preferredCourse != null) open.firstOrNull { it.title == preferredCourse } else open.singleOrNull()
                if (target == null && coursePolls >= 6) resolvingPreferred.value = false
                if (!silent && AttendanceOptions.auto(web.context) && target != null && target.title != autoEnteredCourse) {
                    autoEnteredCourse = target.title
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
