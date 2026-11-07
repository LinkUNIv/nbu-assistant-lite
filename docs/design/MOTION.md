# 动效参考与统一规范

用途：给「宁大助手 Lite」定一套动效口径，避免同一个应用里出现 150 / 180 / 220 / 260 / 340ms
五种时长混用、两套贝塞尔各写各的。参考仓库只作**思路来源**，不是引入依赖的候选清单。

数据取自 GitHub API（2026-11-07）。星数、fork、最近推送时间都会变，只用来判断「是否还活着」。
下文每个条目里的「能借鉴」都指向具体做法，不是「这个库很好」这种评价。

---

## 一、速查表

| 仓库 | 星 | 最近推送 | 许可证 | 一句话 |
|---|---:|---|---|---|
| `fornewid/material-motion-compose` | 659 | 2026-05-28 | Apache-2.0 | Material Motion 四套模式的 Compose 实现 |
| `skydoves/Pokedex` | 8376 | 2026-10-31 | Apache-2.0 | 把 Material Motion 用在完整应用里的范例 |
| `skydoves/Orbital` | 1203 | 2025-10-15 | Apache-2.0 | 共享元素与「按距离错峰位移」 |
| `ImaginativeShohag/Why-Not-Compose` | 734 | 2026-07-23 | Apache-2.0 | Compose 动效与 UI 的合集，当字典用 |
| `SmartToolFactory/Jetpack-Compose-Tutorials` | 3665 | 2026-09-10 | Apache-2.0 | 系统性的 API 教程，含动画与手势 |
| `canopas/compose-animations-examples` | 490 | 2024-12-26 | **无** | 效果合集，停更且不能抄代码 |
| `abdullahalhakimi/SmoothMotion` | 84 | 2026-05-25 | Apache-2.0 | 预置加载/进度动画 |
| `ardakazanci/EasingCompose` | 2 | 2026-06-27 | 未声明 | 缓动曲线预览与复用 |
| `RevenueCat/placeholder-compose` | 246 | 2026-09-08 | Apache-2.0 | 骨架屏 shimmer |
| `skydoves/Balloon` | 4017 | 2026-11-06 | Apache-2.0 | 带箭头的 tooltip |
| `Calvin-LL/Reorderable` | 1377 | 2026-04-20 | Apache-2.0 | 列表/网格拖拽排序 |
| `mxalbert1996/compose-shared-elements` | 187 | 2023-12-06 | MIT | 共享元素（官方 API 出现前的补丁） |
| `airbnb/lottie-android` | 35769 | 2026-02-15 | Apache-2.0 | After Effects 动画运行时 |

---

## 二、逐个详解

### 1. fornewid/material-motion-compose

- **定位**：把 Material 的 motion system 翻译成 Compose API 的库，支持 Compose Multiplatform。
- **数据**：★659 · fork 15 · Kotlin · 最后推送 2026-05-28 · Apache-2.0 · 未归档。
- **内容**：核心产物按平台分成「Jetpack Compose 版」与「Compose Multiplatform 版」两条，
  主体是 `Core` 模块，Maven Central 发布（`io.github.fornewid:material-motion-compose-core`）。
  它实现的是 material.io 那套 motion pattern，而不是零散的补间工具。
- **能借鉴**：**按「变换的性质」选模式**这件事本身。粗分三类：
  同一层级的两个页面互换 → Shared Axis（沿一个轴平移 + 淡入淡出）；
  层级变化（列表 → 详情）→ Container Transform（容器形变）；
  没有空间关系的内容替换 → Fade Through（先淡出再淡入，中间不重叠）。
  我们现在的整页切换是「横轴 + 淡入」，属于 Shared Axis 的变体；但**列表项进详情时还是硬切**，
  这正是 Container Transform 该上场的地方。
- **引入成本**：一个模块 + 一条依赖，不重。但我们 BOM 停在 1.6.8，它的版本矩阵要对着看。
- **结论**：**不引库，抄判断**。把三类模式的选型写进规范，实现用 `AnimatedContent` 自己搭。

### 2. skydoves/Pokedex

- **定位**：一个完整的示例应用，不是库。
- **数据**：★8376 · fork 1106 · Kotlin · 2026-10-31 · Apache-2.0 · topics 含 `hilt`、`mvvm`、`motion`。
- **内容**：用 Hilt + MVVM + Coroutines/Flow 组织的 Pokedex，界面层大量使用 Material Motion，
  是「Material Motion 在真实项目里怎么落地」的参考。
- **能借鉴**：**列表到详情的转场与返回手势怎么对齐**。它把「点卡片 → 卡片长成详情页」和
  「系统返回 → 卡片收回原位」当成一件事设计，而不是两个独立动画。
- **引入成本**：不可引（是应用）。
- **结论**：只读。它的架构比我们重（Hilt + 多层），照搬会破坏我们现在的轻量结构。

### 3. skydoves/Orbital

- **定位**：共享元素转场库，支持 Compose Multiplatform。
- **数据**：★1203 · fork 41 · Kotlin · 2025-10-15 · Apache-2.0 · topic 含 `sharedelementtransitions`。
- **内容**：README 按 `Transformation` / `Movement` / `Shared Element Transition`（含多元素、
  配合 LazyList）/ `Transition Between Composables` 分章，说明它不只做共享元素，
  还管「元素怎么移动」。
- **能借鉴**：**按距离错峰位移**。同屏多个元素出现时，不是一起淡入，而是按与目标的距离
  依次移动、错开时间。课表的「周视图 ↔ 今日卡片」切换用这个思路会比整块淡入有层次。
- **引入成本**：一个依赖。但共享元素部分与官方 1.7+ 的 `SharedTransitionLayout` 功能重叠。
- **结论**：**思路借鉴，暂不引入**。等升 BOM 后优先用官方 API。

### 4. ImaginativeShohag/Why-Not-Compose

- **定位**：Compose 的「cookbook / playground」，个人维护的合集。
- **数据**：★734 · fork 46 · Kotlin · 2026-07-23 · Apache-2.0。
- **内容**：分 `Animations`、`Compositions`、`UIs`、`Tutorials` 几个大区，每个效果一个独立页面，
  可以直接跑起来看。
- **能借鉴**：**当字典用**。想到「这个效果该叫什么、该用哪个 API」时去翻，比搜博客快。
- **引入成本**：不引。
- **结论**：只读。价值在覆盖面，不在深度。

### 5. SmartToolFactory/Jetpack-Compose-Tutorials

- **定位**：成体系的教程仓库，★3665，比多数「动画合集」更接近教材。
- **数据**：★3665 · fork 421 · Kotlin · 2026-09-10 · Apache-2.0。
- **内容**：按 `Material Widgets` / `Layout` / `State` / `Gesture` / `Graphics` / 稳定性与重组 分章，
  自带搜索，每个章节是独立 module，可以单独跑。
- **能借鉴**：**手势与动画的交界**。我们课表里的长按/点击/拖动判断、滚动中的动画降级，
  这类问题它讲得比零散示例清楚。
- **引入成本**：不引。
- **结论**：只读。查 API 与原理的首选。

### 6. canopas/compose-animations-examples

- **定位**：动效效果合集（含进度指示器）。
- **数据**：★490 · fork 37 · Kotlin · 最后推送 **2024-12-26** · **许可证未声明**。
- **内容**：一组打磨得比较精致的动画片段，README 以动图展示。
- **能借鉴**：看「同一个效果可以做到什么程度」，比如进度指示器的形变。
- **风险**：**没有许可证**意味着默认保留所有权利，代码不能复制进我们的仓库；
  且停更近两年，用的 API 可能已过时。
- **结论**：**只看效果，不碰代码**。

### 7. abdullahalhakimi/SmoothMotion

- **定位**：简化 Compose 动画的小型库，主打预置的加载/进度动画。
- **数据**：★84 · fork 5 · Kotlin · 2026-05-25 · Apache-2.0。
- **内容**：README 的示例集中在 loading 类：下落圆点、圆点进度、旋转圆环、旋转实心圆、
  描边圆点等，另有少量通用工具函数。
- **能借鉴**：**加载态的表达方式**——什么时候用三点、什么时候用环形、什么时候什么都不该动。
- **引入成本**：库很小，但我们已有自己的加载态；再引一套会出现两种 loading 风格。
- **结论**：**不引**。我们更需要的是「加载态的克制规则」，不是更多样式。

### 8. ardakazanci/EasingCompose

- **定位**：缓动曲线的库 + 可视化 playground，早期阶段。
- **数据**：★2 · fork 0 · Kotlin · 2026-06-27 · 许可证字段为 `NOASSERTION`（未明确）。
- **内容**：一个干净的 Kotlin 核心 + Compose 适配层，可以预览、比较、复用曲线。
- **能借鉴**：**把曲线可视化这件事**。我们选 `(0.2, 0, 0, 1)` 与 `(0.05, 0.7, 0.1, 1)` 时
  其实没有对照过——有 playground 才能判断「哪个更跟手」。
- **风险**：星数 2、许可证不明确，不能作为依赖。
- **结论**：**只借方法**（自己做一页曲线对照，或直接用现成在线工具），不引库。

### 9. RevenueCat/placeholder-compose

- **定位**：骨架屏（占位加载）库，支持 KMP。
- **数据**：★246 · fork 9 · Kotlin · 2026-09-08 · Apache-2.0。
- **内容**：可自定义颜色、形状、高亮效果的 placeholder；README 分 `Basic Usage`、
  `Customizing Colors`、`Customizing Shapes`、`Using Different Highlight Effects` 几节。
- **能借鉴**：**shimmer 的节奏**——条纹宽度、移动速度、与真实内容块的对齐。
  它把「高亮效果」做成可选，说明 shimmer 不是唯一解（静态灰块有时更安静）。
- **引入成本**：一个依赖；我们的加载态目前是文案 + 空态，引进来要先决定在哪些页面用。
- **结论**：**只在资讯首屏考虑**，且要先出草图。课表不用——课表有本地数据，不该出现骨架屏。

### 10. skydoves/Balloon

- **定位**：tooltip / 气泡提示库，2.0.0 起支持 `Modifier.balloon` 与 Compose Multiplatform。
- **数据**：★4017 · fork 310 · Kotlin · 2026-11-06（几乎每天在更）· Apache-2.0。
- **内容**：定位、箭头、尺寸与间距、显示/消失动画都可配；README 有 `Positioning`、`Arrow`、
  `Size and spacing` 等章节。
- **能借鉴**：**缩放原点跟随箭头**。气泡不是从中心放大，而是从箭头尖那一侧长出来，
  这是「动效符合物理来源」的典型例子。同理适用于我们任何「从某元素旁边弹出」的面板。
- **引入成本**：不轻（一整套气泡布局与定位）。
- **结论**：**不引**，但把「原点跟随来源」这条写进规范。

### 11. Calvin-LL/Reorderable

- **定位**：拖拽排序库，覆盖 `LazyColumn` / `LazyRow` / 两种 Grid / 两种 StaggeredGrid，
  以及普通 `Column` / `Row`。
- **数据**：★1377 · fork 56 · Kotlin · 2026-04-20 · Apache-2.0。
- **内容**：API 面很宽（README 的 API 段几乎是一张表），支持自动滚动、长按/手柄两种触发。
- **能借鉴**：**拖拽时的视觉反馈顺序**：被拖项先抬起（阴影/缩放）→ 其余项让位 → 松手回弹。
  三段的时间分配比单个动画曲线更影响手感。
- **引入成本**：一个依赖。我们课表暂时没有排序需求（课程按节次固定）。
- **结论**：**暂不引**。将来若做「自定义课程顺序」再说。

### 12. mxalbert1996/compose-shared-elements

- **定位**：官方 `SharedTransitionLayout` 出现之前，社区最常用的共享元素实现。
- **数据**：★187 · fork 14 · Kotlin · 最后推送 **2023-12-06** · MIT。
- **内容**：源自 `mobnetic/compose-shared-element`，提供标准共享元素过渡与
  Material container transform 两种模式。
- **能借鉴**：container transform 的参数（容器圆角、内容淡入时机、返回时的反向比例）。
- **风险**：**停更两年半**，而官方 1.7+ 已有 `SharedTransitionLayout`。
- **结论**：**不引**。要共享元素就升 BOM 用官方 API；这层补丁只适合无法升版本的项目。

### 13. airbnb/lottie-android

- **定位**：After Effects 导出的 JSON 动画运行时，全平台（Android / iOS / Web / RN / Windows）。
- **数据**：★35769 · fork 5426 · Java · 2026-02-15 · Apache-2.0。
- **内容**：业界标准，能力毋庸置疑；配套 `lottie-compose` 提供 Compose 封装。
- **能借鉴**：如果真要做品牌级动效（启动动画、空态插画），这是唯一成熟路径。
- **引入成本**：运行时 + JSON 资产。我们 APK 22.85 MB 里离线 OCR 已占大头，
  再加一个动画运行时和若干 JSON，为几个过渡不划算。
- **结论**：**不引**。我们的动效全是状态变化，不是叙事动画；Lottie 解决的问题我们没有。

---

## 三、我们现在的口径

`ui/common/ScreenEffects.kt` 里的 `MotionSpecs`：

| 名称 | 值 | 用途 |
|---|---|---|
| `Decelerate` | `CubicBezierEasing(0.2, 0, 0, 1)` | 通用减速 |
| `Snap` | `CubicBezierEasing(0.05, 0.7, 0.1, 1)` | 手势释放吸附 |
| `decelerate()` | 默认 280ms | 通用 |
| `page()` | 340ms | 按钮触发的整页切换 |
| `snap()` | 260ms | 松手吸附 |

问题：调用处各自传时长，`150 / 180 / 220 / 260 / 340` 五种混着用；页面切换 340ms 在高刷屏上偏拖；
**没有任何一处用到弹簧**，滚动与拖拽全靠 tween，跟手感不如物理动画。

## 四、建议统一成四档

| 档位 | 时长 | 曲线 | 用在哪 |
|---|---|---|---|
| 瞬时 | **120ms** | Standard `(0.2, 0, 0, 1)` | 按压回弹、开关、勾选、颜色过渡 |
| 快速 | **180ms** | Decelerate | 展开/折叠小区域、行内提示、错误提示出现 |
| 中速 | **240ms** | Decelerate | 卡片展开、列表项进出、Sheet 内内容切换 |
| 页面 | **300ms** | Decelerate | 整页切换、页面级 Sheet 升起 |
| 手势 | **spring**（damping 0.85 / stiffness 400） | — | 拖动跟手、松手吸附、下拉刷新回位 |

**四条硬规则**

1. **位移不超过 24dp**。超过这个量级的移动交给页面切换，不要在同一个屏幕里横向飞。
2. **同屏同时运动的元素不超过 3 组**，多组时错峰 40ms（`Orbital` 的错峰位移是同一思路）。
3. **模糊只在过渡中出现，静止时必须为 0**。`WeekGrid` 的展开动画里已有这条注释——
   离屏合成太贵，每帧开着会把高刷打回 40fps。
4. **弹出的东西从来源方向长出来**（`Balloon` 的箭头缩放原点）。不要一律从中心放大。

## 五、明确不引入的

| 东西 | 理由 |
|---|---|
| Lottie / Rive | 换来的多是叙事动画，我们全是状态变化；包体与维护成本不划算 |
| 第三方组件库（Balloon、FlexibleBottomSheet 等） | 已有自己的 Sheet、卡片与间距体系，第二个设计体系必然打架 |
| 第三方共享元素库 | BOM 停在 2024.06（Compose 1.6.8），官方 `SharedTransitionLayout` 要 1.7+；**先升 BOM**，不是引补丁 |
| 手势驱动的复杂视差 | 与「课表要快」的定位冲突，滑一次多算一层变换 |
| 无许可证的代码（如 `canopas/compose-animations-examples`） | 默认保留所有权利，只能看效果 |

## 六、如果要落地，按这个顺序

1. 把散落的 `tween(180)` / `tween(220)` 收敛到 `MotionSpecs` 的四个入口 —— 纯替换，可逐屏验
2. `page()` 340ms → 300ms
3. 列表项进出统一用 `Modifier.animateItemPlacement()`（1.6 的 API，1.7 才改名 `animateItem`）
4. 拖动/吸附类交互改用 `spring()`，替掉现在的 `snap()` tween
5. 骨架屏只在资讯首屏用一次，其余加载态沿用现有空态文案

前四项都不改变视觉语言，只是把已有动效对齐到同一套节奏；第 5 项要出草图再定。
