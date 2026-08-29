# 资产大王：奢华黑金主题与 Compose 设计系统调研

> 调研日期：2026-08-18
>
> 范围：GitHub 一手源码、仓库 README 与官方 Material/Android 规范；不采用效果图聚合站、博客二手总结或不明来源 UI 套件。
>
> 结论性质：设计与技术选型建议，不代表已经安装 Skill、引入依赖或修改源码。

## 结论先行

GitHub 上没有找到一个同时满足“专门面向奢华黑金、Jetpack Compose 可直接落地、来源和许可证清楚”的成熟设计 Skill。最可靠的做法不是套用陌生黑金模板，而是组合两层能力：

1. 用 [`hamen/material-3-skill`](https://github.com/hamen/material-3-skill) 约束 Jetpack Compose 的组件、颜色角色、形状、排版和无障碍；它明确以 Compose 为主，并提供主题生成与合规审计，许可证为 MIT。
2. 为资产大王编写一份很薄的项目级“Vault Noir / 金库黑金”主题规范，固定本项目的黑金色票、锐利形状、图表语义色和禁用项。通用的 [`frontend-design` Skill](https://github.com/mitsuhiko/agent-stuff/blob/main/skills/frontend-design/SKILL.md) 可以帮助 Agent 坚持单一的 luxury/refined 视觉方向，但它面向 Web，不应替代 Compose 规范。

明确推荐：**保留 Material 3 的交互、状态和无障碍骨架，替换视觉令牌；不要为了“锋利”重造 Button、TextField、BottomSheet 等基础控件。** 黑金主题应成为独立的完整主题族，不是给浅色碳水大王主题简单换一个金色强调色。

## 1. 可直接使用的设计 Skill

### 推荐：Material Design 3 Skill

[`hamen/material-3-skill`](https://github.com/hamen/material-3-skill) 的主目标就是让 AI Agent 为 Jetpack Compose 生成符合 Material 3 的组件、设计令牌、响应式布局和无障碍实现。仓库说明其 Compose 支持为首要路径，并包含颜色系统、主题与动态色、排版与形状、组件目录等分拆参考文件；还能按颜色、排版、形状、层级、组件、导航和无障碍等维度审计界面。

适合资产大王的原因：

- 能约束 Agent 不因追求“奢华”而破坏点击区域、状态反馈、系统栏、键盘、Bottom Sheet 和无障碍。
- Material 3 本身允许替换 `ColorScheme`、`Typography` 与 `Shapes`，因此锐利黑金不等于放弃 Material 的可靠交互。
- Skill 明确提醒其内容可能随官方规范更新而漂移，精确 API 和版本仍应以 Android 官方文档为准。这是合理边界，不应把第三方 Skill 当成规范原文。

许可证：仓库标注 [MIT](https://github.com/hamen/material-3-skill/blob/master/LICENSE)。可以复制、修改和用于商业/私有项目，但分发其内容时需保留版权与许可声明。

### 可选参考：Frontend Design Skill

[`mitsuhiko/agent-stuff` 中的 frontend-design Skill](https://github.com/mitsuhiko/agent-stuff/blob/main/skills/frontend-design/SKILL.md) 要求先确定唯一视觉方向，再定义排版、颜色、布局和少量有意义的动效，并把 `Luxury / refined / minimal` 与 `Art-deco / geometric` 列为可选方向。这些原则适合用来生成黑金高保真样式稿。

不直接推荐安装为资产大王的实现规范：它的描述明确针对 HTML/CSS/JS、React、Vue 等前端界面，缺少 Compose API、Android 系统交互和移动端可访问性约束。它只能补充审美方向，不能替代 Material 3 Skill。

许可证：上游仓库为 [Apache-2.0](https://github.com/mitsuhiko/agent-stuff/blob/main/LICENSE)。复制或修改时需要保留许可证、版权/归属通知；若分发修改文件，应按许可证要求标示修改。

### 不存在的部分

本次检索没有发现值得直接采用的“black-gold luxury Compose SKILL.md”。搜索结果中存在大量网页模板、图片风格提示词和未说明来源的主题集合，但它们无法同时证明 Compose 适配、设计系统完整性与许可边界，因此不列入候选。

如果后续确实需要 Skill，建议在视觉稿通过后，把本文件确认的令牌和禁用项制作成资产大王自己的项目级 Skill；这比安装一个只会生成“黑底＋亮黄渐变”的泛用 Skill 更稳定。

## 2. 推荐视觉方向：Vault Noir（金库黑金）

一句话定义：**像私人金库仪表盘，而不是夜店霓虹或游戏充值页。**

### 2.1 形状与边框

用户提出的“锋利边框”可行。Android 官方说明 Compose Material 3 的 `Shapes` 可以在整个主题或单个组件上覆盖，并且提供无圆角的 `RectangleShape`；Material 的形状本来就用于表达品牌和界面层级。[Android 官方 Material 3 in Compose：Shapes](https://developer.android.com/develop/ui/compose/designsystems/material3#shapes)

建议采用“近直角”，而非所有元素绝对 0dp：

| 对象 | 建议角半径 | 原因 |
| --- | ---: | --- |
| 财务总览、统计大卡、贷款总览 | 2dp | 形成金库面板的利落轮廓，仍避免屏幕抗锯齿显得生硬 |
| 普通模块、列表容器、输入框 | 4dp | 保留可点击控件的完成度和层级 |
| Chip、筛选项、状态标签 | 2dp 或胶囊仅限真正的开关/筛选 | 不把全页面重新做成大圆角 |
| 图标底块 | 2dp | 与面板语言一致 |
| 圆形 | 仅环形图、单选圆点、悬浮加号等语义上本就为圆的元素 | 不为了风格破坏控件辨识度 |

边框以 1dp 为主，重要选中态可用 1.5–2dp 金边。不要给每张卡都加发光外圈；用边框亮度和表面色差建立层级。Material 官方形状规范也包含 0dp 的无圆角样式，说明“锐利”可以在 Material 体系内实现，而无需自建控件。[MDC Android Shape 规范源码](https://github.com/material-components/material-components-android/blob/master/docs/theming/Shape.md)

### 2.2 背景与表面层级

黑金主题不应只有 `#000000` 和一种金色。建议用四级低明度表面，减少阴影：

| 令牌 | 建议色 | 用途 |
| --- | --- | --- |
| `background` | `#070706` | 页面底色 |
| `surfaceLow` | `#0D0D0C` | 底部导航、大片连续区域 |
| `surface` | `#121210` | 普通卡片、列表 |
| `surfaceHigh` | `#1A1916` | 弹层、选中卡、展开区 |
| `outline` | `#39352C` | 普通细边框、分隔线 |
| `outlineGold` | `#806A37` | 重点卡片或选中态边框，禁止全屏滥用 |

Material 3 已将不同表面层级建模为 `surfaceContainerLow`、`surfaceContainer`、`surfaceContainerHigh` 等角色，适合映射上述层级，而不是靠大面积阴影和金色描边区分所有卡片。[Material Components Android 颜色主题源码](https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md)

### 2.3 金色层级

金色承担品牌、选中和重点，而不是承担所有数据语义：

| 令牌 | 建议色 | 用途 |
| --- | --- | --- |
| `goldPrimary` | `#D0AF62` | 主按钮、当前导航、选中图标、核心数字 |
| `goldBright` | `#F0D58A` | 小面积高亮、焦点、选中描边 |
| `goldMuted` | `#9A7B3F` | 次级图标、进度轨道已用部分 |
| `goldDim` | `#5D4B2B` | 未选中装饰线、低强调边框 |
| `onGold` | `#17120A` | 金色按钮上的文字/图标 |

不要使用纯黄色 `#FFD700`、大面积金色渐变、金粉纹理和持续发光。若需要质感，只允许核心总览卡出现极弱的金属明暗渐变；普通控件坚持纯色。

### 2.4 文字与数字

- 中文正文继续用 Android 系统中文字体，避免打包来历不清的“奢华字体”。
- 金额数字使用系统无衬线的 `FontFeatureSettings("tnum")` 或等宽数字效果，保证列表金额纵向对齐。
- 大标题/大金额可用 600–700 字重；正文 400–500；不要全局粗体。
- 主文字建议 `#F3EEE3`，次文字 `#B9B1A3`，弱文字 `#817A6E`。纯白只留给极少数最高强调内容。
- 金色正文只用于可点击/选中/核心 KPI，长段文字不要用金色，避免疲劳和对比不稳定。

### 2.5 语义色：黑金下仍需保留，但改成“宝石色”

最合适的方案不是把收入、支出、欠款、提醒全部染成金色。Material 官方颜色系统明确区分品牌色与语义角色，并强调自定义颜色要成对维护容器色/前景色和对比关系。[Material Components Android 自定义颜色说明](https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md#custom-colors)

推荐语义色：

| 语义 | 建议前景色 | 深色容器 | 说明 |
| --- | --- | --- | --- |
| 收入、已完成、余额增加 | 翡翠绿 `#67C99A` | `#10271E` | 比荧光绿沉稳，仍能立即识别“正向” |
| 支出、欠款、赤字、故障 | 珊瑚红 `#FF7A72` | `#311714` | 在黑底比暗酒红更清楚；欠款大数字不建议长期满屏鲜红，可正文米白＋负号/标签红 |
| 临近还款、需核对 | 琥珀橙 `#E4A853` | `#302312` | 与品牌金相近，必须同时带提醒图标和文字，不能只靠色差 |
| 信息、补收、同步中 | 蓝宝石蓝 `#74A9E8` | `#142337` | 与金色区分明显，适合非成败状态 |
| 品牌、选中、当前导航 | 哑光金 `#D0AF62` | `#2A2213` | 不承载成功/失败含义 |

关键原则：

- 金色是品牌色，不是“警告色”也不是“收入色”。
- 语义必须同时通过 `＋/－`、图标、文字标签和必要时的删除线表达，不能只靠红绿。
- 在最终视觉稿和真机上做对比度校验；必要时通过 Material Color Utilities 的 contrast/HCT 能力生成和验证暗色角色。其官方仓库说明提供 HCT 色彩空间、色调调色板、暗色状态与对比控制，并提供 Kotlin 实现。[Material Color Utilities](https://github.com/material-foundation/material-color-utilities)

### 2.6 图表

黑金主题下，图表不应把所有分类都做成难以辨认的金色深浅：

- 默认总览环可使用一组低饱和宝石色（翡翠、蓝宝石、紫水晶、珊瑚、青石、哑光金）；被选中的一级分类改为金色描边或提高亮度。
- 下钻到某一级分类后，必要/非必要只用两种固定语义：必要用翡翠或米白，非必要用琥珀/珊瑚；同时显示文字和百分比。
- 收支趋势：收入固定翡翠绿、支出固定珊瑚红、结余折线固定哑光金；赤字点再叠加向下标识。
- 网格线使用 `#2B2924`，坐标文字使用弱米灰；避免亮金网格抢夺数据注意力。
- 环形图和柱图的触摸目标、选中提示、图例不能因锐利风格被缩小。

如后续需要成熟 Compose 图表库，Vico 官方仓库原生支持 Jetpack Compose/Compose Multiplatform，且可自定义主题，许可证为 Apache-2.0；但是否引入应在核对项目现有图表实现后决定，不应只为换颜色增加依赖。[Vico](https://github.com/patrykandpatrick/vico)

## 3. 与现有浅色主题的关系

建议黑金主题拥有独立令牌，但共享同一组件与信息架构：

- 数据、布局顺序、点击区域、下钻和状态机完全一致。
- 浅色四套主题继续使用碳水大王式大圆角；黑金主题覆盖 `ColorScheme`、`Shapes`、部分边框与图表色。
- 不要在业务代码里写 `if (blackGold) { ... }` 到处分叉。组件只读取 `AssetsTheme.colors / shapes / chartColors` 等主题令牌。
- 只有视觉资源允许差异，不能让黑金主题出现独有功能或不同账务含义。

这样既满足“黑金必须有完全不同的气质”，也不会形成两套难以维护的页面。

## 4. 开源许可与复用边界

| 来源 | 许可证 | 可以做什么 | 需要注意 |
| --- | --- | --- | --- |
| [`hamen/material-3-skill`](https://github.com/hamen/material-3-skill) | MIT | 安装、修改、用于生成 Compose 设计和审计 | 分发 Skill 副本或改版时保留 MIT 版权与许可声明 |
| [`mitsuhiko/agent-stuff`](https://github.com/mitsuhiko/agent-stuff) | Apache-2.0 | 借鉴/修改 frontend-design Skill | 分发时保留许可证和归属；修改文件需标示变更；不要暗示上游背书 |
| [AndroidX / Compose](https://github.com/androidx/androidx) | Apache-2.0 | 使用 Material 3/Compose API 与源码实现 | 遵循依赖与应用分发中的开源声明要求 |
| [Material Color Utilities](https://github.com/material-foundation/material-color-utilities) | Apache-2.0 | 使用 Kotlin 色彩与对比算法 | 保留许可证/NOTICE 要求；若只用 Compose 已有能力则不必重复引入 |
| [Vico](https://github.com/patrykandpatrick/vico) | Apache-2.0 | 实现 Compose 图表并自定义暗色主题 | 引入前先确认现有依赖和功能是否已满足，避免无谓替换 |

用户提供的极光奖 App 和碳水大王截图是视觉参考，不等于获得对方插画、图标、字体、源码、布局素材的复制许可。资产大王可以吸收“锐利卡片、模块化仪表盘、层级与交互模式”等通用思想，但应使用原创色票、图标、间距、图表和组件实现。

## 5. 明确推荐方案

1. 主题名称暂定 **金库黑 / Vault Noir**，作为五套主题中唯一完整深色主题。
2. 视觉组合采用“炭黑多级表面＋1dp 细边＋2–4dp 近直角＋小面积哑光金”。
3. 品牌金只负责选中、导航、按钮和重点数字；收入/支出/提醒/信息继续使用低饱和宝石语义色。
4. 页面布局、组件行为和无障碍继续基于 Material 3；通过主题令牌改变外观，不复制一套黑金业务页面。
5. 可以采用 `hamen/material-3-skill` 辅助后续 Compose 实现和审计；不建议安装所谓专用黑金模板 Skill。
6. 在正式编码前先做三张黑金真机比例样式图：首页、统计页、待确认编辑页。只有这三张同时成立，才把色票和形状固化为项目级 Skill/设计令牌。
7. 依赖策略保持克制：Material 3 已能实现颜色与近直角形状；Material Color Utilities 和 Vico 仅在现有能力不足时引入。

## 最终判断

用户提出“黑金主题不再使用大圆角”是合理且有辨识度的方向。最合适的不是完全抛弃碳水大王的清晰信息架构，而是让黑金主题在同一布局上切换为更像保险库控制面板的视觉语言。推荐下一步先画一张首页黑金样式图，以 2dp 主卡、4dp 输入控件、哑光金品牌色和宝石语义色验证气质；确认后再把它写成可执行的项目主题规范。
