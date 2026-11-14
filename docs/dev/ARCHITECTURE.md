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
- **隐藏入口**：「我的 → 关于 → 版本行连点 5 次」解锁「学校」卡。解锁状态存在 `SchoolPreference`。

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
| 解析算法、界面、动效、注入脚本、CHANGELOG | APK | **是** | — |

读取顺序固定：**先查已落地的包，没有再读 assets**（`ResourcePackStore.readText`）。
包内的算法与代码一律不生效——远程能改的只有参数与数据。

### 5.1 内容分发原则（2026-11-14 定）

> **新增学校、新增入口、校历/作息更新、栏目调整，一律只发资源包，APK 不动。**

要做到这条，必须同时满足下面五条，缺一条就会变成「改了也得发版」：

1. **读取层**：所有会被新增 / 修改的数据文件，读取处必须走 `ResourcePackStore.readText`，
   不允许 `context.assets.open` 直读（`schools/index.json` 目前就是直读，必须改）。
2. **发布层**：`tools/publish-pack.sh` 的清单要覆盖 `schools/`、`calendar/`、`portal/`、`news/`
   的整棵数据目录，而不是现在的三个宁大文件。
3. **守卫层**：`PackGuards` 的路径判断改后缀匹配（现在只认字面路径，`schools/<id>/news.json`
   会直接跳过校验），host 判断改后缀匹配（现在精确比较，`jwc.xtu.edu.cn` 会被判新域名）。
4. **兼容层**：新增字段一律可选，缺省值等于旧行为；旧版 App 读到读不懂的结构要整包跳过
   （`minAppVersion`），而不是半套生效。
5. **回滚层**：包坏了退回 assets 内置，且 `failedVersion` 拉黑该版本——不能出现「装不上也退不回」。

### 5.2 信任边界

包的下载源是我们自己的仓库（raw.githubusercontent → jsDelivr），manifest 与文件都走 https，
逐文件 sha256。**信任边界在仓库本身，不在包的内容**。放宽到「包可以声明新学校」之后，
守卫的作用从「防第三方投毒」变成「把事故半径限住」，因此保留三条硬约束：

- 新增学校声明的 `domains` **必须是 `edu.cn` 子域**且 https——包被改也只能把请求引向教育网。
- `builtin: true` 的内置学校**不可被包删改**（id、名称、规则路径以 APK 为准）。
- 入口链接仍不许降级到 http（内置里本就是 http 的那一个除外）。

---

## 6. 现存的四处名不副实

细节与验收写进 `PENDING.md`「资源包作为内容分发通道」一节：

1. 入口页不按学校分流（`PortalCatalog.load` 写死 `portal/entries.json`，湘潭用户看到宁大 9 条）。
2. 发布脚本不含 `schools/`，多校文件「能读、发不出」。
3. 守卫按字面路径挂、host 精确匹配，开放 `schools/` 前必须先修，否则等于开后门。
4. `schools/index.json` 直读 assets → 新学校必须发版（与 5.1 的方向冲突）。

---

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
