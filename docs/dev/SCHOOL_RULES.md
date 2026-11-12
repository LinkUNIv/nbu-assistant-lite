# 多校适配（0.38.x 起的架构）

> 读这一份就能接着改学校相关的东西。代码入口都在 `app/src/main/java/app/nbult/lite/school/`。

## 一句话模型

**一所学校 = 一个目录 + 一份 rules.json**。凡是"不同学校不一样"的东西，都往规则里塞；代码只认规则，不认学校。

```
app/src/main/assets/schools/
├── index.json            # 学校清单：id / name / builtin / rules 路径
├── nbu/                  # 宁波大学
│   ├── rules.json        # 解析正则、导入参数、能力开关、域名、周次语法
│   └── news.json         # 资讯源（nbu 的作息仍在 assets/calendar/ 下，历史原因）
└── xtu/                  # 湘潭大学
    ├── rules.json
    ├── calendar.json     # 冬夏两套作息
    └── news.json         # 新闻网 + 教务处
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

`PackGuards` 校验包里的资讯/入口地址，允许集合 = **内置栏目域名 + 内置学校 rules.domains**。

只读 APK 里的规则，不信任资源包自己声明的域名——否则坏包能借 rules.json 把请求引到任意站点。新学校要能靠包下发，得先把它内置进 APK（至少内置 rules.json）。

## 加第三所学校要做什么

1. 建 `schools/<id>/`：rules.json（照 xtu 抄，改正则与限制）
2. 有作息就加 calendar.json；有资讯就加 news.json
3. `schools/index.json` 加一条
4. 导入样本跑一遍，差异写进测试夹具
5. 不需要改任何 Kotlin
