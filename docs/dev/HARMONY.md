# 鸿蒙（HarmonyOS）适配评估

> 状态：**评估完成，未开工**。结论是"先不动，真要动就从 H0/H1 两步验起"。
> 撰写日期 2026-11-19，依据为本项目现有技术栈（Compose + Room + PDFBox + ML Kit + Glance）。

## 1. 先分清两个鸿蒙

| 版本 | 能否装本项目 APK | 要做什么 |
|---|---|---|
| HarmonyOS 4.x 及以前 | **能**，底层保留 AOSP 兼容层 | 无需适配 |
| HarmonyOS NEXT（5.x，纯血鸿蒙） | **不能**，只认 HAP 包 | 需重写一个 ArkTS/ArkUI 应用 |

"适配鸿蒙"实际含义是**为 NEXT 重写一份**，不是改配置。项目的 Android 侧行为不受影响。

## 2. 逐项能力对照

按移植难度排序。

| 能力 | 现实现 | 鸿蒙对应 | 难度 |
|---|---|---|---|
| PDF 课表取词 | `pdfbox-android` 取文本坐标 | 无等价库；可在 `Web` 组件内跑 pdf.js | 🔴 高 |
| 应用更新 | GitHub Release + 自更新 | 只能走 AppGallery，自更新作废 | 🔴 高（流程） |
| 签到码 OCR | ML Kit 中文识别 | `VisionKit` / `@hms.ai` | 🟡 中 |
| 精确提醒 | `AlarmManager.setExactAndAllowWhileIdle` | `reminderAgentManager` + 通知授权 | 🟡 中 |
| 桌面小组件 | Glance（可滚动列表） | FormKit（ArkTS 卡片，能力较弱） | 🟡 中 |
| 常驻通知 | 前台服务 | `backgroundTaskManager` 长时任务 | 🟡 中 |
| 签到 WebView | `androidx.webkit` | `Web` 组件（Chromium），Cookie 管理不同 | 🟡 中 |
| 课表存储 | Room | `relationalStore`（SQLite），schema 照搬 | 🟢 低 |
| 解析规则 | Kotlin | ArkTS 重写；规则本身是数据 | 🟢 低 |
| 网络 / 资源包 | OkHttp + JSON | `@ohos.net.http` / `rcp` | 🟢 低 |

## 3. 三个关键障碍

1. **PDF 取词没有等价库。** 这是核心竞争力所在。可行路径：文件选择 → 交给 `Web` 组件内的 pdf.js 提取文本坐标 → 复用 `rules.json` 的解析规则。性能与字体子集映射问题都要在新端重验。
2. **自更新消失。** 鸿蒙应用只能经 AppGallery 更新；应用内下载安装包属违规。资源包通道（自建 CDN）仍可保留，但主程序必须上架。
3. **提醒可靠性要重验。** 鸿蒙后台管控更严；`reminderAgentManager` 是官方路径，但其日历型提醒与 AlarmManager 语义不同，必须真机验证（本项目 PENDING 第十七节的"提醒自检页"在新端更有价值）。

## 4. 可复用资产

- **完全复用**：`pack-src/**`（学校规则、作息、校历、资讯栏目、公告）、`rules.json` 参数体系、`announcements.json` 协议、文案与发布流程。
- **复用思路**：调课叠放、状态机（权威在容器、UI 只渲染）、权限自检清单、公告三档 severity。
- **需重写**：全部 UI、平台能力适配层、PDF 解析执行环境。

## 5. 建议路线（最小闭环优先）

| 阶段 | 内容 | 目的 |
|---|---|---|
| **H0** | ArkTS 空工程，读内置 JSON 渲染课表 + 周切换 | 验证 ArkUI/Navigation 手感 |
| **H1** | `reminderAgentManager` 排一节课提醒，锁屏 8 小时验证 | **最大不确定性，先验它** |
| H2 | 粘贴文本导入 + 解析（暂不做 PDF） | 打通数据链路 |
| H3 | FormKit 桌面卡片：今日下一节 | 体验差异点 |
| H4 | PDF 取词（pdf.js in Web） | 补齐核心能力 |
| H5 | 上架 AppGallery | 软著、隐私声明、审核 |

## 6. 上架与合规提示

- 个人开发者可上架，但国内应用市场普遍要求**计算机软件著作权登记证书**（或电子版权认证），办理周期以周计，需提前启动。
- 需提供隐私政策与权限清单，且应用内可访问；对剪贴板、后台等敏感权限问得细。
- **禁止应用内自更新**：更新弹窗在鸿蒙版必须删除或改为"前往应用市场"。

## 7. 决策

- 现阶段**不开工**：HarmonyOS 4.x 用户已能用现有 APK，NEXT 用户量仍在爬坡。
- 若启动，先做 **H0 + H1**：提醒在鸿蒙上能否准点，决定这条路的价值上限；验不过就不必投入 UI 重写。
- 项目侧建议：若要做，优先拿它当华为开发者认证的实战项目（覆盖 ArkUI、relationalStore、reminderAgent、FormKit 四个考点）。
