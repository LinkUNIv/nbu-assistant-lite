# 接手须知（宁大助手 Lite）

**只需读本文件 + 粗读 `docs/dev/`，就能接着干。** 其他目录当黑盒，按需再翻。
架构与分工（操作地图 / 运行时序 / 什么能热更）见 `docs/dev/ARCHITECTURE.md`。

- 仓库：`nbu-assistant-lite/`（本文件所在目录），Android 原生 Kotlin + Compose
- 包名：`app.nbult.lite`（debug `.debug` / preview `.preview`）
- 远端：https://github.com/LinkUNIv/nbu-assistant-lite （公开，二进制只走 Release）

---

## 1. 三十秒看懂这个项目

宁波大学课表 / 校历 / 资讯 / 签到的第三方客户端，**非官方**：不登录、不采集、不碰学校私有接口。

0.38.x 起支持多校（现为宁大 + 湘潭 + 长沙民政）。**一所学校 = 资源包里的一个目录 + 一份 rules.json**，
代码不认学校，只认规则。自 0.38.7 起**APK 只内置宁波大学**，其余学校全在 `pack-src/` 里随包下发：
包里加一条就多一所，删一条就少一所（正在用会回退宁大）。详见 `docs/dev/SCHOOL_RULES.md`。

## 2. 当前状态（2026-11-26）

| 项 | 值 |
|---|---|
| 已发布 | **0.39.5**（3905），2026-11-26 |
| 资源包 | `202611251600`（minApp 3823） |
| 学校 | 四所：宁波大学 / 长沙民政 / 湘潭大学 / 中南民族大学（民大已全量适配，18 个时段） |
| 单测 | **180 项**（179 通过 + 1 项 `@Ignore` 探针），全绿 |
| 最新提交 | `c4c1510`（工作区干净，与 origin/main 对齐） |

**这一程做完的事**

- 中南民大 PDF 适配收尾：决定性改动是 `PdfTextExtractor` **按词取词**（按行合并会把跨列整行拖进一列）
- 解析参数外置 10 项（`importer.pdf.tuning`）：阈值、房间/地点正则、节次锚点、课号、取词词距
- 包内自检（`selftest.json` + 脱敏夹具）：民大 18 条、湘大 26 条已覆盖，调试面板可见
- 诊断快照导出（`DiagSnapshotStore`）：最近一次导入的片段与结果落成 JSON
- 校历换官方数据（8/31 开学，两学期各 20 周）；学校列表常驻 + 拼音排序（宁大 pinned）

**待办第一件事**：`PENDING.md` 第一节 0.40.0——资源包只向上兼容（门槛默认抬到当版 + rules schema 语义统一）。

**两条已知坑**

- 本机 Gradle 仍不稳，**统一走 CI 编译**；推 main 绿了再打标签
- 发版三步校验：`docs/releases/<tag>.md` 必须存在、说明里**不得**含"版本号提升至…"、标签与 `versionName` 一致

**Excel 的结论（省下的一次重构）**

宁大 Excel 走 `GridParser`（按「星期」表头定位列、A 列节次、单元格内 `1-15周,星期1,3-4节,地点` 兜底），
用教务系统导出样本实测**全部正确**，因此不新写解析器；真正写死的只剩 `FieldExtractor` 的星期/节次词表，
四所现有学校都用不到，已降级为低优先。

## 3. 目录与代码地图

```
app/src/main/java/app/nbult/lite/
├── school/              # 多校：规则解析、清单、当前学校上下文、作息判定、偏好
│   ├── SchoolContext    #   规则 / 作息 / PDF / 本地校历，切学校时整体替换（不重启）
│   ├── PeriodPresetPolicy # 换校换季该不该写作息的纯判定（0.38.3 新增，有单测）
│   └── SchoolPreference #   学校 id、已应用作息、宁大所选模板（全部 commit）
├── parser/              # 课表解析
│   ├── TimetableParser  #   入口：按扩展名分派
│   ├── FieldExtractor   #   取词（周次委托 WeekParser）
│   ├── WeekParser       #   周次→掩码，按段解析（0.38.2 重写）
│   └── pdf/             #   PDF：取词 / 归列 / 导入
├── data/
│   ├── db/              # Room，DB 版本 6（term/course 带 schoolId；period_time 没有）
│   ├── repo/            # 仓储（课表、校历、周次展开缓存）
│   ├── remote/          # 资讯抓取
│   ├── pack/            # 资源包：下载、校验、守卫
│   └── news/            # 资讯源配置
├── net/                 # 白名单拦截器、HTTP 客户端
├── ui/                  # Compose 界面（AppRoot / nav / timetable / settings / news）
└── browser/             # 签到 WebView（宁大专属）
```

`AppContainer.alignPeriods()` 是唯一的作息写入口，切学校、启动、恢复出厂三处都走它。

## 4. 十一条不能违反的约定

1. **版本号 `主.次.修订`**：主位改动要用户点头；修 bug 只动修订位；`versionCode = 主*10000+次*100+修订`。
2. **不要主动升 1.0.0**，继续 0.x。
3. **出包前**把 `CHANGELOG` 条目与 `docs/releases/<tag>.md` 草稿交用户过目，改完再构建。
4. **写完 CHANGELOG 必须 `grep -c "^## v<版本> "` 校验等于 1**，脚本静默失败过。
5. **攒批发布**：一组修复发一版，别一修一发（每版 CI + 验收约 40 分钟）。
6. **提交用 `tools/push-via-api.py`**，不要 `git push`（本机不通）。它取 `git status ∪ origin/main..HEAD`，并展开未跟踪目录。
7. **任何"写完立刻杀进程"的地方不能用 `apply()`**，必须 `commit()`。
8. **单测不要碰 `org.json`**（Android 桩会抛异常，本地过 CI 挂）；判定逻辑抽成纯数据入口。
9. **新增规则字段一律可选**，缺省值等于旧行为 —— 但这条**只管"加参数"**；
   改变结构或语义必须抬门槛（`schema` +1，发布脚本会自动抬 `minAppVersion`），旧版整包跳过。
10. **数据不外发**：新域名要进白名单（学校域名走 `rules.domains`），明文流量全禁。
11. **内容改动只发资源包**：新增学校 / 新增入口 / 校历作息 / 栏目调整，一律走资源包，
    APK 不动。前提是读取走 `ResourcePackStore`、发布脚本覆盖 `schools/`、守卫按后缀匹配
    （三条缺一条就得发版）。契约见 `docs/dev/ARCHITECTURE.md` 第 5.1 节。
12. **写共享表要记来源**：`period_time` 全校一张（无 schoolId），凡是自动写它，
    必须把「谁写的」记进 `SchoolPreference.appliedPeriodPreset`（`<schoolId>/<preset>`），
    否则切回来就还原不了 —— 0.38.2 的宁大作息回归就是这么来的。

## 5. 发版流程（照抄即可）

```bash
# 1) 升版本（app/build.gradle.kts：versionCode / versionName）
# 2) 写 CHANGELOG 条目 + docs/releases/v<tag>.md + README 版本行
# 3) 校验两份文本一致（更新说明的"更新内容"≡CHANGELOG 条目）
python tools/check-release-notes.py v<tag>
# 4) 干跑看文件清单
python tools/push-via-api.py --dry-run "release: x.y.z ..."
# 5) 真提交
python tools/push-via-api.py "release: x.y.z ..."
# 6) 打标签（Git Data API；标签受仓库规则保护，不能删也不能移）
python tools/mktag.py <commit-sha> <tag> "说明"
# 7) 等 CI（约 7 分钟）→ 验收 Release → 断点续传下载 APK → 核对 sha
# 8) 归档 dist/archive/v<tag>/（APK + 源码包 + manifest + RELEASE.md）
```

`tools/check-release-notes.py` 是本机复刻 CI 那道闸（条目唯一 + 更新内容归一化后一致），
推之前先跑，别等 7 分钟后 CI 红着回来。

构建命令（本机工具链在 `C:\atmp`）：

```bash
export JAVA_HOME='C:\atmp\jdk-17.0.12+7'
export PATH="/c/atmp/jdk-17.0.12+7/bin:$PATH"
/c/atmp/gradle-8.11.1/bin/gradle.bat --no-daemon --console=plain :app:testPreviewUnitTest :app:assemblePreview
```

**必须在纯 ASCII 路径下构建**。中文路径下单测会 5 个类全 `initializationError`
（`android.overridePathCheck=true` 只绕 AGP 检查，绕不过 test worker 的类加载）。
做法：`git archive HEAD | tar -x -C /c/tmp/nbu-xxx`，再 `cp local.properties`。

## 6. 踩过的坑（都真实发生过，别再踩）

| 坑 | 症状 | 正解 |
|---|---|---|
| `apply()` 后立刻 `exit(0)` | 切学校重启又回默认值 | 改 `commit()` 同步写 |
| 中文路径跑单测 | 全部 `initializationError`，报测试类自己找不到 | 复制到 ASCII 路径再跑 |
| 共享表无 schoolId | 切回宁大留着湘潭时间，整表偏 30 分钟 | 写入记来源，切回按来源还原（0.38.3） |
| 宁大"永不套用预设" | 保护了梅山用户，却让切回来的人停在错时间 | 只在我们写过别的校时才覆盖 |
| 上传脚本只看 `git status` | 0.37.20 的包少了整个 `assets/schools/` | 用 `tools/push-via-api.py`（取并集） |
| 下载不写临时文件 | 半截包交给系统，报"解析软件包时出现问题 (33)" | 写 `.part` → 校验 → rename → 写 `.ok` |
| `FieldExtractor.weekMask` 取第一段 | 湘潭第 10 周只剩 2 门课 | `WeekParser` 按段解析（0.38.2） |
| PDF 的 y 方向 | 单元格内多行逆序拼接，正则不匹配 | 取词时 `y = -yDirFirst` |
| KDoc 里写 `assets/schools/*` | `/*` 触发嵌套注释，整文件吞掉 | 注释里避开 `/*` |
| `runCatching{}` 表达式体里 `return` | 语法错误 | 块体 + `return@runCatching` |
| CI 上 `org.json` 是桩 | 本地过、CI 挂 | 判定抽纯数据入口 |
| 标签受保护 | 删不掉也移不动，只能新开版本 | 改 tag 前先确认 |
| `mktag.py` 传短 SHA | `422 The object parameter must be exactly 40 characters` | 传 `git rev-parse` 的完整 40 位 |
| API 直推后本地显脏 | `sync-remote.sh` 拒绝执行，HEAD 落后 | `git fetch origin main && git reset --hard origin/main` |
| pdfbox 带 BC 后量子表 | 包体 29.21MB | `packaging.resources.excludes` 排除 pqc |

## 7. 现在的已知限制

1. **民政没有校历**（`years: []`）→ 切过去不会自动设开学时间，周次靠手动；资讯栏目为空。
2. **民政 PDF 的教师姓名**是字体子集缺映射，抽出来可能为空或乱码。
3. **民政 PDF 参数靠样本估**：`columnAnchor=星期`、`rowGapPt=13`、`namePrefixMerge=true`，
   真机遇到别的版式还要调。
4. **七天周隐藏老师**按列数判断（≥7），「显示整周」打开时也会隐藏。
5. **时间栏七列只显示起始时刻**，不再显示结束时刻。
6. **静态清单没有 ETag**，每次仍下整份清单（几 KB）。
7. **APK 仍从 GitHub 下载**，网络不畅要手动下。
8. **切校只换规则 / 作息 / 资讯 / 入口，课表不换**：`TimetableDao.observeCurrentTerm`
   是 `SELECT * FROM term WHERE isCurrent = 1`，没按 `schoolId` 过滤（表里有这一列）。
   用户会报「切了学校课表还是原来的」—— 这是「多校深化」没做完，不是切换坏了。
9. **`schema` 只对 0.39.3 及以后生效**：更早的版本不认识这个字段，仍会应用新包。
   要拦老版本时，发包必须同时抬 `minAppVersion`（`publish-pack.sh` 在 `schema` 变大时会自动抬）。

## 8. 接下来该做什么

优先顺序（细节见 `docs/dev/PENDING.md`，已完成项与历史决策在 `docs/dev/PENDING-ARCHIVE.md`）：

1. **0.40.0 资源包只向上兼容**：`publish-pack.sh` 默认把门槛抬到当版；`rules.json` 的 schema 由
   精确相等改为 `<= supported`。ETag / 每日检查 / `cachedApk` 若要做也并进这一版
2. **0.41.0 多校数据隔离**（`schoolId` 贯穿）；**0.42.0 → 0.43.0 主线**：取词源抽象 / 几何语义分层
   → 布局声明化 + 新校准入，目标是"新学校只发资源包"（`PENDING.md` 第零节五步）
3. 民政补齐（校历、资讯、PDF 参数）：⛔ 缺数据，等输入
4. 视觉 / 动效：一律后置

## 9. 需要用户拍板的事

- 湘潭的资讯栏目要不要保留「教务处」这一栏（若长期取不到）
- 1.0.0 何时算"成型"（一直按 0.x 迭代是既定策略）
- 视觉/材质类改动一律后置，等他明确要做

---

**上手第一步建议**：`git log --oneline -10` 看时间线 → 读 `docs/dev/SCHOOL_RULES.md`
→ 在 ASCII 副本跑一次 `:app:testPreviewUnitTest`（约 2 分钟，应 118 项全绿）。
