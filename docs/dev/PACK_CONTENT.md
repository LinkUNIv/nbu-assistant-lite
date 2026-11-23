# 资源包内容清单

回答一个问题：**哪些东西改动可以只发资源包、不用发版。**

判断标准三条（按可外置程度排序）：

| 性质 | 能否包修 | 例子 |
|---|---|---|
| 阈值 / 开关 | ✅ | `rowGapPt`、`multiCourse`、`dropAboveHeader` |
| 正则 | ✅（需外置） | `cellRegex`；地点模式、课号模式 |
| 数据源路径 | ✅（需外置） | `calendarPath`、`newsPath` |
| 处理顺序 / 控制流 | ❌ | 先过滤页眉再分组、按课号分组 |
| 引擎行为 | ❌ | 取词粒度（按词/按行）、OCR |

---

## 一、已外置（`schools/<id>/rules.json → importer.pdf`）

`columnAnchor` `columnEdge` `cellSource` `rowGapPt` `headerPerPage` `cellRegex`
`noiseDrop` `multiCourse` `namePrefixMerge` `groupByCourseCode` `dropAboveHeader` `weekSyntax`

## 二、可外置但仍在代码里（L1 候选）

| 建议键 | 现值 | 位置 |
|---|---|---|
| `lineTolerance` | 3.5 | `PdfLayoutParser` |
| `roomTailChars` | 楼室庭场馆 | 同上 |
| `roomPattern` | 见代码 | 同上 |
| `trailingRange` | `…(\d{4,})-\d{1,2}$` | 同上 |
| `sectionMarker` | `(第n-m节)` | 同上 |
| `courseCode` | `\[[^\]]{1,8}\]` | 同上 |
| `sectionTimePrefix` | 见代码 | 同上 |
| `wordGapRatio` | 0.35 | `PdfTextExtractor` |
| `ocrScale` / `ocrMaxSidePx` | 200/72、2600 | `PdfOcrExtractor` |
| `calendarPath` / `newsPath` / `portalPath` | 硬编码 | `CalendarRepository` 等 |

## 三、可外置但属于流程（L2，暂缓）

`pipeline` 数组：`dropAboveHeader → clipLeft → columnBy → cluster → groupBy → split → regex → postClean`。
前七段可配置驱动；`postClean` 的顺序耦合强，参数化收益低。**学校 ≥ 8 所再考虑。**

## 四、参考 MAA 可新增的内容

| 内容 | 文件 | 优先级 |
|---|---|---|
| 自检清单 | `schools/<id>/selftest.json` | P0 |
| 脱敏夹具 | `fixtures/<id>.tsv` | P0 |
| 结构版本 | `latest.json.schema` | P1 |
| 回退提示 | `latest.json.previous` | P1 |
| 分包 | `schools/<id>/latest.json` | P3 |

## 五、不可外置

裁剪↔归列↔分组的顺序依赖、正则引擎语义、表格线几何重建、PDF/OCR 调用、安全边界。

---

## 六、加字段的检查清单

新增一个规则字段，四处都要动，缺一处就是"空转"（`groupByCourseCode` 就是这样连废两版）：

```
① SchoolRules：JSON 解析 + 字段声明
② PdfImporter：构造 PdfTableParams 时传参
③ 路由日志：打印该字段值
④ 资源包：写值 → CI 绿 → 装机核对日志
```

## 七、外置的前提

外置会把"配置错误"从编译期挪到运行期。**先做包内自检，再外置正则与路径**，
否则发包比发版快，出错也更快上线。
