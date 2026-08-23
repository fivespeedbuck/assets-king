# 2026-08-23 贷款最后收口换会话交接

> 这是继 `2026-08-23-loan-stabilization-session-handoff.md` 后的增量交接。必须先完整读取前一份交接，再读本文件。不要从头重做。

## 1. 硬门禁

- 绝不修改、构建、清理或执行任何 Git 操作于 `D:\assets-king`。
- 只在 `D:\assets-king-codex-recovery` 工作。
- 只使用包名 `com.assetsking.app.recovery`。
- 不提交。
- 当前最新源码包含**尚未完整编译**的最后收口补丁。必须先只读复核，再编译；不能把它当成完成。
- 每次明确区分：已修改、已编译、已安装、已真机通过。

## 2. 接管时的真实状态

### 已在此前完成并有证据

- 首页、贷款页、还款日历已共用 `monthRepaymentItems` 权威投影；真机此前验证四笔待还合计 `¥6,520`。
- 用户创建一笔广发信用卡已出账账单分期：三笔消费 `300 + 400 + 300 = 1,000`，12 期，每期 `¥100`。
- 数据库证明：剩余本金 `¥1,000`，12 期预测息费总计 `¥200`，预计总还款 `¥1,200`；原界面隐藏了这 `¥200`，用户据此要求全局补齐未来息费。
- 数据库 Android 测试曾在 iQOO 上逐项独立执行，`38/38` 通过。整批运行在测试包第 19 项附近被 OriginOS `fast_freezer` 杀死；逐项新进程验证排除了测试逻辑失败。
- 守护者开关、通知固定文案、通知点击、设置清理等上一阶段工作按旧交接记录继续执行。

### 本轮已修改但尚未完成门禁

涉及：

- `app/src/main/kotlin/com/assetsking/app/ui/screen/LoanScreen.kt`
- `app/src/main/kotlin/com/assetsking/app/ui/screen/RepaymentPresentation.kt`
- `app/src/main/kotlin/com/assetsking/app/ui/screen/HomeScreen.kt`
- `app/src/main/kotlin/com/assetsking/app/LedgerViewModel.kt`
- `core-database/src/main/kotlin/com/assetsking/database/LedgerRepository.kt`
- `core-database/src/androidTest/kotlin/com/assetsking/database/LedgerRepositoryIntegrationTest.kt`
- `core-ledger/src/main/kotlin/com/assetsking/ledger/LoanCalculator.kt`
- `app/src/test/kotlin/com/assetsking/app/ui/screen/LoanPresentationTest.kt`

本轮意图：

1. 信用分期卡展示剩余本金、预计未来息费、预计剩余总还款，期次拆分本金与息费。
2. 贷款页负债构成下展示“另有预计未来息费”，明确未入账、不计入当前总负债，并区分普通贷款与信用分期。
3. 普通贷款计划将“剩余欠款”改为“剩余本金”，显示预计剩余息费、预计剩余总还款、预计总息费；旧数据仅有部分期次时必须写“当前计划”，不能冒充整笔贷款。
4. 用户要求删除“提前还款测算”。入口、弹窗以及错误的 `LoanCalculator.earlyRepaymentSavings` / `EarlyRepaymentResult` 已删除。用户实测旧功能在剩余本金 `¥3,000`、三期、全额提前偿还时错误显示节省利息 `¥3,036.08`，这是删除依据。
5. 实际“提前还款”新增“手续费 / 违约金（可选）”：
   - 实际现金流 = 本金 + 手续费；
   - 贷款余额只减本金；
   - 流水分别保存 `principalCents` 与 `feeCents`；
   - 删除流水时应恢复本金和现金余额。
6. 已新增数据库集成测试 `loanPrepaymentFeeChangesCashFlowButDoesNotReducePrincipalTwice`，但尚未成功完成本轮编译与真机执行。
7. 信用分期卡片展开仍只展示近三期摘要；新增“查看完整计划”按钮，完整计划单独 Sheet 展示全期，底部“修改分期计划”。不要再把全 12 期直接塞进卡片展开层。
8. 用户认为“信用账户账款”和“信用分期”两个大卡割裂。最新补丁已改为一个“信用账户”大区块：每张信用卡自身大卡内包含账款总览和该卡的分期摘要；普通贷款计划仍单独一块。
9. 新增 `creditAccountRepaymentProjection`：尝试统一一张卡的当前总欠款、普通消费/其他、分期剩余本金、未来息费、预计全部还款、下次还款拆分。
10. 新增规则：如果当前本期账单存在，它是本期总应还的权威金额；还款日前的分期期次只用于解释账单构成，不得在 `monthRepaymentItems` 再单独累加。已新增两个展示测试，但尚未执行。

## 3. 最新用户确认的信用卡产品口径

用户认知主体是一张卡，例如“广发信用卡”，而不是两套数据类型：

- 已出账：这个月必须还的本期账单。
- 未出账：已形成信用卡欠款、等下期账单再还。
- 分期：未来每月要还的分期本金 + 当期预计息费。
- 用户需要直接知道：当前总欠款、普通消费/其他、分期剩余本金、未来总息费、预计全部还款、每月出账日、还款日、下次还多少以及其中普通部分/分期本金/息费。
- 当前总负债只计卡账户当前欠款；未来未入账息费单列，不提前加入当前总负债。
- 本期账单包含的分期当期金额不能再算一次。
- 卡片文字不要多、不要花哨、不要拥挤。允许卡片适当拉高，用清晰金额区块；解释段落放详情页或删除。

当前最新 UI 已缩短标签为：

- 当前总欠款
- 本期应还
- 预计全部还款
- 普通消费 / 其他
- 分期本金
- 未来息费
- 本期账单 / 下次还款（普通、分期本金、息费三项拆分）

必须真机视觉检查，不能仅凭代码判断信息密度。

## 4. 编译状态（必须如实延续）

- 在“信用账户聚合”大改之前，信用分期未来息费的较早版本曾通过 `LoanPresentationTest`，`BUILD SUCCESSFUL`。
- 本轮随后继续修改普通贷款息费、提前还款手续费、完整计划入口、信用账户聚合和去重规则。
- 一次组合编译运行到 `:app:javaPreCompileDebug` 后，为响应用户新交互要求被主动 Ctrl+C 中止，退出码 1；这不是已知编译错误，也不是成功。
- 中止后又继续修改了多个文件。因此当前最终结论是：
  - 已修改：是。
  - 最新代码已编译：否。
  - 最新 APK 已安装：否。
  - 最新功能已真机通过：否。

## 5. 新会话第一步

1. 完整执行 Obsidian 启动记忆序列，并读取本文件、前一份贷款交接、项目当前状态、修复记录和验收矩阵。
2. 只读复核上述 8 个文件，特别检查 `LoanScreen.kt` 大段 UI 合并后的括号、Compose 层级与重复信息。
3. 先运行：
   - `:core-ledger:test`
   - `:app:testDebugUnitTest --tests com.assetsking.app.ui.screen.LoanPresentationTest`
   - `:core-database:compileDebugAndroidTestKotlin`
4. 若失败，按最小改动修复。重点核对新增信用卡测试中的预测总额和本期账单去重是否符合模型。
5. 编译通过后再做完整离线门禁与 Android 测试 APK。
6. 真机上先保留用户当前广发信用卡 12 期测试数据，验证：
   - 当前欠款、分期本金、未来息费 `¥200`、预计全部还款；
   - 本期账单与分期当期不重复；
   - 卡片只显示近三期摘要；
   - “查看完整计划”显示 12 期；
   - “修改分期计划”在完整计划内可进入；
   - 普通贷款显示本金、利息、总还款；
   - 提前还款手续费真实入账与删除回滚。
7. 完成后更新 Obsidian，并继续剩余 P0/P1；贷款仍未完成。

## 6. 特别风险

- `creditAccountRepaymentProjection` 对“未出账普通消费全部会在下一次账单结算”的假设需要测试和产品复核；不能用漂亮 UI 掩盖错误预测。
- 当前本期账单抑制分期期次的条件是“本期账单存在且分期期次到期日不晚于该账单还款日”。必须用已出账账单分期、既有分期当期、未出账分期和零账单四类数据验证。
- `installmentPrincipalCents` 被限制不超过卡当前欠款；若原始数据不一致，需要警示而不是静默吞掉。最新 UI 为精简曾移除长警示，接管时需决定在账户详情保留审计提示。
- 不要恢复“提前还款测算”；用户明确要求删除。

