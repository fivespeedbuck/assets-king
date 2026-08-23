# 资产大王可信恢复：贷款优先重启交接

日期：2026-08-22

状态：贷款专项正在进行；代码未提交；未打包、未安装、未做本轮真机视觉验收。电脑重启后从这里继续，不得从头重做。

## 2026-08-23 追加：贷款终验候选与金库重开假绿

- 贷款下半页与二级页候选已完成自动回归，最终 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `917DD1DF60D3393371F723FBE1CB55E6743E615AB63A61E9B1360A8E2293497A`；尚未安装，不能标完成。
- 用户报告重开软件后有时仍需手点“立即恢复”。只读取证发现常驻通知和前台服务存在，但系统 `Live notification listeners` 不含恢复包组件，旧的两信号探针是假绿。
- `MainActivity` 现在每个新实例首次 `onResume` 自动调用 `recoverNow`，复刻按钮的强制解绑/重绑；后续回前台只做轻量重绑。`scripts/repro-vault-p0-lifecycle.ps1` 已增加实时监听第三信号并分别报告划卡存活、重开恢复。
- 用户醒后必须先安装同一候选，再执行 `scripts/verify-loan-ui-iqoo.ps1` 和至少 3 轮金库脚本；vivo 若仍在划卡时冻结整个 UID，需记录为 OEM 硬门禁并配置最近任务锁定、自启动、后台高耗电，而不能把“重开恢复”冒充“划卡后持续监听”。

> 04:23 替代候选：安装前复核又补上“旧计划关联花呗/信用卡后编辑卡死”的修复路径。旧计划不删除，卡片提示需改关联账户，编辑回落到合法贷款账户。最终 APK SHA-256 已更新为 `6F2090539D382119F14769A3CE12BD1BAC3B52CE61393589E8EBBEFD00CAAE78`；此前 `917DD1…` 作废。整仓 320 任务通过；后续按源码注解逐名复核，真机回归脚本实际覆盖全部 31+1+3 个用例。

> 04:28 最终替代候选：新建贷款账户时保留底层计划表单，账户 Sheet 关闭后原输入不丢、新账户自动回到合法选项。APK SHA-256 `CF6B78643414EC3C3B4D309201E247104D6162640C61842299B15FA8281585AE`；`6F2090…` 也已作废。用户醒后只安装本哈希。

> 真机脚本已补“关闭新建贷款账户后恢复原新增计划”的断言/截图，并按有无贷款账户分支检查。离线侧无剩余准备项；不得再改包或继续生成新哈希，用户回复“可以装了”后安装 `CF6B78…` 并直接执行终验。

## 1. 必须立即恢复的 `/goal`

> 接管并可信恢复资产大王：以 298 问/七类验收矩阵/原生浅绿色效果图为唯一产品基线；保持原 D:\assets-king 脏工作树不变；只在 D:\assets-king-codex-recovery 工作；修复全部 P0/P1，完成自动回归、iQOO 真机与视觉验收，最终交付 APK。不能把能编译当成完成。主会话由 Sol High 负责判断、架构和最终验收；机械批量、重复测试和截图优先委派 Luna Max，所有结论由主会话复核。

完整目标仍未完成，不得标记 complete。

## 2. 启动顺序与硬门禁

新会话第一步完整读取：

1. `D:\obsidian\obsidian\00-入口\启动记忆.md`
2. `D:\obsidian\obsidian\01-用户\用户画像.md`
3. `D:\obsidian\obsidian\02-项目\项目索引.md`
4. `D:\obsidian\obsidian\00-入口\读取路由.md`
5. `D:\assets-king-codex-recovery\docs\agents\`（若存在）
6. 本交接
7. `D:\assets-king-codex-recovery\CONTEXT.md`
8. `D:\obsidian\obsidian\02-项目\资产大王\资产大王_信用卡与贷款账务模型_V3精简版.md`
9. `D:\obsidian\obsidian\02-项目\资产大王\设计\AK-ENH-003-信用卡既有消费事后分期设计.md`
10. 当前状态、七类矩阵和修复记录。

硬门禁：

- 绝不修改、清理、构建或执行任何 Git 操作于 `D:\assets-king`。
- 所有代码、构建、ADB、截图和 Git 操作必须显式位于 `D:\assets-king-codex-recovery`。
- 当前恢复分支基线仍是 `codex/recovery-20260820`、HEAD `4f3c609`，上面有大量未提交续接改动；先复核现状，不重写、不提交，除非用户明确要求。
- 包名固定 `com.assetsking.app.recovery`；正式包不碰。
- 用户要求多问题合批、最后只打一次 APK。本轮未打包、未安装。
- 目标设备 iQOO 15U（V2546A / Android 16）。ADB 二进制：`C:\Users\chenyanggggg\AppData\Local\Android\Sdk\platform-tools\adb.exe`。重启前用户最后提供直连端口 `39551`，重启后不得假设仍有效；历史可靠 mDNS 为 `adb-10AG7A1M7K0032G-HZAJbL._adb-tls-connect._tcp`。
- vivo 安全守护安装勾选只能由用户完成，不能绕过。用户操作设备时不并发点击。

## 3. 产品边界与用户最终偏好

- 统计页与流水页已经收口；统计保持双环，不做三环，不再视觉改造，除非发现回归。
- 全局资金语义：收入绿、普通支出红、实际还款紫、正结余淡蓝、赤字深红；报销黄；周期待扣红橙。还款流水使用金币图标。
- 首页上半区在金库 P0 真机确认后可收口；下半区的离线重构已做，仍待同批真机验收。
- 隐私徽记最小版本已完成代码：固定锚点，正常紫色、隐藏灰色，全局金额遮罩和灰阶；混乱/灰雾随机特效明确留到首页、贷款、设置完成后。
- 设置页后做：主页只留分组摘要和入口；短内容原地展开、复杂设置进入二级页；按“月度规划 / 自动入库 / 数据与隐私 / 外观”归组，精简再精简。
- 贷款是用户明确认定的资产大王最重要模块。不能以当前能编译为完成，必须完成模型、视觉、交互、迁移、真机和账务回归。

## 4. 贷款页最终视觉要求

用户不接受进入贷款页先看到一堆细小、密集数字。当前实现方向已改为：

- 页首使用非常直观的大数字“当前总负债”。
- 2×2 关键指标：本月待还（红）、本月已还（紫）、本月净降债（绿/深红）、7 日内到期（红橙）。
- 负债构成用分段条解释信用卡、贷款本金、已到期息费，不把信用卡分期本金再加一次。
- 本月还款进度使用紫色进度条；隐私模式下金额遮罩、图表归零，不泄露比例。
- 还款日历和累计息费/成果默认折叠，避免首屏信息过密。
- 单笔贷款继续使用手风琴；折叠态只保留长名称、剩余欠款、下一还款日；最近三期围绕首个未还期次，日期与金额稳定分列。
- 信用卡消费分期也使用卡片手风琴；折叠先给剩余本金、预计每期、剩余期数和下期日期；展开才显示原消费、预测期次、调整/取消。

当前这些布局已写入代码并通过编译，但没有 iQOO 视觉证据。下一会话必须在真实窄屏检查大数字是否截断、2×2 是否拥挤、长名称换行、手风琴高度、按钮和图表对比度。

## 5. AK-ENH-003 账务铁律

以 Obsidian V3 精简模型为唯一账务依据：

1. 信用卡事后分期是“既有欠款的还款条款”，不是借款入账。
2. 原消费流水的金额、分类、时间和负债保持不变。
3. 创建分期对资产、负债、支出、现金流的即时影响必须全部为 0。
4. 分期本金不得再次计入总负债；信用卡欠款已在信用卡账户余额中。
5. 只能分配已确认、未退款、未被有效分期占用的信用卡消费本金，并同时受信用卡未分配欠款上限约束。
6. 未来期次只是预测，不能生成实际流水。
7. 真实还款是现金账户到信用卡账户的 Transfer，只降现金和负债，不再记支出。
8. 真实利息/手续费由银行实际入账时才形成一次费用和负债；未来预计息费不能提前进账。
9. 取消/调整必须保留原消费关联、旧期次和审计事件；不得硬删除已建立的事实。
10. v22 旧分期没有原流水证据，只迁为 `LEGACY_UNLINKED`，绝不猜链接。

## 6. 本轮已经落下的代码

### 6.1 贷款首屏投影与视觉

- 新增 `app/src/main/kotlin/com/assetsking/app/ui/screen/LoanDashboardPresentation.kt`：
  - 唯一生成大数字首屏数据；
  - 负债构成严格使用 `cardDebt + loanAccountDebt + loanPlanDebt + accruedInterest`；
  - 本月已还继续复用 `CashFlowSummary`；
  - 生成既有信用卡消费的可分期候选，扣除退款、有效分期分配和信用卡未分配欠款上限。
- 重构 `LoanScreen.kt`：
  - 大总负债、2×2 指标、负债构成条、还款进度；
  - 还款日历与详细口径默认折叠；
  - 保留贷款计划手风琴、最近三期、完整计划、记录还款、提前还款、结清等既有能力；
  - 消费分期从原来的任意手填实体改为“从既有信用卡消费创建”。
- `LoanPresentationTest` 新增负债构成求和、还款进度和可分期候选回归；原 `AK-BUG-030` 与最近三期回归仍保留。

### 6.2 安全的信用卡事后分期深模块

- `core-ledger/CardInstallmentPolicy.kt`：
  - 校验原消费可用本金、同卡、信用卡未分配欠款双上限；
  - 生成本金精确到分的未来期次；
  - 固定每期总还款时，把无法区分的成本放入 `expectedUnclassifiedChargeCents`，不伪装成实际利息或手续费；
  - `estimateInstallmentCost` 由本金 + 固定月供 + 期数反推总息费、月度 IRR 和有效年化成本率；假设按月期末还款，只是估算，不是银行名义 APR。
- `core-database/CreditCardInstallmentService.kt`：
  - 创建、调整、取消都在唯一事务边界内；
  - 创建分期不改原流水、不改账户余额；
  - 创建 Allocation、Schedule、不可变 Audit；
  - 调整新增 revision 并取消旧 UPCOMING 预测；
  - 取消只改状态并释放有效容量，不删除关联和审计；
  - 创建日期已改由注入时钟推导，不再直接调用系统日期。
- Room v23：
  - 扩展 `CreditCardInstallmentEntity`；
  - 新增 Allocation、Schedule、Audit 三表；
  - Schedule 新增 `expectedUnclassifiedChargeCents`；
  - v22→v23 旧行迁为 `LEGACY_UNLINKED`，只加表/列，不猜原流水、不改金额。
- `LedgerRepository` / `LedgerViewModel` / `HomeScreen`：
  - 暴露 plans、allocations、schedules；
  - 新建、调整、取消通过服务回调返回 Result；
  - 旧 UI 可直接 `upsert CreditCardInstallmentEntity` 的入口已经移除；
  - 原名为 `deleteCardInstallment` 的 repository 方法当前实际执行审计取消，后续可重命名消除误导。

### 6.3 分期表单当前实现

- 默认“只知道每期还款”：填写本金、期数、每期总还款；自动显示预计总息费和估算年化成本率。
- 可切换“息费分开填写”：分别输入每期预计利息、手续费，适合银行已给明细或非固定月供结构。
- 表单明确说明预测不入账；真实银行息费后续另行入账。
- 用户已提醒不同分期方式输入不同。当前只实现上述两个显式模式；“只知道总手续费”“银行给年利率”“等额本金首期不同/逐期导入”等更多模式尚未实现，不得宣称覆盖全部银行产品。新增模式必须独立显示，不能把所有算法塞进默认首屏。

## 7. 自动证据

本轮最后一次完整针对性门禁：

```text
:core-ledger:test --tests com.assetsking.ledger.CardInstallmentPolicyTest
:core-database:compileDebugAndroidTestKotlin
:app:testDebugUnitTest --tests com.assetsking.app.ui.screen.LoanPresentationTest
BUILD SUCCESSFUL
```

此外此前已经通过：

- `:app:compileDebugKotlin`
- 报销到账图标 `TransactionsFilterTest`
- 信用卡分期 service 的 Android integration 测试代码编译。

新增但尚未在真机执行：

- `LedgerRepositoryIntegrationTest.postPurchaseInstallmentKeepsOriginalExpenseAndCardDebtWhileCreatingAuditTrail`
- `paymentOnlyInstallmentStoresUnknownForecastChargeWithoutPostingInterestOrExpense`
- `cancellingInstallmentReleasesCapacityWithoutDeletingAllocationOrAudit`
- `adjustingInstallmentAppendsRevisionAndPreservesCancelledForecastRows`
- `CardInstallmentMigrationTest.version22LegacyPreviewMigratesWithoutGuessingExpenseLinksOrChangingAmounts`

不能把“Android 测试代码编译”写成“真机测试通过”。

## 8. 仍未完成与高风险接缝

### 8.1 AK-ENH-003 还没有完整收口

- 尚未把真实信用卡还款 Transfer 与具体分期计划/期次建立可审计匹配。
- 因此 `remainingPrincipalCents`、`periodsRemaining`、Schedule 的 paid 字段还不会随真实还款自动推进。
- 自动匹配必须与普通周期代扣严格分开；用户已明确“还款是还款，代扣是代扣”。
- 匹配应优先按信用卡账户、金额、账期/还款日和未还期次判定；有歧义进入待确认，不能静默猜。
- 实际息费入账模型仍需落实；未知预测息费不能直接变成实际费用。
- DAO 仍保留底层 `deleteById`，当前产品路径不调用；应审计是否移除，防止未来误用。

### 8.2 UI 与迁移仍需真实验收

- 新贷款首屏没有截图、没有 iQOO 窄屏/大字体/深色验证。
- 新分期创建、调整、取消没有真实触控证据。
- v22→v23 migration test 只编译，尚未在设备运行。
- 没有生成或安装本轮 APK，符合用户“一批收完再打包”的要求。
- 最后一处 `Math.multiplyExact` 乘法溢出保护加入后，已再次单独运行 `:core-ledger:test --tests "com.assetsking.ledger.CardInstallmentPolicyTest"`，结果 `BUILD SUCCESSFUL`（2026-08-22，14 秒）。

### 8.3 其他未完成模块

- `AK-BUG-031` 金库长时真机门禁仍未完成。
- 2026-08-22 晚间新增 `AK-BUG-033 / P0`：当前安装包漏入两笔美团消费 `¥11.97`、`¥66.93`，支付渠道均为微信零钱。用户推测可能没监听美团/微信通知，但只是待验证假设；用户明确让当前先做贷款，回头再逐层查通知来源、原始落库、`NEW/PENDING_CONFIRMATION` 和自动处理。
- 设置页极简分组重构未完成。
- 隐私模式的混乱灰雾特效未做，必须等首页/贷款/设置完成。
- 七类矩阵、长时监听、Release 签名和最终 APK 仍未完成。

## 9. 同批顺手完成的小修复

- 流水“报销到账”此前因为没有分类图标退化为三个点；已改为一级分类图标库已有 `request-quote`（单据/金额符号），继续使用全局报销黄色。
- `TransactionsFilterTest` 固定无分类或旧 `more-horiz` 都返回 `request-quote`，针对性测试与 App 编译通过；真机待最终 APK 验收。

## 10. 重启后的推荐执行顺序

1. 只读复核上述代码，尤其 `LoanScreen.kt`、`LoanDashboardPresentation.kt`、`CardInstallmentPolicy.kt`、`CreditCardInstallmentService.kt`、Room v23 migration；不要重写已完成部分。
2. 先补贷款纯函数/呈现测试：固定月供模式、息费分开模式、隐私图表归零、长名称、无负债/无本月还款、估算异常输入。
3. 审计并实现“真实信用卡还款 → 分期计划/期次”的可审计匹配，保持普通还款与周期待扣分离；有歧义必须待确认。
4. 运行 core-ledger、App、Android test compile；同批完成后再按用户要求一次打包。
5. 在 iQOO 15U 由用户完成安全守护确认后，运行 v22→v23 migration 与分期 integration tests，再验收贷款首屏/手风琴/长名称/创建/调整/取消。
6. 贷款收口后做设置页极简分组。
7. 回查 `AK-BUG-033` 两笔微信美团真实漏单与金库 P0 长时门禁。
8. 最后才做隐私混乱特效、七类矩阵、Release 签名和最终 APK。

## 11. 结论口径

可说：贷款首屏和安全事后分期的第一阶段代码已落地，针对性 JVM 测试、App 编译与 Android 测试代码编译通过。

不可说：贷款页已完成、AK-ENH-003 已完成、迁移已通过真机、P0 已完成、最终 APK 可交付。

## 12. 2026-08-23 08:41 续接更新（以后续事实为准）

- 04:28 的 `CF6B78…` 已安装并验证新 Activity 首次重开三信号当时为 `notification=true / foreground_service=true / live_listener=true`，但滑除/重开多循环与长时真实通知仍未完成。
- 用户随后明确重开隐秘视觉范围并最终收口：稳定态为 4 张不同透明雾纹理，21% / 16% / 12% / 10%；每次进入生成一个种子，四层雾场共同上下/左右翻转，各层独立缓慢漂移，不再动画透明度。用户对最终真机版明确回复“OK了 完全没问题”，隐秘再次冻结。
- 当前唯一已安装 Debug 候选：44,097,385 字节，SHA-256 `D4D2D3F0FD6E670EBBC5F2413CF94625AD72019BD7ECAE05BDDE065A611C020A`，恢复包名不变。此前 `CF6B78…` 及所有中间雾版本均被取代。
- 当前源码已重新通过完整 `:app:testDebugUnitTest :app:lintDebug`、`core-ledger:test`、`core-usecase:test`、`LoanPresentationTest`。贷款 UI 脚本上次缺少“贷款计划”是保留滚动位置/等待不足的误报：旧 XML 明确存在该标题；脚本现复位顶部并等待 5 秒，语法通过。
- 用户当时切到其他 App；不得抢手机。待用户回到恢复包并指纹退出隐秘后，先跑 `scripts/verify-loan-ui-iqoo.ps1`，再做人工视觉、31+1+3 Android 回归和金库滑除重开三信号。贷款仍绝不是完成状态。
