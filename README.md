# 宁大助手 Lite

[![Build](https://github.com/LinkUNIv/nbu-assistant-lite/actions/workflows/build.yml/badge.svg)](https://github.com/LinkUNIv/nbu-assistant-lite/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/LinkUNIv/nbu-assistant-lite?include_prereleases&label=release)](https://github.com/LinkUNIv/nbu-assistant-lite/releases)
[![License](https://img.shields.io/github/license/LinkUNIv/nbu-assistant-lite)](LICENSE)

将课表、资讯、校历与常用校园入口整合为一个 Android 应用。课表支持离线使用、不需要登录任何校内系统；点名与账号功能需要使用学校统一身份认证，登录仅在本机浏览器内完成，凭据不经过本应用服务器且默认不保存。

> 本项目属于非官方第三方工具，与宁波大学不存在隶属、合作或授权关系。名称中的「宁大」仅用于说明适用学校。

---

## 下载与安装

当前预览版本：**0.37.0 Preview**（2026-11-03）

[下载 Preview](https://github.com/LinkUNIv/nbu-assistant-lite/releases/tag/v0.37.0-Preview) · [完整更新说明](docs/releases/v0.37.0-Preview.md)

| 文件 | 大小 | 运行要求 |
|---|---|---|
| `nbu-assistant-lite-0.37.0-Preview.apk` | 约 23 MiB（含离线 OCR 原生库，已裁剪架构） | Android 7.0（API 24）及以上 |

- 下载完成后使用文件管理器打开安装
- 同包名、同签名的升级可覆盖安装并保留数据；卸载后重新安装会清空本地数据
- 正式版、预览版与调试版包名不同，可以同时安装，但课表和设置互不共享
- 当前为预发布测试版本。Preview 包名为 `app.nbult.lite.preview`，请按实际通道下载，避免误以为能覆盖 Debug 或 Release。

历史版本与逐版说明见 [Releases](../../releases)。

---

<!--
## 界面预览

截图尚未收录，待界面视觉定稿后补充。收录规范见 docs/images/README.md。

<details>
<summary>查看截图</summary>

</details>
-->

## 主要功能

- **课表**：导入教务导出的 Excel 或 Word 文件，也可以手动录入。周视图按屏宽自适应，左侧节次栏固定显示节次与起止时间
- **停课与调课**：针对某一节课设置停课或调整时间，可仅对本周生效，也可自本周起每周生效，随时可以恢复原安排
- **上课提醒**：开课前提醒；上课期间可在通知栏保留剩余时间，Android 16 及以上版本会申请「实时动态」显示位
- **资讯**：将新闻网四个栏目与教务处通知公告合并为一条时间线，保留正文、配图与原文链接
- **校历**：内置两个学年的结构化作息与事件，另可查看官方校历原图
- **校园入口**：教务、选课、图书馆、校园卡、邮箱、WebVPN 等，全部交由系统浏览器打开

- **点名**：内置学校考勤浏览器，支持主题登录、课程列表、应用内数字键盘签到及前台静默预加载；长按点名可设置自动跳转等行为。
- **相机 OCR**：在取景框中定位蓝色背景上的黄色签到码区域，支持缩放、对焦和独立 OCR 测试。此功能仍为实验性质，泛白投影场景尚不能保证可靠识别。
- **账号**：可选用 Android Keystore 与 AES-GCM 在本机加密保存凭据，更改或清除会清理浏览器会话。

**数据处理原则**：课表离线保存在本机；普通校园入口交由系统浏览器打开。点名使用系统 WebView 保留 Cookie 和网页存储，并将输入交给学校原认证表单；应用会在学校页面注入交互适配和诊断脚本。相机图片只在内存中处理，不保存或上传。Preview/Release 仅记录基础本地交互日志，不启用 Debug 的接口正文、页面输入及 DOM 详细记录；Debug 日志可能包含签到码和课程个人信息，不应公开原始日志。不接入广告或统计 SDK。详见 [点名与诊断说明](docs/dev/ATTENDANCE_BROWSER.md)。

---

## 数据来源与版权

| 来源 | 用途 | 方式 |
|---|---|---|
| `news.nbu.edu.cn` | 要闻、动态、媒体、人物 | 公开栏目页，读取列表、正文与配图 |
| `jwc.nbu.edu.cn` | 教务处通知公告 | 同上 |
| `www.nbu.edu.cn/xl.htm` | 官方校历原图 | 仅解析图片地址，结构化日期来自本地 JSON |
| `attendance.nbu.edu.cn`、`uis.nbu.edu.cn` 及学校认证跳转域名 | 点名、统一认证 | 用户登录后的网页交互，非公开数据抓取 |
| 教务导出文件 | 课表 | 由使用者自行导出后手动导入，应用不自动获取 |

- 资讯与校历抓取针对公开页面；点名服务需要学校登录，遵循学校验证流程，不绕过验证码或访问控制
- 文章与图片的版权归宁波大学及原作者所有。应用仅作为阅读索引：正文缓存保存在本机数据库，
  不上传、不聚合、不二次分发，每条内容均保留原文链接与来源标注
- 校历依据教务处公开校历人工整理，尚未与原文核对的学年会在页面底部提示
- 分发或再发布本应用前，请自行复核上述内容的转载授权

---

## 项目结构

```
app/src/main/java/app/nbult/lite/
├─ account/     本机加密账号管理
├─ browser/     点名、认证适配、相机 OCR
├─ data/        数据库、网络抓取、数据仓库、提醒调度
├─ domain/      时间与周次计算、停课调课合成、提醒规划
├─ parser/      Excel、Word、RTF、HTML 表格与文本网格解析
├─ net/         网络状态监控
├─ ui/          Compose 界面，按模块划分（课表、资讯、校历、入口、侧栏、设置）
└─ util/        日志、崩溃捕获、外部链接、帧统计

app/src/main/assets/
├─ calendar/    校历数据
└─ portal/      校园入口配置
```

技术栈为 Kotlin、Jetpack Compose、Room、OkHttp、Jsoup 与 Coil，依赖注入采用手动方式。

```bash
./gradlew :app:testDebugUnitTest  # 日期边界回归测试
./gradlew :app:assembleRelease   # 正式包，需配置固定发布签名
./gradlew :app:assemblePreview   # 预览包
```

构建环境、版本规则、发版流程与代码约定见 [`docs/dev/DEVELOPING.md`](docs/dev/DEVELOPING.md)。

---

## 反馈

- **缺陷**：使用「问题反馈」模板提交，并附上工作日志
- **建议**：使用「功能建议」模板提交
- **安全问题**：通过 [Security Advisories](../../security/advisories/new) 私下报告，请勿提交公开 Issue

工作日志的导出路径为「我的 → 数据与诊断 → 工作日志」。日志记录版本、周次与模块状态，
不包含课程名称、教师姓名或任何账号信息。

---

## 已知限制

- 新增「点名」内置浏览器为实验功能，使用指定 UA；网页依赖原生今日校园接口时可能无法运行，尚未完成登录真机验收。日志覆盖与隐私说明见 [浏览器说明](docs/dev/ATTENDANCE_BROWSER.md)。
- 2026–2027 学年校历尚未与教务处原文逐条核对
- 资讯正文属于转载，分发前需自行评估授权
- 已提供下一节课与课程列表桌面小组件；后台刷新受系统省电策略限制，当前仅支持 Android
- 部分国产系统的后台限制可能导致提醒延迟，应用内无法完全规避

---

## 致谢

- **Noto Serif**：用于进度与倒计时数字，遵循 SIL Open Font License 1.1
- **Jetpack Compose、Room、OkHttp、Jsoup、Coil**：均遵循 Apache License 2.0

各依赖的完整许可信息可通过应用内「我的 → 关于 → 开源许可」查看。

---

## 许可证

源代码以 [MIT License](LICENSE) 发布。

该许可证仅覆盖本仓库的源代码。通过本应用访问的文章、图片、校历等内容的版权归原权利人所有，
不属于本许可证的授权范围。

---

## 免责声明

1. 本项目为个人开发的非官方工具，与宁波大学及其任何部门无关，未获其授权或认可
2. 点名浏览器由用户自行登录，登录态由 WebView 保存；应用不提取账号密码或会话凭证用于独立接口调用
3. 抓取内容均来自公开页面，仅作个人阅读便利；版权归原权利人，如有异议请联系删除
4. 使用本应用可能违反所在学校的信息系统使用条款，风险由使用者自行承担
5. 软件按「现状」提供，不附带任何明示或暗示的担保；因使用造成的任何后果，作者不承担责任
