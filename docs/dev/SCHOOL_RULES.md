# 多校适配（0.38.x 起的架构）

> 读这一份就能接着改学校相关的东西。代码入口都在 `app/src/main/java/app/nbult/lite/school/`。

## 一句话模型

**一所学校 = 资源包里的一个目录 + 一份 rules.json**。凡是「不同学校不一样」的东西，都往规则里塞；代码只认规则，不认学校。

自 0.38.7 起**应用里只内置宁波大学**，其余学校全部由资源包定义：包里加一条就多一所学校，
删一条就少一所；正在使用的那所被删掉时会自动回退宁大。

```
pack-src/                     # 内容源：改这里，再用 tools/publish-pack.sh 发包
├── schools/
│   ├── index.json            # 学校清单：id / name / builtin / rules 路径
│   └── xtu/                  # 湘潭大学（nbu 已内置，不必重复放）
│       ├── rules.json        # 解析正则、导入参数、能力开关、域名、周次语法、推迟上课
│       ├── calendar.json     # 冬夏两套作息 + 官方校历原图（两页 JPG）
│       ├── news.json         # 资讯源：新闻网 + 教务处
│       └── portal.json       # 校园入口
├── calendar/nbu_calendar.json
├── portal/entries.json
└── news/sources.json

app/src/main/assets/schools/  # APK 内置，仅此一份，作为「包还没到」时的兜底
├── index.json                # 只有 nbu 一条
└── nbu/rules.json
```

## rules.json 字段

| 字段 | 作用 | 缺失时 |
|---|---|---|
| `schema` | 结构版本，App 只认自己支持的那版 | 拒绝加载，退回内置 |
| `patterns` | 周次/节次/星期/教师/教室正则 | 解析失效 |
| `tokens` | 星期字、单位、标签词 | 同上 |
| `limits` | maxWeeks / maxSections | 默认 20 / 12 |
| `calendar` | 作息文件路径（`schools/<id>/calendar.json`） | 用内置宁大作息 |
| `news` | 资讯源路径 | 只有内置宁大栏目 |
| `importer.formats` / `importer.pdf` | 能导什么格式；PDF 的列锚、行距、单元格正则、噪声词 | 只支持 Office/文本 |
| `capabilities` | attendance / login / widget | **全开**（保持旧行为） |
| `domains` | 该校域名，用于网络白名单与资源包守卫 | 只能访问内置域名 |
| `weekSyntax` | 周次写法（分隔符、区间、单周、奇偶标记） | 用内置默认写法 |
| `portal` | 校园入口路径（`schools/<id>/portal.json`） | 用全局 `portal/entries.json` |
| `officialCalendar` | 官方校历页地址（逐年 JPG 那种）；留空则不显示「官方原图」卡 | 不显示该卡 |
| `lateStart` | 按楼栋推迟上课：`[{contains,minutes,label}]` | 不推迟 |

**兼容原则**：新增字段一律可选，缺省值等于旧行为。这样老规则包在新 App 上不会崩。

## 三个容易搞混的"作息"

| 东西 | 存在哪 | 谁用 |
|---|---|---|
| 校历 `years`（学期起止） | `schools/<id>/calendar.json` | 自动判断第几周 |
| 作息 `periods`（第 N 节几点） | 同上 | 切换学校时写进数据库 `PeriodTime` |
| 界面显示的时间 | **数据库 `period_time` 表** | 课表网格 |

界面读的是数据库，不是校历文件。所以改作息必须**写入 DB**（`applyPeriodPreset`）才生效。

宁大有主校区/梅山两套，自动套用会误伤，所以**默认学校（nbu）不套用预设**。

### 季节选择

湘潭冬夏两套时间差很多（第五节 14:00 vs 14:30）。`calendar.json` 的每套带 `months`，按当前月份挑：

```json
{"id":"winter","name":"冬季作息","months":[10,11,12,1,2,3,4],"items":[...]}
{"id":"summer","name":"夏季作息","months":[5,6,7,8,9],"items":[...]}
```

用 `SchoolPreference.appliedPeriodPreset` 记住"已应用的学校+季节"，同一套只写一次，避免每次启动冲掉用户手调。

## 切换学校

```
设置 → 版本行连点 5 次 → 出现「学校」卡 → 选校
  ↓
AppContainer.switchSchool(id)
  ├─ SchoolPreference.setSchoolId()（commit 同步写，见踩坑）
  ├─ SchoolContext.switchTo(): 重新加载规则 / 白名单 / 资讯源，tick +1
  ├─ 应用该校作息（非默认学校）
  └─ AppRoot 观察到 tick → rememberViewModel(key="timetable-$tick") 重建课表 VM
```

**不重启进程**。容器里学校相关的东西都是 getter（`schoolRules` / `calendarRepository` / `pdfParse`），不是 `lazy`。

## PDF 导入链路（湘潭）

```
PdfTextExtractor（pdfbox-android，取 x/y，y 取反成"越大越靠上"）
  → PdfLayoutParser（纯算法：表头锚分列、回退半列宽、同列 y 差 >14pt 断行、续表沿用上页列锚）
  → WeekParser（按段解析周次）
  → ParsedSlot
```

- 单元格是**拼接**的，不加分隔符：中文换行会切断词语（`信息科技大楼北-` + `401`）。
- 参数全来自 `importer.pdf`：`columnAnchor` / `rowGapPt` / `headerPerPage` / `cellRegex` / `noiseDrop`。
- 没有文本层 = 扫描件，明确报错，不做 OCR。
- 测试夹具 `app/src/test/resources/pdf/xtu_fragments.tsv`：真实坐标 + 脱敏名称 + 已删学号。

## 资源包与域名守卫

`PackGuards` 校验包里的入口与资讯地址，允许集合 = **内置域名 + 包内各校 `rules.json` 声明的 `edu.cn` 子域**。

- 校验分**两遍**：先验所有 `*/rules.json`（结构与 edu.cn 约束）并收集它们声明的域名，再验入口与资讯。
  顺序反了，湘潭的入口会被当成「新域名」整包拒绝。
- **内置学校（APK 里那条，目前是 nbu）不可删、不可改**：名称、规则路径、`builtin` 标记都不许被包改动。
  非内置学校可以增、可以删、可以改。
- 包把当前学校删掉后，`ResourcePackChecker` 会调 `SchoolContext.refresh()` 当场回退宁大，
  不必等冷启动。

## 加第三所学校要做什么

1. 建 `pack-src/schools/<id>/`：rules.json（照 xtu 抄，改正则与限制）
2. 有作息就加 calendar.json；有资讯加 news.json；有入口加 portal.json
3. `pack-src/schools/index.json` 加一条，`builtin` 写 `false`
4. 导入样本跑一遍，差异写进测试夹具
5. 发资源包（`tools/publish-pack.sh`）——**不用改 Kotlin，也不用发 APK**

下架一所学校就是反着来：从清单里删掉那一行再发包，客户端下次检查时会移除它。
