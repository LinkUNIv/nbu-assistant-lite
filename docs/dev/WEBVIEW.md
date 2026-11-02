# WebView 内嵌校园页面的参考实现分析

> **状态更新（2026-11-02）**：本文分析的实现已由协作者 `Huanyi-HuTao`
> 合入本仓库（提交 `d83e7db`，0.35.49 Preview 起）。所以文中「外部实现」的措辞
> 现在指的是**同一份代码的历史来源**，不再指它是别人的东西 ——
> 下文的取舍判断针对的是这套设计本身，作为后续维护的参考。
>
> 实现边界与当前行为以 [`ATTENDANCE_BROWSER.md`](ATTENDANCE_BROWSER.md) 为准，
> 本文只保留「为什么这样设计、哪里值得学、哪里不该学」的判断。
>
> 原始分析对象：一份第三方「宁大助手 Lite」构建（`0.35.23-Debug`，包名
> `app.nbult.lite.debug`）。记录目的是**技术备忘**：接入校内页面之前先看清
> 别人怎么做的、哪里做对了、哪里不该学。

---

## 一、被分析的实现概况

| 项 | 值 |
|---|---|
| 容器 | `AttendanceBrowserActivity`（Kotlin + Compose，单 Activity） |
| 目标页 | `https://attendance.nbu.edu.cn/h5/student/schedule/`（考勤系统的课表页） |
| 页面渲染 | 系统 `WebView` + 原生 `LinearLayout` 容器，Compose 只负责工具栏/面板 |
| 登录方式 | 原生 Compose 面板收集账号密码，`evaluateJavascript` 注入学校原表单 |
| 凭据存储 | 可选，`AndroidKeyStore` + `KeyGenParameterSpec` + `AES/GCM/NoPadding` |
| JS 桥 | **无**（没有 `addJavascriptInterface`） |
| 诊断日志 | `document-start` 注入采集脚本 → `console.log` → `WebChromeClient` 落盘 JSONL |

---

## 二、WebView 配置（逐项实测）

以下参数取自 dex 字节码的实参，不是推测：

| 设置 | 值 | 评价 |
|---|---|---|
| `javaScriptEnabled` | `true` | 必需 |
| `domStorageEnabled` | `true` | 必需，否则单页应用存不住状态 |
| `allowFileAccess` | `false` | **正确** |
| `allowContentAccess` | `false` | **正确** |
| `mixedContentMode` | `1`（`MIXED_CONTENT_NEVER_ALLOW`） | **正确**，不放行混合内容 |
| `supportMultipleWindows` | `false` | 合理，避免弹窗逃出容器 |
| `userAgentString` | 见下节 | 有问题 |
| `CookieManager.setAcceptCookie` | `true` | 必需 |
| `CookieManager.setAcceptThirdPartyCookies(web, true)` | `true` | 有争议，见下 |
| `addJavascriptInterface` | **未调用** | **这是最值得学的一点** |

### 没有 JS 桥意味着什么

没有任何原生对象暴露给页面 JS，因此页面即使被第三方篡改，也拿不到应用侧的能力
（读文件、读课表、发 Intent）。代价是所有双向通信只能走
`evaluateJavascript(js, callback)` 单向注入 + 回调取值，写起来啰嗦，但攻击面小得多。

---

## 三、User-Agent：伪装成官方客户端

实际硬编码值：

```
Mozilla/5.0 (Linux; Android 15; PKR110 Build/AP3A.240617.008; wv)
AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0
Chrome/155.0.8059.43 Mobile Safari/537.36
cpdaily/9.9.10 wisedu/9.9.10
```

末尾的 `cpdaily/9.9.10 wisedu/9.9.10` 是**今日校园（风华宁大）客户端的标识**。
也就是说这个实现让服务端认为请求来自学校官方 App，而不是一个第三方应用。

这是整份实现里**最不该照搬**的一处：

- 学校侧的访问控制、限流、风控如果按 UA 区分，这样做等于绕过；
- 一旦学校升级校验（加设备指纹、加签名字段），这段写死的 UA 立刻失效；
- 而且它把 `Android 15` / `PKR110` / `Chrome 155` / `cpdaily 9.9.10` 全部写死，
  机型、系统版本、官方客户端版本任一变化，伪装就开始露馅 —— 本身是个持续维护负担。

> 如果真要内置一个页面，正确的做法是**用自己的 UA**，让服务端如实知道调用方是谁。
> 服务端不接受，说明它不希望你这么用，那就应该退回外链跳转。

---

## 四、document-start 脚本注入

用 androidx.webkit 的能力在文档开始前注入，且**限定来源**：

```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
    WebViewCompat.addDocumentStartJavaScript(
        web, OBSERVER_JS,
        setOf("https://*.nbu.edu.cn", "https://nbu.edu.cn")   // 只对学校域注入
    )
}
```

`DOCUMENT_START_SCRIPT` 需要做 feature gate，老版本 WebView 不支持 —— 这点处理对了。
限定 `allowedOriginRules` 也对了，不会污染外链页面。

### 注入的脚本在采集什么

```js
const clean = v => { const u = new URL(v, location.href); return u.origin + u.pathname; };
```

关键就在这个 `clean()`：**只保留 origin + pathname，丢弃 query 和 hash**。
所以 token、ticket、学号这类放在 URL 参数里的东西不会被记录。

采集范围：

| 事件 | 记录内容 |
|---|---|
| `click` | 仅 `event.target.tagName`，**不记输入框的值** |
| `submit` | 仅当前 URL |
| `fetch` / `xhr` | url / method / status / duration，**不记 header、body** |
| `error` / `unhandledrejection` | 文件名或 URL |
| `pushState` / `replaceState` / `hashchange` / `popstate` | 仅 URL |

源码里连自述都写了：

```
coverage=navigation,requests,http-errors,fetch-xhr,clicks,forms,history;
excludes=bodies,headers,inputs,cookies,secrets
```

**这部分是整份实现里做得最规范的地方**，脱敏是认真的，不是嘴上说说。

---

## 五、原生登录面板与表单填充

流程：

1. 原生 Compose 面板覆盖在 WebView 之上（`FrameLayout` 叠放，不是对话框）；
2. 先 `evaluateJavascript` 探测页面处于什么状态；
3. 按状态分三支，**有两支主动放弃自动化**：

| 探测条件 | 分支 | 行为 |
|---|---|---|
| `window.needCaptcha === true` 或 `#captchaDiv` 可见 | `web` | 收起原生面板，交还原网页 |
| `.trust-con-btn` 与 `.trust-cancel-btn` 同时存在 | `trust` | 显示设备信任选项，用户确认后点原页面按钮 |
| `location.pathname` 含 `/reAuthCheck/` 且 `#reAuthSubmitBtn` 可见 | `code` | 只留一个验证码输入框 |
| 其余 | 账号密码 | 原生面板输入 → 注入 → 提交 |

4. 填充时不是简单赋值，而是补齐框架需要的事件：

```js
const pwd  = form.querySelector('input[type=password]');
const user = form.querySelector('input[name=username]');
[user, pwd].forEach(e => {
  e.dispatchEvent(new Event('input',  { bubbles: true }));
  e.dispatchEvent(new Event('change', { bubbles: true }));
});
```

这一步是必需的 —— 很多前端框架只监听 `input`/`change`，直接改 `value` 不会被识别。
「7 天免登录」同理：`remember.checked = true` 之后还要补一个 `change` 事件。

5. 用户输入经过 `JSONObject.quote()` 转义后才拼进 JS 字符串。

**这个细节值得记：** 账号密码是用户输入，直接字符串拼接会形成 JS 注入。
用 `JSONObject.quote` 做转义是对的，比手写 `replace("'", "\\'")` 稳妥。

6. 提交后用轮询检测登录是否成功（`poll`），失败或超时就 `useOriginal` 回退到原网页。

---

## 六、Cookie 与第三方 Cookie

```kotlin
CookieManager.getInstance().apply {
    setAcceptCookie(true)
    setAcceptThirdPartyCookies(web, true)
}
```

开启第三方 Cookie 是为了让统一身份认证（`uis.nbu.edu.cn`）写下的会话在
业务域（`attendance.nbu.edu.cn`）可用 —— SSO 跳转场景下确实需要。

但这也意味着任何被嵌入页面的第三方 iframe 都能读写会话。
**更好的做法是按域判断**：只在 SSO 跳转的那一跳临时放开，登录完成后收回。
这份实现没有做这个区分。

---

## 七、落地页纠偏

登录成功后学校会把人丢到门户或考勤首页，不一定回课表页。
实现里用一个独立的 `AttendanceLandingPolicy` 负责判断：

- `isAuthentication(url)` — 当前是否还在认证流程中；
- `shouldReturn(url)` — 是否该纠回课表页；
- 配合 `attendance_auth_seen` / `attendance_return_applied` 两个标志**只纠一次**，避免死循环。

登录后跳转纠偏一定要有次数上限，否则「纠回 → 又被重定向 → 再纠回」会卡死。
这里的「只做一次」思路可以借鉴。

---

## 八、凭据存储（可选功能）

| 项 | 实现 |
|---|---|
| KeyStore | `AndroidKeyStore` |
| 密钥生成 | `KeyGenParameterSpec.Builder`（`setBlockModes` 可见） |
| 变换 | `AES/GCM/NoPadding` |
| 别名 | `school_account_aes_v1` |
| Prefs | `school_account` |
| 界面 | 只提供「更改 / 清除」，**不提供查看密码** |

密钥由系统 Keystore 生成、不可导出，这是正确的做法；
用 GCM 而非 CBC 也正确（GCM 自带完整性校验）。

但**存密码本身仍有争议**：Keystore 保护的是「设备被拿走且未解锁」的场景，
root 后的设备或注入攻击依然能拿到明文。所以「能不能存」不是技术问题，
而是产品要不要承担这个责任的问题。

---

## 九、诊断日志

| 项 | 实现 |
|---|---|
| 位置 | `filesDir/attendance-log/` |
| 文件 | `current.jsonl` + `previous.jsonl`，滚动两份各约 5 MB |
| 写盘 | 单线程 `Executor` 串行写，避免并发交错 |
| 字段 | `time` / `event` / `session` / `sequence` / `elapsedMs` / `url` / `detail` |
| 导出 | 手动触发，导出后提示「日志已导出」 |

用 JSONL 而不是纯文本是对的：结构化、可追加、不怕中途损坏。
日志里带 `session` 和 `sequence` 也便于还原一次完整的登录时序。

---

## 十、可以借鉴的

1. **不开 JS 桥**。所有交互走 `evaluateJavascript`，攻击面最小。
2. **`allowFileAccess` / `allowContentAccess` 一律关**，`mixedContentMode` 用 `NEVER_ALLOW`。
3. **注入脚本限定 origin**，且 `DOCUMENT_START_SCRIPT` 做 feature gate。
4. **日志脱敏用「只留 origin + pathname」** 这一条规则，比逐字段判断更不容易漏。
5. **用户输入拼 JS 前必须转义**（`JSONObject.quote`）。
6. **自动化遇到验证码就主动放弃**，交回原网页 —— 这是唯一正确的处理方式。
7. **登录后跳转纠偏要有次数上限**，用状态标志保证只纠一次。
8. **日志用 JSONL + 单线程串行写 + 滚动两份**。

## 十一、不该借鉴的

1. **伪装官方客户端 UA**。见第三节。
2. **无条件开启第三方 Cookie**。应该按域、按时机收放。
3. **把原生面板叠在 WebView 上做「无感登录」**。用户看不到浏览器地址栏，
   等于把所有信任都押在这个 App 上 —— 一旦 App 本身出问题，用户无从察觉。
4. **保存账号密码**。技术上能做，但这是把学校的账号安全接管过来，
   出了问题责任在自己这边。
5. **写死机型/系统/浏览器版本号**。任何一处变化都会导致识别失败。

---

## 十二、结论

这份实现的技术水平不低 —— 配置很克制、脱敏很认真、异常分支也有。

**当初的判断**：它的产品定位是「把学校登录流程搬进自己 App」，与本项目当时的
三条约束（不登录、不采集、不调私有接口）正面冲突，因此只建议外链跳转。

**后来的决定**：产品方向变了 —— 允许把考勤页内嵌进来，理由是学生上课点名的便利性
压过了原来的洁癖。0.35.49 起这套实现成了本项目的一部分。

既然已经越过这条线，**真正要守的边界换成了下面这几条**：

- **不替代学校的身份校验**：验证码、设备信任、二次验证一律交回原网页或让用户处理，
  不做任何形式的绕过；
- **凭据默认不存**：优先交给系统密码管理器（Android autofill），应用侧只做可选项，
  且不提供查看入口；
- **页面数据只进不出**：拿到的课程与签到状态只在本机使用，不上传、不转述给第三方；
- **随时可退回**：学校改版或结构变化时，退回系统浏览器，课表导入这条主路必须始终可用。

已经踩过、必须继续守住的：不伪装 UA 以外的身份、不无条件开第三方 Cookie、
不在日志里留凭据与查询参数、不把「有登录」当成「功能一定更好」的理由。

---

## 附：分析方法（便于复核）

```bash
# 1. 定位含目标类的 dex
for f in classes*.dex; do grep -qa "AttendanceBrowserActivity" "$f" && echo "$f"; done

# 2. 反编译为 Dalvik 字节码
$ANDROID_HOME/build-tools/36.0.0/dexdump -d classes10.dex > dump.txt

# 3. 在字节码里按类切成块，读 invoke-* 的实参
#    WebSettings 的每个 set* 都能看到传入的 const 值，比读 Java 更直接

# 4. 长字符串（内嵌 JS）dexdump 会转义换行，
#    直接从 dex 原始字节按 \x00 边界提取更完整
```

要点：Debug 包未混淆，类名和方法名可读；`dexdump -d` 的字节码里
`invoke-virtual {v3, v4}, Landroid/webkit/WebSettings;.setUserAgentString:(...)`
直接给出了调用顺序和寄存器，反推参数值只需往上找 `const`。
