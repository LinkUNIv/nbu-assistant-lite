# 动效参考与统一规范

用途：给「宁大助手 Lite」定一套动效口径，避免同一个应用里出现 150 / 180 / 220 / 260 / 340ms
五种时长混用、两套贝塞尔各写各的。参考仓库只作**思路来源**，不是引入依赖的候选清单。

数据取自 GitHub API（2026-11-07），星数与最近推送时间会变，只用来判断「是否还活着」。

## 一、参考仓库

### 1. 规范与模式（看「怎么分类」，不引入代码）

| 仓库 | 数据 | 能借鉴什么 | 适配度 |
|---|---|---|---|
| [fornewid/material-motion-compose](https://github.com/fornewid/material-motion-compose) | ★659 · Kotlin · 2026-05-28 · Apache-2.0 | Material Motion 四套模式的 Compose 实现：Shared Axis（同层级换页）、Fade Through（跨层级）、Container Transform（列表项→详情）、Elevation Scale。**最大价值是「按变换性质选模式」这套判断**，而不是代码本身 | 高。我们现在的整页切换其实就是 Shared Axis 的横轴变体 |
| [skydoves/Pokedex](https://github.com/skydoves/Pokedex) | ★8376 · Kotlin · 2026-10-31 · Apache-2.0 | 一个完整应用里怎么把 Material Motion 用在列表→详情；容器变换的时序与返回手势衔接 | 中。可读，但架构比我们重（Hilt + 分层） |

### 2. Compose 动效库（多数不必引入，挑一两条思路）

| 仓库 | 数据 | 能借鉴什么 | 适配度 |
|---|---|---|---|
| [skydoves/Orbital](https://github.com/skydoves/Orbital) | ★1203 · Kotlin · 2025-10-15 · Apache-2.0 | 同层级网格/列表切换时的「元素轨道位移」：元素按距离错峰移动，而不是整块淡入淡出 | 中高。课表「周视图 / 今日卡片」切换可用同样思路 |
| [ImaginativeShohag/Why-Not-Compose](https://github.com/ImaginativeShohag/Why-Not-Compose) | ★734 · Kotlin · 2026-07-23 · Apache-2.0 | 大量小而完整的动画片段，适合快速对照某种效果该用哪个 API | 中。当字典用 |
| [canopas/compose-animations-examples](https://github.com/canopas/compose-animations-examples) | ★490 · Kotlin · 2024-12-26 · **无许可证** | 效果合集 | 低。停更近两年，且无许可证不能抄代码，只看效果 |
| [SmartToolFactory/Jetpack-Compose-Tutorials](https://github.com/SmartToolFactory/Jetpack-Compose-Tutorials) | ★3665 · Kotlin · 2026-09-10 · Apache-2.0 | 动画 API 的系统性示例（过渡、手势、绘制） | 中。查 API 用 |
| [abdullahalhakimi/SmoothMotion](https://github.com/abdullahalhakimi/SmoothMotion) | ★84 · Kotlin · 2026-05-25 · Apache-2.0 | 把「进入/退出方向 + 时长」收成 DSL 的写法 | 低。我们已有 MotionSpecs，再套一层只会更绕 |
| [ardakazanci/EasingCompose](https://github.com/ardakazanci/EasingCompose) | ★2 · Kotlin · 2026-06-27 | 缓动曲线的可视化 playground | 低。概念有用，代码量不值得引 |

### 3. 具体效果

| 仓库 | 数据 | 能借鉴什么 | 适配度 |
|---|---|---|---|
| [RevenueCat/placeholder-compose](https://github.com/RevenueCat/placeholder-compose) | ★246 · Kotlin · 2026-09-08 · Apache-2.0 | 骨架屏（shimmer）的实现与节奏：速度、条纹宽度、与真实内容的对齐 | 中。资讯首屏加载用得着，但要克制——骨架屏用错地方比空态更吵 |
| [skydoves/Balloon](https://github.com/skydoves/Balloon) | ★4017 · Kotlin · 2026-11-06 · Apache-2.0 | 带箭头 tooltip 的进出场：缩放原点跟随箭头，而不是从中心放大 | 低。我们不用 tooltip |
| [Calvin-LL/Reorderable](https://github.com/Calvin-LL/Reorderable) | ★1377 · Kotlin · 2026-04-20 · Apache-2.0 | 拖拽排序时被拖项放大、其余项让位的时序 | 低。课表暂无排序需求 |
| [mxalbert1996/compose-shared-elements](https://github.com/mxalbert1996/compose-shared-elements) | ★187 · Kotlin · 2023-12-06 · MIT | 共享元素（列表缩略图 → 详情大图） | 低。**优先升 Compose BOM 用官方 `SharedTransitionLayout`**，而不是引这层补丁 |
| [airbnb/lottie-android](https://github.com/airbnb/lottie-android) | ★35769 · Java · 2026-02-15 · Apache-2.0 | After Effects 动画直出 | 不推荐，见第四节 |

## 二、我们现在的口径

`ui/common/ScreenEffects.kt` 里的 `MotionSpecs`：

| 名称 | 值 | 用途 |
|---|---|---|
| `Decelerate` | `CubicBezierEasing(0.2, 0, 0, 1)` | 通用减速 |
| `Snap` | `CubicBezierEasing(0.05, 0.7, 0.1, 1)` | 手势释放吸附 |
| `decelerate()` | 默认 280ms | 通用 |
| `page()` | 340ms | 按钮触发的整页切换 |
| `snap()` | 260ms | 松手吸附 |

问题：调用处各自传时长，`180 / 150 / 220 / 260 / 340` 五种混着用；页面切换 340ms 在高刷屏上偏拖。

## 三、建议统一成四档

| 档位 | 时长 | 曲线 | 用在哪 |
|---|---|---|---|
| 瞬时 | **120ms** | Standard `(0.2, 0, 0, 1)` | 按压回弹、开关、勾选、颜色过渡 |
| 快速 | **180ms** | Decelerate | 展开/折叠小区域、行内提示、错误提示出现 |
| 中速 | **240ms** | Decelerate | 卡片展开、列表项进出、Sheet 内内容切换 |
| 页面 | **300ms** | Decelerate | 整页切换、页面级 Sheet 升起 |
| 手势 | **spring**（damping 0.85 / stiffness 400） | — | 拖动跟手、松手吸附、下拉刷新回位 |

**三条硬规则**

1. **位移不超过 24dp**。超过这个量级的移动交给页面切换，不要在同一个屏幕里横向飞。
2. **同屏同时运动的元素不超过 3 组**，多组时错峰 40ms，不要所有东西一起动。
3. **模糊只在过渡中出现，静止时必须为 0**。这条已在 `WeekGrid` 的展开动画里写过注释——离屏合成太贵，每帧开着会把高刷打回 40fps。

## 四、明确不引入的

| 东西 | 理由 |
|---|---|
| Lottie / Rive | 包体与维护成本换来的多是「叙事动画」，而我们的动效全是状态变化。22.85 MB 里离线 OCR 已占大头，再加运行时和 JSON 资产只为几个过渡不值 |
| 第三方组件库（Balloon、FlexibleBottomSheet 等） | 我们已有自己的 Sheet、卡片与间距体系，引入第二个设计体系必然打架 |
| 第三方共享元素库 | BOM 停在 2024.06（Compose 1.6.8），官方 `SharedTransitionLayout` 要 1.7+。**先升 BOM**，而不是引补丁库 |
| 手势驱动的复杂视差 | 与「课表要快」的定位冲突，滑一次要多算一层变换 |

## 五、如果要落地，按这个顺序

1. 把散落的 `tween(180)` / `tween(220)` 收敛到 `MotionSpecs` 的四个入口 —— 纯替换，可逐屏验
2. `page()` 340ms → 300ms
3. 列表项进出统一用 `Modifier.animateItemPlacement()`（1.6 的 API，1.7 才改名 `animateItem`）
4. 骨架屏只在资讯首屏用一次，其余加载态沿用现有的空态文案

前三项都不改变视觉语言，只是把已有的动效对齐到同一套节奏；第 4 项要出草图再定。
