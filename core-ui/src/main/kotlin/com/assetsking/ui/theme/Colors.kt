package com.assetsking.ui.theme

import androidx.compose.ui.graphics.Color
import com.assetsking.model.TransactionType

// ── Brand ──
val BrandGreen = Color(0xFF42D0A3)

// ── 浅色主题（REQ 主题§2：不透明实体卡片，删除毛玻璃/半透明叠层）──
val GlassCardLight = Color.White                     // 实体白卡
val GlassBorderLight = Color(0xFFDBE5E1)             // 原浅绿色效果图卡片细边框
val SurfaceLight = Color(0xFFF7F8F8)                 // 浅灰白页面背景（对齐碳水大王 container #F7F8F8）
val OutlineLight = Color(0xFFDBE5E1)                 // 原浅绿色效果图输入框边框
val SurfaceVariantLight = Color(0xFFEEF3F1)          // 进度轨道/辅助表面，避免 Material 默认紫灰
val OutlineVariantLight = Color(0xFFDBE5E1)          // 辅助边框，保持浅绿色基线

// ── 深色主题（龙巢预留：Codex 接管深色令牌与材质，这里是可运行占位）──
val GlassCardDark = Color(0xFF242426)                // 占位深色卡片（龙巢将替换为黑曜石层次）
val GlassBorderDark = Color(0xFF3A3A3C)
val SurfaceDark = Color(0xFF1C1C1E)
val OutlineDark = Color(0xFF48484A)

// ── 语义色（REQ 首页UI§16：固定不随主题切换）──
val IncomeGreen = Color(0xFF22A06B)                  // 收入/完成
val ExpenseRed = Color(0xFFE4573D)                   // 普通支出/欠款/错误
val BalanceBlue = Color(0xFF5C9CE6)                  // 正结余/结余组成/结余数字
val RepaymentPurple = Color(0xFF8A73C7)              // 实际还款现金流（正常还款/提前还款）
val ReimbursementYellow = Color(0xFFB77900)          // 报销状态/报销到账（浅色背景上保持可读）
val DeficitRed = Color(0xFFC83A32)                   // 现金赤字（与普通支出红明确区分）
val PendingOrange = Color(0xFFFF9500)                // 待处理/临近到期
val RecurringDebitOrange = Color(0xFFE76F3C)         // 周期代扣/本月待扣（区别待确认金橙与普通支出红）
val InfoBlue = Color(0xFF007AFF)                     // 信息/补扫/系统状态

// ── 负债语义（全局固定）──
val OutstandingDebtRed = ExpenseRed                  // 尚未清偿的具体欠款/本金金额
// 负债构成是分类图，不复用结余蓝、实际还款紫或临近橙；使用独立、克制的类别色。
val CreditAccountDebtColor = Color(0xFFC85345)       // 信用账户普通账款：珊瑚红
val CreditInstallmentDebtColor = Color(0xFF9A5D82)   // 信用分期：灰紫
val LoanPrincipalDebtColor = Color(0xFF5267B2)       // 贷款本金：稳重靛蓝（不是正结余蓝/实际还款紫）
val AccruedChargeDebtColor = Color(0xFF8F4E32)       // 已到期息费：棕红
val ForecastChargeYellow = Color(0xFF9A6700)         // 未入账未来息费：克制深金黄，浅色卡片上保持可读
val PrivacyEmblemPurple = Color(0xFF8464C2)          // 隐私徽记：正常可见状态
val PrivacyEmblemFog = Color(0xFF77777F)             // 隐私徽记：已隐藏/灰雾状态
val TextPrimaryLight = Color(0xFF182420)             // 对齐碳水大王 on_surface #182420
val TextSecondaryLight = Color(0xFF6B7A75)           // 原浅绿色效果图次要文字
val TextPrimaryDark = Color(0xFFF5F5F7)
val TextSecondaryDark = Color(0xFF98989D)

// ── 4 套浅色主题令牌（REQ 主题§1/§12）：对齐碳水大王真实色值，浅绿默认 ──
// 映射：primary→colorScheme.primary，soft→primaryContainer，soft_text→onPrimaryContainer
val ThemePrimaryGreen = Color(0xFF2A806B)
val ThemePrimaryGreenSoft = Color(0xFFF1F7F5)
val ThemePrimaryGreenSoftText = Color(0xFF19634F)

val ThemePrimaryBlue = Color(0xFF438BD1)
val ThemePrimaryBlueSoft = Color(0xFFEEF5FC)
val ThemePrimaryBlueSoftText = Color(0xFF2E6FA9)

val ThemePrimaryViolet = Color(0xFF8464C2)
val ThemePrimaryVioletSoft = Color(0xFFF5F1FA)
val ThemePrimaryVioletSoftText = Color(0xFF664A9A)

val ThemePrimaryWarm = Color(0xFFC4932E)
val ThemePrimaryWarmSoft = Color(0xFFFBF5E9)
val ThemePrimaryWarmSoftText = Color(0xFF8A671C)

val ThemePrimaryLongNest = Color(0xFFC9A24B)         // 哑光古金占位（龙巢最终色值由 Codex 定）
val PrivacyGothicBorderSilver = Color(0xFFD9E0E3)   // 隐秘卡片暗银/雾白主线
val PrivacyGothicBorderMist = Color(0xFF9DA8AE)      // 隐秘卡片切角与内线

/** 全局资金语义映射：页面只提供交易类型，不再各自决定现金流颜色。 */
fun transactionCashFlowColor(type: String): Color = when (type) {
    TransactionType.EXPENSE.name, TransactionType.FEE.name -> ExpenseRed
    TransactionType.LOAN_PAYMENT.name, TransactionType.LOAN_PREPAYMENT.name -> RepaymentPurple
    TransactionType.REIMBURSEMENT.name -> ReimbursementYellow
    TransactionType.INCOME.name, TransactionType.REFUND.name -> IncomeGreen
    else -> TextSecondaryLight
}

fun cashBalanceColor(balanceCents: Long): Color = if (balanceCents >= 0L) BalanceBlue else DeficitRed

/** 全局负债金额语义：有欠款为红色体系，清零后才显示完成绿。 */
fun debtAmountColor(debtCents: Long, debtColor: Color = OutstandingDebtRed): Color =
    if (debtCents > 0L) debtColor else IncomeGreen

/** 全局负债构成映射：分类图使用独立类别色，具体未清偿金额仍由 [debtAmountColor] 显示欠款红。 */
fun debtCompositionSemanticColor(type: String): Color = when (type) {
    "CARD" -> CreditAccountDebtColor
    "CARD_INSTALLMENT" -> CreditInstallmentDebtColor
    "LOAN" -> LoanPrincipalDebtColor
    else -> AccruedChargeDebtColor
}
