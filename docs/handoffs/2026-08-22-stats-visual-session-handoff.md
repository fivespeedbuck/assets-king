# 2026-08-22 统计视觉与会话迁移交接

## 用户最新优先级

1. 流水页暂时冻结，但正常演示数据需要补足多个自然月，不能只有 2026-08。
2. 统计页只剩两项：双环标注线/文字，以及底部收支趋势；本会话已经实现并完成一轮真机截图，但尚未完成 6/12 月多数据视觉终验。
3. 主页重点是金库监听 P0，以及主页下方全部小卡片的视觉效果。
4. 用户睡觉期间若无法做视觉验收，先修贷款和设置页面。
5. iQOO 的 vivo 安全守护会拦截 ADB 覆盖安装，界面需勾选“已了解应用的风险检测结果”后继续；不能宣称安装可完全无人值守。

## 强制边界

- 绝不修改或构建 `D:\assets-king`。
- 只在 `D:\assets-king-codex-recovery` 工作。
- 分支 `codex/recovery-20260820`，当前 HEAD `4f3c609`。
- 包名只用 `com.assetsking.app.recovery`。
- ADB：`C:\Users\chenyanggggg\AppData\Local\Android\Sdk\platform-tools\adb.exe`，设备 `192.168.31.80:41611`。
- 用户操作真机时不要并发点击。

## 当前未提交代码

- `app/src/main/kotlin/com/assetsking/app/ui/screen/StatsScreen.kt`
- `app/src/test/kotlin/com/assetsking/app/ui/screen/StatsPresentationTest.kt`

统计实现内容：

- 双环标注改为左右统一标签轨、肘点轨、文字间距和同侧碰撞处理。
- 收支趋势改成收入/支出/结余共享同一零轴与金额尺度。
- 顶部摘要改为三列，增加可见图例、轴金额、月份交互提示和读屏摘要。
- 新增 5 个统计呈现单测（必要性标签、双环左右对称、碰撞边界、共享零轴、选中月份回退）。

## 已通过证据

- 完整命令：`:app:testDebugUnitTest :core-ui:testDebugUnitTest :core-database:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug`
- 结果：`BUILD SUCCESSFUL in 1m 3s`。
- 当前 APK：`D:\assets-king-codex-recovery\app\build\outputs\apk\debug\app-debug.apk`
- SHA-256：`3E4A37103106A8640C447CD99CC91BACD54CA5099A6A158001C2209602EC1B84`
- 真机截图：
  - `.build-output/product-design-audit/after/01-stats-top-after.png`
  - `.build-output/product-design-audit/after/02-stats-trend-after.png`
- 原生浅绿色参考：`.build-output/product-design-audit/reference/effect-a-v2-full.png`

## 未完成与下一步

1. `scripts/seed-recovery-visual-data.sql` 当前只有 2026-08 的正常收入/支出，2026-07 只有贷款还款，导致趋势 6/12 月大量为 0。应给恢复包验收夹具补至少过去 6 个自然月的工资与多分类消费，并保持幂等、只用于恢复包视觉验收，不做生产自动灌数。
2. 补数据后复核 3/6/12 月趋势标签密度、月份切换和二次点击下钻。
3. 统计视觉通过后，若用户不在场，先做贷款和设置；主页视觉与金库 P0 需要最终真机截图验收。
4. 所有非显然结论继续写回 Obsidian；完整七类矩阵、长时监听、Release 门禁和最终 APK 尚未完成，禁止宣布总目标完成。

## 安装状态说明

- 第一轮 ADB 覆盖安装曾返回 `Success`。
- 用户要求复演时，vivo 安全守护出现外部来源应用风险勾选页，第二轮安装未证明完成。
- 后续要么由用户勾选确认，要么先构建并等待用户醒来后集中安装一次；不要绕过系统安全提示。

## 2026-08-22 10:33 续接更新

- 数据已扩为连续 12 个非零月份、96 笔；iQOO 主库 `quick_check=ok`。数据库/待确认真机回归改为逐例独立 instrumentation，标准脚本当前为 13+3 PASS；禁止恢复成整类同进程运行。
- 趋势当前候选：默认 6 月，隐藏普通 Y 轴数字及“收/支/余”前缀，选中月左侧只显示绿色/红色/淡蓝紧凑值；格式按截断而非四舍五入，`6989.83 → 6.9K`；平均支出红虚线已完全删除，绘图区左边距 50dp→44dp。
- “已还款”尚未实现，不能当成当前代码：用户要求先讨论；候选为红色普通支出 + 紫色实际还款堆叠、蓝色现金结余 = 收入−支出−还款、顶部 2×2 四项和双环第四态。用户仍在理解“蓝线按余额 366 落轴”与组成柱的区别，等明确确认再编码。
- 双环中心可循环总支出/总收入/总结余；中心点击反馈为圆形、一级分类行为 16dp 圆角，解决灰色方块按压层。
- 金库最近入库最终结构不可再误改：顶部只保留“金库正常”与徽标；左下完整点击区显示“入库状态 / 最近入库（绿色粗体）/ 时间”，不显示重复“正常”。证据 `D:\assets-king-codex-recovery\.build-output\device-qa-20260822\24-home-metric-final.png`。
- 最新 Debug 已在 iQOO 15U 覆盖安装成功；用户正在逐项视觉复核。视觉接受后重跑完整 Debug/Release 门禁、身份校验与最终哈希。长时监听、七类矩阵和最终签名 APK仍未完成，`/goal` 保持 active。

## 2026-08-22 统计与流水最终收口

- 用户已明确验收“统计和流水收口了”。`AK-UX-003` 与 `AK-UX-005` 的本批视觉项按真机当前状态通过，不再恢复旧交互提示或继续改双环几何。
- 最终保留双环，不改三环。还款不是消费分类：不把紫色还款塞入分类环；“本月消费组成”继续只表达普通消费的一级分类与必要/非必要结构。
- 双环中心保留四态循环：总支出 → 总收入 → 总结余 → 已还款，环本身不随中心切换。
- 趋势最终采用单张现金流组成图：绿色收入；红色普通支出；紫色实际还款；正现金结余以淡蓝色嵌在收入柱顶部，赤字以赤红色表达。实际还款仅含 `LOAN_PAYMENT + LOAN_PREPAYMENT`，未来计划不计；现金结余统一为收入−普通支出−实际还款。
- 顶部摘要为收入/支出/还款/结余 2×2；趋势只点击选月，不再二次点击跳流水，也不显示交互说明。默认 6 月，已删除普通 Y 轴刻度、连接小线段、平均支出文字/红虚线。
- 恢复验收夹具最终为 2025-09 至 2026-08 连续 12 个非零月份、108 笔；每月都有实际还款，2026-06 有消费过多赤字，2026-08 精确口径为收入 6989.83、普通支出 3203.50、已还款 3420.00、现金结余 366.33。
- 最终证据：`.build-output/device-qa-20260822/30-stats-default-final.png`（双环与加深奶糖彩虹色）、`.build-output/device-qa-20260822/33-trend-default-six.png`（默认六个月趋势）。用户评价“完美”，随后确认不做三环。
- 自动门禁：现金流实现后的完整 Debug 单测、Android 测试代码编译、Lint、assembleDebug 已通过；最后一轮调色/尺度/点击清理的 `StatsPresentationTest + lintDebug + assembleDebug` 也通过。当前 APK 已成功覆盖安装，但进入下一批前应重新计算当前文件哈希，不能沿用本文件旧哈希。
- 下一任务切换为主页与贷款。主页优先金库监听 P0 和下方全部卡片视觉；贷款页包含 `AK-BUG-030 / AK-UX-006` 真机视觉，以及 `AK-ENH-003` 信用卡消费事后分期的安全数据模型。设置页可与贷款同批处理。整体 `/goal` 仍 active，七类矩阵、长时监听、Release 签名与最终 APK 尚未完成。
