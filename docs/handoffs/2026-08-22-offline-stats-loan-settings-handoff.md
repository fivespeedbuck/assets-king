# 2026-08-22 统计、多月份数据、贷款与设置离线批次交接

## 硬边界

- 仅操作 `D:\assets-king-codex-recovery`，未触碰 `D:\assets-king`。
- 分支 `codex/recovery-20260820`，基准 HEAD `4f3c6097cb9aa602e184244b07845bea960fa134`。
- 包名保持 `com.assetsking.app.recovery`。
- 用户睡觉期间未安装 APK、未点击真机、未绕过 vivo“安全守护”勾选。

## 本批代码

- `StatsScreen.kt` / `StatsPresentationTest.kt`：双环统一标注轨道、碰撞和留白；收支趋势共用金额轴/零轴、摘要、图例、尺度和 3/6/12 月刻度策略。
- `seed-recovery-visual-data.sql`：恢复数据扩为 2025-09 至 2026-08 连续 12 个月、96 笔。
- `verify-recovery-visual-data.py`：种子重复执行、月份收支、`quick_check`、外键验证。
- `LoanScreen.kt` / `LoanPresentationTest.kt`：剩余本金不再误作月供；折叠态只显示贷款名、剩余欠款、下一还款日；详情/操作进展开态；最近三期围绕首个未还期次；日期金额分列。
- `SettingsScreen.kt` / `SettingsPresentationTest.kt`：自动记账提前为第二部分；显示恢复中/最近入库；监听故障与短信补扫/保活提醒分级；移除 Emoji 状态；规划异步回显；主题横向可访问；移除设置页重复的分类与周期规则块。

## 已通过证据

- 恢复数据：`PASS: 96 transactions across 12 non-zero months; idempotent; database checks clean`。
- 针对性测试：`LoanPresentationTest`、`StatsPresentationTest`、`SettingsPresentationTest` 通过。
- 整批门禁：`:app:testDebugUnitTest :core-ui:testDebugUnitTest :core-database:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug` 成功。
- APK：`D:\assets-king-codex-recovery\app\build\outputs\apk\debug\app-debug.apk`。
- SHA-256：`2522FCFE1FDF18284A896595AB97F5944B38E1D804652E7906DDC2FA97AF5043`。

## 下一步

1. 用户醒来后只安装一次；vivo 安全守护勾选由用户完成。
2. 将 12 个月种子写入恢复包并拉库核对，复核统计 3/6/12 月。
3. 同一正常数据状态下验收统计双环/趋势、贷款和设置；旧截图不能替代新 APK 证据。
4. 随后进入主页 `AK-BUG-017`：优先金库监听 P0，再核对全部下方模块。
5. 完成七类矩阵、长时监听、Release 门禁和最终 APK；不得把本批构建成功当成完整目标完成。
