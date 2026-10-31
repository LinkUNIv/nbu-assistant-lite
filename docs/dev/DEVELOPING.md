# 开发与发版

本文件面向维护者。使用者请看 [`../../README.md`](../../README.md)。

---

## 版本规则

版本号 `主.次.修订`：

| 位 | 含义 |
|---|---|
| 主 | 架构级或不兼容变更 |
| 次 | 新增功能模块 |
| 修订 | 修 bug、文案、样式、微调 |

`versionCode = 主 × 10000 + 次 × 100 + 修订`，每版必须递增，否则无法覆盖安装。

- 小改动**只递第三位**，不要动第二位
- **主位变更必须明确确认**，不自行决定
- 不主动升到 `1.0.0`：功能成型后仍用 `0.x` 迭代，除非明确要求

版本名带通道后缀：`0.30.9` / `0.30.9-Preview` / `0.30.9-Debug`。

---

## 三个通道

包名互不覆盖，可同时安装：

| 通道 | 包名 | 应用名 | 图标 | debuggable |
|---|---|---|---|---|
| 正式 | `app.nbult.lite` | 宁大助手 Lite | 蓝 | false |
| 预览 | `app.nbult.lite.preview` | 宁大助手 Preview | 粉红 | false |
| 调试 | `app.nbult.lite.debug` | 宁大助手 Debug | 灰 | true |

- 预览版与正式版同为 `debuggable=false`（走 AOT 编译），性能数据可直接对照
- 正式包名**永久固定**：改了已装用户数据就断代
- 调试入口用 `BuildConfig.BUILD_KIND`（`release` / `preview` / `debug`），不要用 `BuildConfig.DEBUG`

---

## 构建环境

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | Platform 36、Build-Tools 36.0.0 |
| Gradle | 8.11.1（wrapper 已固定） |
| AGP | 8.9.1 |
| Kotlin / Compose 编译器 | 1.9.24 / 1.5.14（不升 Kotlin 2.0） |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |

```bash
./gradlew :app:assemblePreview   # 预览包
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 正式包（暂无签名配置，暂不出包）
```

**路径要求**：`gradle.properties` 里有 `android.overridePathCheck=true`，用于在含非 ASCII 字符的路径下构建
（AGP 默认拒绝）。更稳的做法仍是把项目放在纯 ASCII 路径下。

**targetSdk 36 起系统强制边到边**：内容避让由 `Scaffold` 与 `NavigationBar` 消费 systemBars inset，
不要在根布局再叠一层 padding；系统栏图标明暗由 `MainActivity` 的 `enableEdgeToEdge()` 负责。

**签名**：`release` 构建类型目前没有 `signingConfig`，不会产出可分发 APK。一旦对外发布，
keystore 必须长期保持不变，否则已安装用户无法覆盖升级（Android 以签名区分应用身份）。

---

## 出包与归档

```bash
tools/archive-release.sh preview       # → dist/archive/v<版本>-Preview/
tools/archive-release.sh debug -f      # → dist/archive/v<版本>-Debug/（-f 覆盖）
```

归档内容为 APK + 源码快照 + SHA-256 + `RELEASE.md` 记录。

- **`dist/` 不入库**，二进制走 GitHub Release 分发
- 每次版本变更同步更新根目录 `CHANGELOG.md`，并用 `grep -c "^## v<版本> "` 校验条目唯一
- 改完文档或 JSON 一律用 grep 复核，不要只相信脚本返回值

---

## 行尾与远端同步

**仓库里一律 LF**，由 `.gitattributes` 的 `text=auto eol=lf` 固定。

起因是一次事故：本机是 Windows，编辑器写出 CRLF；而这台机器 `git push` 长期不通，
提交改走 GitHub API 直推 —— API 不走 git 的换行转换，于是 39 个文件以 CRLF 进了仓库。
后果有两个：本地 `core.autocrlf=true` 把每个文件都当成「改过」，`git status` 永远脏；
`tools/*.sh` 带 CRLF 在 Linux 或 CI 上会以 `bad interpreter` 直接失败。

所以：**通过 API 直推时，文件必须先转成 LF**（用 Python 写文件要带 `newline="\n"`）。

本地与远端对齐用 `tools/sync-remote.sh`：带重试地 fetch、硬重置、清掉远端已删除的标签。
**API 直推之后必须跑一次** —— 否则本地 HEAD 落后，下次改文件是拿旧版本打底，
提交里会把别人的改动一起回退掉。

---

## CI

`.github/workflows/build.yml`：

- `push` / `pull_request` / 手动触发 → 编译 preview 包并上传 `apk-preview` 产物
- 推送 `v*` 标签 → 额外生成 Release，附 APK 与 sha256
- preview 变体走 debug 签名，**CI 不需要任何 keystore**

两个已经踩过的坑，改这块前先看：

1. **不要用 `android-actions/setup-android`。** 它内部会执行 `sdkmanager "tools"`，
   而新版 SDK 仓库已移除 legacy `tools` 包，直接以 `Failed to find package 'tools'` 失败。
2. **`sdkmanager` 不在 runner 的 PATH 上。** 需按 `$ANDROID_SDK_ROOT` 用 `find` 定位可执行文件，
   装完组件后再把路径写回 `GITHUB_ENV` 供 Gradle 使用。

---

## 资源包

校历、入口配置、资讯抓取规则不只存在于 APK 里：`filesDir/pack/<版本>/` 可以放一份下载来的覆盖版，
读取时优先用它，没有就退回 `assets/`。这样改数据不必发版。

目前纳入资源包的三份文件：

| 文件 | 内容 | 读取处 |
|---|---|---|
| `calendar/nbu_calendar.json` | 学年、学期起止、作息模板 | `CalendarRepository` |
| `portal/entries.json` | 入口链接与分类 | `PortalCatalog` |
| `news/sources.json` | 资讯栏目与抓取规则 | `NewsSources` |

| 项 | 位置 |
|---|---|
| 发布源 | 仓库的 `packs` 分支（**不是 main**，否则每次发资源都会触发一次 APK 构建） |
| 客户端地址 | 写死在 `ResourcePackChecker.BASES`：GitHub raw → jsDelivr |
| 清单 | `packs/latest.json`；文件在 `packs/<version>/<path>` |
| 落地 | `filesDir/pack/<version>/`，`state.json` 记录当前生效版本 |
| 发布 | `tools/publish-pack.sh [版本号]` |

清单格式：

```json
{
  "version": 202610311135,
  "updatedAt": "2026-10-31T11:35:53+08:00",
  "notes": "这次改了什么，会显示在调试面板里",
  "minAppVersion": 3400,
  "files": [{ "path": "portal/entries.json", "sha256": "…" }]
}
```

四条不能改的规则：

- **版本号只升不降**。`yyyyMMddHHmm`，客户端拒绝比本地旧的包
- **`minAppVersion` 是硬门槛**。包里用了旧版读不懂的结构就抬高它，旧版整体跳过，不做部分应用
- **读取一律走 `ResourcePackStore.readText()`**。直接 `context.assets.open()` 会绕开资源包，
  远程更新对该文件就无效了
- **入口链接与资讯配置单独设卡**（`PackGuards`）。都不允许引入内置清单里没有的域名，
  非 https 一律拒绝。入口能带用户去站外，资讯配置能让应用去请求任意地址 —— 这两处必须卡
- **抓取规则只放「往哪找」，不放「怎么算」**。`news/sources.json` 里只有选择器、正则与阈值，
  挑正文根节点、去重、日期格式化这些判断留在 `NewsCrawler`。远程包能改的是参数，不是逻辑

失败处理：校验不过、解析不过、写入失败 → 记 `failedVersion`，同版本不再重试，退回内置资源。
网络不通不记版本，下次启动还能再试。

---

## 签名

**所有版本使用同一份固定证书，不许退回 `debug` 签名。**

背景：`debug` 签名的密钥是 AGP 在「首次构建」时按机器生成的。CI 每次都跑在全新 runner 上，
于是每次构建都是一把新钥匙 —— 实测 0.33.0 与 0.33.1 的证书 SHA-256 完全不同。
表现是装好一个版本后再装下一个，系统直接拒绝：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
界面只显示「安装失败(-7)」和一句「与已安装应用签名不同」。应用内更新器因此从未成功过。

密钥位置与生效路径：

| 环境 | 来源 |
|---|---|
| 本机 | `local.properties` 的 `nbult.storeFile` / `storePassword` / `keyAlias` / `keyPassword` |
| CI | Secrets `NBULT_KEYSTORE_BASE64` / `NBULT_KEYSTORE_PASSWORD`，workflow 里还原到 `$RUNNER_TEMP` |

密钥文件放在**仓库之外**（工作区根目录的 `keystore/nbult-preview.jks`，密码在同目录 `password.txt`），
`.gitignore` 已挡掉 `*.jks`。两边必须是同一把钥匙，否则本地装的包与线上包互相盖不上。

密钥缺失时 `preview` 与 `release` 会退回 `debug` 签名，并在构建日志里打出警告，
CI 则直接失败（`还原签名密钥` 那一步）—— 宁可不出包，也不要出一个盖不上任何既有安装的包。

`CI` 的「校验签名」步骤会把证书指纹打进日志，正常应显示
`CN=NBU Assistant Lite`；若出现 `CN=Android Debug`，说明密钥没生效。

**换密钥等于让所有既有安装无法更新。** 真要换，先确认接受「所有人卸载重装、数据清空」，
并在发布说明里写明。`versionCode` 只能递增，证书不能随便换重新签名同一个版本号。

---

## 变更记录

`CHANGELOG.md` 是唯一的变更记录来源，同时也是应用内「更新日志」页的正文：
构建时由 `copyChangelog` 任务复制到 `build/generated/changelogAssets/changelog/CHANGELOG.md`，
该目录通过 `sourceSets` 注册为 assets 源目录，页面读取后按 Markdown 子集解析。

这意味着 **写 CHANGELOG 就等于写应用内文案**，不需要在 app 里维护第二份。两条注意事项：

- 生成目录属于「源目录」而非任务产物，AGP 不会自动建立依赖。读取它的任务有两类：
  `merge*Assets` 与 lint（`generate*LintVitalReportModel`、`lintVitalAnalyze*`、`lintAnalyze*`）。
  `app/build.gradle.kts` 里已按任务名挂 `dependsOn`，**少挂一个构建会直接失败**，
  报错形如 `uses this output ... without declaring an explicit or implicit dependency`。
- CHANGELOG 会随包分发，体积直接计入 APK。当前全文约 145 KB，压缩后十几 KB；
  如果将来增长到数 MB，再考虑只打包最近若干版本。

解析器只支持本项目实际用到的语法：`## vX.Y.Z[-渠道] — 日期` 分版本、`### ` 作分组、
`- ` 列表、围栏代码块、`**加粗**` 与反引号代码。表格会在解析时拍平为逐行文字，
手机屏宽放不下真正的表格。给 CHANGELOG 加新语法之前，先确认解析器认识它。

---

## 视觉改动先出草图

小组件与资讯卡这类「看得见、但没法用日志验证」的改动，**先在对话里出一张 SVG 草图**，
把内边距、圆角、字号、颜色标在图上，确认后再写代码，实现时严格照图上的数值来。

原因是 0.31.3 与 0.32.x 的经验：小组件的前六轮改动都是「改代码 → 编译三分钟 → 装机 → 截图 → 再改」，
一轮二十多分钟，而参数本身画在图上讨论只需要一分钟。**改的是数值，不是代码**，
用编译验证数值的效率极低。

草图要标出的是可核对的具体值，不是「大概这样」：内边距多少 dp、字号多少 sp、
圆角多少 dp、颜色是哪个十六进制。图与实现不一致时，以图为准改实现。

---

## 发布说明的写法

`docs/releases/<tag>.md` 既是 Release 正文，也是应用内更新日志的内容。统一按下面的结构：

```
# 0.32.0 Preview

发布日期：2026-10-30

一句话总述。本版做了什么、有没有新增功能。

## 按主题分节

陈述改了什么、改成什么值。

## 已知问题

- 只列使用者会遇到的事
```

四条硬规则：

- **只陈述，不解释动机**。写「条目间距 12 → 2dp」，不写「因为同一条资讯的两行之间
  不该留出与两条资讯之间相当的距离」。理由对写代码的人有用，对读更新日志的人无用。
- **不出现实现细节**：函数名、文件路径、行号、根因分析、对照实验过程，一概不写。
  这些留在 commit message 与 `NOTES.md` 里。
- **不写开发过程**：「顺带清理了 14 处内部段落」「这一版不动界面行为，只改验收工具」
  这类是工作记录。使用者只关心他手上的东西变成了什么样。
- **散文为主**，列表只用于并列项（已知问题、逐条改动）。章节名用名词短语，
  不用「一、二、三」这类序号。

数值可以保留，它是可核对的事实；形容词尽量少用，「更顺滑」「更清爽」这类无法验证。

---

## 代码结构

```
app/src/main/java/app/nbult/lite/
├─ data/db/        Room 实体、DAO、数据库迁移
├─ data/remote/    资讯与校历抓取、服务器校时
├─ data/repo/      课表 / 资讯仓库
├─ data/notify/    上课提醒调度与常驻通知
├─ data/licenses/  开源许可清单（读 assets/licenses/index.json）
├─ data/changelog/ 变更记录解析（读 assets/changelog/CHANGELOG.md）
├─ domain/         时间与周次计算、停课调课合成、提醒规划
├─ parser/         xlsx / docx / rtf / HTML 表格 / 文本网格解析
├─ net/            网络状态监控（三态）
├─ ui/timetable/   固定节次栏 + 分页网格、导入、课程与停课调课编辑
├─ ui/news/        资讯列表与详情
├─ ui/calendar/    校历、官方原图查看器
├─ ui/portal/      校园入口（纯跳转）
├─ ui/nav/         右侧常驻侧栏与进度胶囊
├─ ui/settings/    设置、外观、日志导出、开源许可、更新日志
└─ util/           日志、崩溃捕获、外部链接、帧统计
app/src/main/assets/
├─ calendar/       校历数据（人工结构化的 JSON）
├─ changelog/      构建时由 CHANGELOG.md 复制而来，不手工编辑
├─ licenses/       许可全文与清单
├─ portal/         入口配置（改入口不必改代码）
└─ samples/        虚构样例数据（真实导出件不入库）
```

---

## 相关文档

- [`../../CHANGELOG.md`](../../CHANGELOG.md) — 逐版本变更
- [`NOTES.md`](NOTES.md) — 早期开发笔记（**含已被推翻的设计，请对照 README 的已知限制阅读**）
- [`PENDING.md`](PENDING.md) — 路线图与未排期事项
