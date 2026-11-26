# 架构与分工

> 读这一份能回答三类问题：界面上有哪些页、启动后谁在什么时候跑、**什么内容能热更、什么必须发版**。
> 规则细节看 `SCHOOL_RULES.md`，发版流程看 `DEVELOPING.md`，待办看 `PENDING.md`。

---

## 1. 一句话模型

**APP 本体决定「能做什么」，资源包决定「说什么」。**

```
┌─ 界面层  ui/ · browser/ · widget/           Compose，四个 Tab + 三个抽屉
├─ 容器    AppContainer / SchoolContext        谁在什么时候用哪套规则
├─ 领域    domain/ · parser/                    周次、节次、提醒、停课调课、各格式解析
└─ 数据    data/db(Room) · data/pack · net      唯一事实源 + 唯一的远程入口
```

界面不直接碰网络与文件：读数据走 Room 的 Flow，读配置走 `ResourcePackStore`，
**只有 `data/remote` 与 `data/pack` 会发请求**，两者都要过 `net/HostWhitelist`。

---

## 2. 操作地图

| Tab | 主要动作 | 下钻 |
|---|---|---|
| 课表 | 左右切周、点课卡改「本次安排」、点空白加课、导入（文件 / 文本 / PDF） | 课程设置抽屉、添加课程抽屉、导入抽屉 |
| 资讯 | 按当前学校的栏目浏览 | 卡片展开 → 详情（正文 + 原文链接） |
| 入口 | 校历卡（第几周）、分类入口（交系统浏览器）、反馈行（进工作日志） | 校历页（路由 `calendar`） |
| 我的 | 学期与作息、上课提醒、数据与日志、关于 | 外观、编辑手势、作息逐节编辑、更新日志、许可 |

- **校历不是 Tab**：从「入口」页顶部卡片进入；`barRoutes` 保留了它，避免成为无栏可退的死角。
- **三个抽屉共用 `ui/common/AppBottomSheet`**：insets、进退动画、高度过渡只写一次。
- **学校切换**：「我的 → 关于 → 学校」一行显示当前学校，点「切换」弹出选择框。0.39.3 起不再需要连点版本号解锁，也不再把这四所学校平铺在页面上。

---

## 3. 运行时序

| 时机 | 做什么 | 节流 |
|---|---|---|
| `Application.onCreate` | 装崩溃捕获 → 建容器 → 加载学校上下文（规则 / 白名单 / 资讯源）→ `alignPeriods("启动")` → 默认作息种子 | — |
| 首帧 | `AppRoot` 按 `schoolTick` 建 VM，Room 的 Flow 直接出内容 | — |
| 启动 +4 s | 更新检查（清单 → Release 两跳） | 6 小时 |
| 启动 +12 s | 资源包检查（下载 → 校验 → 落盘） | 1 小时 |
| 课表写入 | 800 ms 去抖后刷新两个桌面组件；按课程重排提醒闹钟 | — |
| 回前台 | 复查更新（`RESUMED`，首次跳过） | 6 小时 |
| 资源包版本变化 | 重读 `announcements.json`（不缓存，包换版即重算） | — |

三条由此产生的可感知行为：

- 换学校**不重启**：`SchoolContext` 整体替换 + `schoolTick` 递增 → `rememberViewModel(key="timetable-$tick")` 重建。
- 断网仍能看课表：数据在 Room，只有资讯 / 校历原图 / 更新 / 资源包需要网络。
- 资源包更新后**不会重算已导入的课表**：课表是解析后入库的，包只影响「以后怎么解析」。

---

## 4. 事实源与三个「作息」

```
导入 / 编辑 / 应用作息 / 停课调课 ──→ Room ──→ VM 订阅 Flow ──→ 界面
                                        ├──→ 组件（去抖 800ms）
                                        └──→ 提醒（按周次+节次算时刻）
```

| 名字 | 存在哪 | 用途 |
|---|---|---|
| `years` 学期起止 | 校历 JSON | 算「今天是第几周」 |
| `periods` 每节几点 | 校历 JSON | 切学校 / 换季时**写入数据库** |
| `period_time` 表 | Room | 界面真正显示的时间 |

**改作息必须写库才生效**，唯一写入口是 `AppContainer.alignPeriods()`（切学校、启动、恢复出厂三处共用）。
`period_time` 没有 schoolId，所以「谁写的」记在 `SchoolPreference.appliedPeriodPreset`，切回来按来源还原。

---

## 5. 资源包与 APP 的分工

| 内容 | 归属 | 发版 | 读取点 |
|---|---|---|---|
| 入口清单 | 资源包 | 否 | `ui/portal/PortalScreen.kt` → `PortalCatalog` |
| 资讯栏目与抓取规则 | 资源包 | 否 | `data/news/NewsSources.kt` |
| 校历（学年 / 假期 / 作息） | 资源包 | 否 | `data/repo/CalendarRepository.kt`、`school/SchoolContext.kt` |
| 学校规则（周次语法 / PDF 参数 / 能力开关 / 域名） | 资源包 | 否 | `school/SchoolCatalog.kt` |
| 学校清单 `schools/index.json` | **资源包（2026-11-14 起）** | 否 | 同上 |
| 官方校历原图地址（`calendar.officialImages`） | 资源包 | 否 | `ui/calendar/OfficialCalendarCard.kt` |
| 楼栋推迟上课（`rules.lateStart`） | 资源包 | 否 | `domain/LateStartPolicy.kt` |
| 公告（`announcements.json`） | 资源包 | 否 | `data/pack/Announcements.kt` → 侧栏「公告」 |
| 解析算法、界面、动效、注入脚本、CHANGELOG | APK | **是** | — |

读取顺序固定：**先查已落地的包，没有再读 assets**（`ResourcePackStore.readText`）。
包内的算法与代码一律不生效——远程能改的只有参数与数据。

### 5.1 内容分发原则（2026-11-14 定，**0.38.7 起已全部达成**）

> **新增学校、新增入口、校历/作息更新、栏目调整、发通知，一律只发资源包，APK 不动。**

要做到这条必须同时满足五条，逐条现状：

| # | 要求 | 现状 |
|---|---|---|
| 1 | 读取层走 `ResourcePackStore.readText`，不许 `assets.open` 直读 | ✅ `SchoolCatalog`、`PortalCatalog`、`CalendarRepository`、`NewsSources` 全部走 store（`PackGuards` 里读内置清单用于**校验**，属例外） |
| 2 | `publish-pack.sh` 覆盖整棵数据目录 | ✅ 用 `find . -type f` 打全量清单 |
| 3 | 守卫按**后缀**匹配路径与域名 | ✅ `PackGuards.validateByPath` 全部 `endsWith`，`schools/<id>/news.json` 不再漏检 |
| 4 | **新增参数**一律可选、缺省等于旧行为；**改变结构或语义**必须抬门槛，旧版整包跳过 | ✅ 规则字段全部带默认值；0.39.3 起 `schema` 由 App 自动判定 |
| 5 | 包坏了退回内置，且拉黑该版本 | ✅ `failedVersion` + 24 小时重试窗口 |

**第 4 条要分两种改动看**（0.39.3 补，之前文档把两者揉成一句，读起来像"新字段都该向下兼容"）：

- **新增可选参数**：有默认值、旧行为等价 → 不抬门槛，旧 App 照常吃内容。这是"内容热更"的价值所在。
- **改变结构或语义**：目录布局变、字段含义变、必填变可选的**反面** → 必须抬门槛，旧版整包跳过。

门槛有两个：`schema`（结构版本，App 自己判定，结构一变就 +1）与 `minAppVersion`（App 版本，
靠人判断）。`publish-pack.sh` 会在 `schema` 变大时自动把 `minAppVersion` 抬到当版。

**`schema` 的局限**：已装机的 0.39.2 及更早版本不认识这个字段，照样会应用。历史欠账收不回来，
机制只从 0.39.3 起生效；第一次带 `schema` 的包若要拦老版本，仍须同时抬 `minAppVersion`。

**一条例外值得记**：公告（`announcements.json`）走的是**逐条丢弃**而不是整包原子——
公告是附加信息，一条写错不该把校历作息一起拒掉。这是全局唯一允许部分失效的数据类型。

### 5.2 信任边界

包的下载源是我们自己的仓库（raw.githubusercontent → jsDelivr），manifest 与文件都走 https，
逐文件 sha256。**信任边界在仓库本身，不在包的内容**。放宽到「包可以声明新学校」之后，
守卫的作用从「防第三方投毒」变成「把事故半径限住」，因此保留三条硬约束：

- 新增学校声明的 `domains` **必须是 `edu.cn` 子域**且 https——包被改也只能把请求引向教育网。
- `builtin: true` 的内置学校**不可被包删改**（id、名称、规则路径以 APK 为准）。
- 入口链接仍不许降级到 http（内置里本就是 http 的那一个除外）。

---

## 6. 曾经的名不副实（均已修，留档防回退）

0.38.7 之前，下面四条让"只发包不发版"实际上做不到。现在都已解决，**不要回退**：

| # | 当时的问题 | 现状 |
|---|---|---|
| 1 | 入口页写死 `portal/entries.json`，湘潭用户看到宁大 9 条 | ✅ 路径由学校规则决定（`PortalScreen.DEFAULT_PATH` 只作回落） |
| 2 | 发布脚本不含 `schools/`，多校文件"能读、发不出" | ✅ `find` 打全量 |
| 3 | 守卫按字面路径挂、host 精确匹配 | ✅ 全部后缀匹配 |
| 4 | `schools/index.json` 直读 assets，新学校必须发版 | ✅ 走 `ResourcePackStore`（`SchoolCatalog` 的注释还写着 assets，属文案滞后） |

## 6.1 真正的边界（记住这条比记 bug 清单有用）

**远程能改的只有参数与数据，改不了算法。** 遇到"这个能不能发包解决"，先问三句：
有文本层吗？结构与已有学校同族吗？写法在 `weekSyntax` 与正则能力内吗？
三句都"是"→ 只发包；任一"否"→ 需要发版加能力（参见 `PENDING.md` 第六节的 `parserVersion`）。

## 7. 读代码的入口

| 想知道 | 从哪看 |
|---|---|
| 启动做了什么 | `NbuLiteApp.kt` 的 `AppContainer` / `NbuLiteApp.onCreate` |
| 切学校怎么生效 | `AppContainer.switchSchool` → `SchoolContext.switchTo` → `alignPeriods` |
| 作息该不该写 | `school/PeriodPresetPolicy.kt`（纯判定，有单测） |
| 课表怎么解析 | `parser/TimetableParser` → `FieldExtractor` / `WeekParser` / `pdf/` |
| 数据表结构 | `data/db/Entities.kt`（DB 版本 6） |
| 什么能热更 | 本文第 5 节 + `data/pack/ResourcePackChecker.kt` |
| 网络能去哪 | `net/HostWhitelistInterceptor.kt`（白名单是唯一阀门） |
