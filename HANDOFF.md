# 接手须知（宁大助手 Lite）

**只需读本文件 + 粗读 `docs/dev/`，就能接着干。** 其他目录当黑盒，按需再翻。

- 仓库：`nbu-assistant-lite/`（本文件所在目录），Android 原生 Kotlin + Compose
- 包名：`app.nbult.lite`（debug `.debug` / preview `.preview`）
- 远端：https://github.com/LinkUNIv/nbu-assistant-lite （公开，二进制只走 Release）

---

## 1. 三十秒看懂这个项目

宁波大学课表 / 校历 / 资讯 / 签到的第三方客户端，**非官方**：不登录、不采集、不碰学校私有接口。

0.38.x 起支持多校（现为宁大 + 湘潭）。**一所学校 = `assets/schools/<id>/` 一个目录**，代码不认学校，只认规则。
详见 `docs/dev/SCHOOL_RULES.md`。

## 2. 当前状态（2026-11-12）

| 项 | 值 |
|---|---|
| 已发布 | **0.38.2**（3802），2026-11-12 |
| 工作区版本号 | 仍是 3802，**0.38.3 的改动已提交但未发版** |
| 未发版内容 | 文案按学校分流、湘潭冬夏作息、按季节自动应用作息 |
| 单测 | 84 项，全绿 |
| APK | 25.27 MB（PDF 库 +2.2MB） |
| 最新提交 | `aeb589f feat(school): 湘潭冬季作息 + 按季节自动选择` |

**待办第一件事**：确认 0.38.3 文案后发版（流程见第 5 节）。草稿当时已给用户，只差他改词。

## 3. 目录与代码地图

```
app/src/main/java/app/nbult/lite/
├── school/              # 多校：规则解析、清单、当前学校上下文、偏好
├── parser/              # 课表解析
│   ├── TimetableParser  #   入口：按扩展名分派
│   ├── FieldExtractor   #   取词（周次委托 WeekParser）
│   ├── WeekParser       #   周次→掩码，按段解析（0.38.2 重写）
│   └── pdf/             #   PDF：取词 / 归列 / 导入
├── data/
│   ├── db/              # Room，DB 版本 6（term/course 带 schoolId）
│   ├── repo/            # 仓储（课表、校历、周次展开缓存）
│   ├── remote/          # 资讯抓取
│   ├── pack/            # 资源包：下载、校验、守卫
│   └── news/            # 资讯源配置
├── net/                 # 白名单拦截器、HTTP 客户端
├── ui/                  # Compose 界面（AppRoot / nav / timetable / settings / news）
└── browser/             # 签到 WebView（宁大专属）

app/src/main/assets/schools/   # 各校规则
app/src/test/resources/        # 测试夹具（含脱敏的真实 PDF 坐标）
docs/dev/                      # 工程文档（版本、构建、踩坑、待办）
docs/parser/xtu-pdf.md         # 湘潭 PDF 取格规则，改 PDF 解析前必读
```

## 4. 十条不能违反的约定

1. **版本号 `主.次.修订`**：主位改动要用户点头；修 bug 只动修订位；`versionCode = 主*10000+次*100+修订`。
2. **不要主动升 1.0.0**，继续 0.x。
3. **出包前**把 `CHANGELOG` 条目与 `docs/releases/<tag>.md` 草稿交用户过目，改完再构建。
4. **写完 CHANGELOG 必须 `grep -c "^## v<版本> "` 校验等于 1**，脚本静默失败过。
5. **攒批发布**：一组修复发一版，别一修一发（每版 CI + 验收约 40 分钟）。
6. **提交用 `tools/push-via-api.py`**，不要 `git push`（本机不通）。它取 `git status ∪ origin/main..HEAD`，并展开未跟踪目录。
7. **任何"写完立刻杀进程"的地方不能用 `apply()`**，必须 `commit()`。
8. **单测不要碰 `org.json`**（Android 桩会抛异常，本地过 CI 挂）；判定逻辑抽成纯数据入口。
9. **新增规则字段一律可选**，缺省值等于旧行为。
10. **数据不外发**：新域名要进白名单（学校域名走 `rules.domains`），明文流量全禁。

## 5. 发版流程（照抄即可）

```bash
# 1) 升版本（app/build.gradle.kts：versionCode / versionName）
# 2) 写 CHANGELOG 条目 + docs/releases/v<tag>.md + README 版本行
# 3) 校验两份文本一致（更新说明的"更新内容"≡CHANGELOG 条目）
# 4) 干跑看文件清单
python tools/push-via-api.py --dry-run "release: x.y.z ..."
# 5) 真提交
python tools/push-via-api.py "release: x.y.z ..."
# 6) 打标签（Git Data API；标签受仓库规则保护，不能删也不能移）
python tools/mktag.py <commit-sha> <tag> "说明"
# 7) 等 CI（约 7 分钟）→ 验收 Release → 断点续传下载 APK → 核对 sha
# 8) 归档 dist/archive/v<tag>/（APK + 源码包 + manifest + RELEASE.md）
```

构建命令（本机工具链在 `C:\atmp`）：

```bash
export JAVA_HOME='C:\atmp\jdk-17.0.12+7'
export PATH="/c/atmp/jdk-17.0.12+7/bin:$PATH"
/c/atmp/gradle-8.11.1/bin/gradle.bat --no-daemon --console=plain :app:testPreviewUnitTest :app:assemblePreview
```

## 6. 踩过的坑（都真实发生过，别再踩）

| 坑 | 症状 | 正解 |
|---|---|---|
| `apply()` 后立刻 `exit(0)` | 切学校重启又回默认值 | 改 `commit()` 同步写 |
| 上传脚本只看 `git status` | 0.37.20 的包少了整个 `assets/schools/` | 用 `tools/push-via-api.py`（取并集） |
| 下载不写临时文件 | 半截包交给系统，报"解析软件包时出现问题 (33)" | 写 `.part` → 校验 → rename → 写 `.ok` |
| `FieldExtractor.weekMask` 取第一段 | 湘潭第 10 周只剩 2 门课 | `WeekParser` 按段解析（0.38.2） |
| PDF 的 y 方向 | 单元格内多行逆序拼接，正则不匹配 | 取词时 `y = -yDirFirst` |
| KDoc 里写 `assets/schools/*` | `/*` 触发嵌套注释，整文件吞掉 | 注释里避开 `/*` |
| `runCatching{}` 表达式体里 `return` | 语法错误 | 块体 + `return@runCatching` |
| CI 上 `org.json` 是桩 | 本地过、CI 挂 | 判定抽纯数据入口 |
| 标签受保护 | 删不掉也移不动，只能新开版本 | 改 tag 前先确认 |
| pdfbox 带 BC 后量子表 | 包体 29.21MB | `packaging.resources.excludes` 排除 pqc |

## 7. 现在的已知限制

1. **湘潭学期起止未采集**（`years: []`）→ 第几周靠手动设置或新闻识别。
2. **湘潭教务处栏目未真机验证**，可能取不到条目（`jwc.xtu.edu.cn`，GB2312）。
3. **APK 仍从 GitHub 下载**，网络不畅要手动下。
4. **宁大作息预设不自动套用**（有主校区/梅山两套，避免误伤）。

## 8. 接下来该做什么

优先顺序（细节见 `docs/dev/PENDING.md`，路线图在第 19 行起）：

1. **发 0.38.3**（只差文案确认）
2. **真机验湘潭链路**：切学校不重启、重新导入 PDF 得 17 门、第 5 节 14:00
3. **教务处栏目**：取不到就给它单独写选择器，或先隐藏
4. **学期起止**：找 2026-2027 校历图补 `years`，否则第几周永远要手设
5. 之后才轮到 PENDING 里的 OCR、资源包第四批、常驻通知

## 9. 需要用户拍板的事

- 湘潭的资讯栏目要不要保留"教务处"这一栏（若长期取不到）
- 1.0.0 何时算"成型"（一直按 0.x 迭代是既定策略）
- 视觉/材质类改动一律后置，等他明确要做

---

**上手第一步建议**：`git log --oneline -10` 看时间线 → 读 `docs/dev/SCHOOL_RULES.md` → 跑一次 `testPreviewUnitTest`（约 2 分钟）确认环境可用。
