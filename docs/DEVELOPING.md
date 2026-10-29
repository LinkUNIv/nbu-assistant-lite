# 开发与发版

本文件面向维护者。使用者请看 [`../README.md`](../README.md)。

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

## 代码结构

```
app/src/main/java/app/nbult/lite/
├─ data/db/        Room 实体、DAO、数据库迁移
├─ data/remote/    资讯与校历抓取、服务器校时
├─ data/repo/      课表 / 资讯仓库
├─ data/notify/    上课提醒调度与常驻通知
├─ domain/         时间与周次计算、停课调课合成、提醒规划
├─ parser/         xlsx / docx / rtf / HTML 表格 / 文本网格解析
├─ net/            网络状态监控（三态）
├─ ui/timetable/   固定节次栏 + 分页网格、导入、课程与停课调课编辑
├─ ui/news/        资讯列表与详情
├─ ui/calendar/    校历、官方原图查看器
├─ ui/portal/      校园入口（纯跳转）
├─ ui/nav/         右侧常驻侧栏与进度胶囊
├─ ui/settings/    设置、外观、日志导出、开源许可
└─ util/           日志、崩溃捕获、外部链接、帧统计
app/src/main/assets/
├─ calendar/       校历数据（人工结构化的 JSON）
└─ portal/         入口配置（改入口不必改代码）
```

---

## 相关文档

- [`../CHANGELOG.md`](../CHANGELOG.md) — 逐版本变更
- [`NOTES.md`](NOTES.md) — 早期开发笔记（**含已被推翻的设计，请对照 README 的已知限制阅读**）
- [`PENDING.md`](PENDING.md) — 路线图与未排期事项
