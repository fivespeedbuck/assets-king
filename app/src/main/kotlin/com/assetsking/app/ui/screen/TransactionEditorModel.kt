package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LendingPlanEntity
import com.assetsking.database.LendingPlanStatus
import com.assetsking.database.TransactionEntity
import com.assetsking.ledger.AmountExpression
import com.assetsking.ledger.OrderPlatform
import com.assetsking.ledger.PaymentChannel
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import com.assetsking.ui.format.formatMoney
import java.util.Locale
import kotlin.math.roundToLong

// 编辑器领域模型与纯函数：独立编译边界，避免主 Composable 的 FIR 数据流分析膨胀。

internal data class RefundSourceCandidate(
    val transaction: TransactionEntity,
    val remainingCents: Long
)

internal fun refundSourceCandidates(
    transactions: List<TransactionEntity>,
    accountId: String,
    refundAmountCents: Long,
    refundOccurredAt: Long,
    editingRefundId: String? = null,
    merchantName: String? = null,
    refundChannel: String? = null,
    refundOrderPlatform: String? = null
): List<RefundSourceCandidate> {
    val requiredMerchant = merchantName?.trim().orEmpty()
    val requiredChannel = refundChannel?.trim().orEmpty()
    val requiredOrderPlatform = refundOrderPlatform?.trim().orEmpty()
    if (requiredMerchant.isBlank()) return emptyList()

    val refundedByExpense = transactions
        .filter {
            it.id != editingRefundId &&
                it.status == "CONFIRMED" &&
                it.type == TransactionType.REFUND.name &&
                it.refundOfId != null
        }
        .groupBy { requireNotNull(it.refundOfId) }
        .mapValues { (_, refunds) -> refunds.sumOf { it.amountCents } }

    return transactions.asSequence()
        .filter {
                it.status == "CONFIRMED" &&
                it.type == TransactionType.EXPENSE.name &&
                it.accountId == accountId &&
                it.merchant?.trim()?.equals(requiredMerchant, ignoreCase = true) == true &&
                PaymentChannel.refundSourceCompatible(it.channel, requiredChannel) &&
                OrderPlatform.refundSourceCompatible(it.orderPlatform, requiredOrderPlatform) &&
                it.occurredAt <= refundOccurredAt
        }
        .map { expense ->
            RefundSourceCandidate(
                transaction = expense,
                remainingCents = (expense.amountCents - refundedByExpense.getOrDefault(expense.id, 0L)).coerceAtLeast(0L)
            )
        }
        .filter { it.remainingCents > 0L && (refundAmountCents <= 0L || refundAmountCents <= it.remainingCents) }
        .sortedWith(
            compareByDescending<RefundSourceCandidate> { refundAmountCents > 0L && it.remainingCents == refundAmountCents }
                .thenByDescending { it.transaction.occurredAt }
        )
        .take(30)
        .toList()
}

// ── 编辑器顶层类型（REQ 编辑器§11）──
internal enum class EditorKind(val label: String) { EXPENSE("支出"), INCOME("入账"), TRANSFER("划转"), REPAY("还款"), LENDING("借出/收回") }
internal enum class IncomeSub(val label: String, val type: TransactionType) {
    INCOME("收入", TransactionType.INCOME),
    REFUND("退款", TransactionType.REFUND),
    REIMBURSEMENT("报销到账", TransactionType.REIMBURSEMENT),
    LOAN_DISBURSEMENT("借款到账", TransactionType.LOAN_DISBURSEMENT)
}
internal enum class RepaySub(val label: String) { CREDIT_CARD("信用卡还款"), LOAN("贷款还款") }
internal enum class LendingSub(val label: String) { DISBURSEMENT("借出本金"), REPAYMENT("收回本金/利息") }
internal enum class BalanceResolution { NOTIFICATION, CURRENT_LEDGER }

internal data class PendingTransferAccounts(val fromAccountId: String, val toAccountId: String)

internal fun pendingTransferAccounts(isExpense: Boolean?, evidenceAccountId: String): PendingTransferAccounts =
    when (isExpense) {
        true -> PendingTransferAccounts(fromAccountId = evidenceAccountId, toAccountId = "")
        false -> PendingTransferAccounts(fromAccountId = "", toAccountId = evidenceAccountId)
        null -> PendingTransferAccounts(fromAccountId = "", toAccountId = "")
    }

internal fun pendingTransferEvidenceAccountId(
    isExpense: Boolean?,
    fromAccountId: String,
    toAccountId: String
): String = when (isExpense) {
    true -> fromAccountId
    false -> toAccountId
    null -> ""
}

internal fun isOrdinaryEditableTransaction(type: TransactionType): Boolean =
    type == TransactionType.EXPENSE || type == TransactionType.INCOME ||
        type == TransactionType.REFUND || type == TransactionType.REIMBURSEMENT

internal fun loanPaymentSplitDifferenceCents(
    totalCents: Long,
    principalCents: Long?,
    interestCents: Long?,
    feeCents: Long?
): Long? {
    if (totalCents <= 0L || principalCents == null || interestCents == null || feeCents == null) return null
    if (principalCents < 0L || interestCents < 0L || feeCents < 0L) return null
    val splitTotal = runCatching {
        Math.addExact(Math.addExact(principalCents, interestCents), feeCents)
    }.getOrNull() ?: return null
    return runCatching { Math.subtractExact(totalCents, splitTotal) }.getOrNull()
}

internal fun lendingSplitDifferenceCents(
    totalCents: Long,
    principalCents: Long,
    interestCents: Long
): Long? = loanPaymentSplitDifferenceCents(totalCents, principalCents, interestCents, 0L)

internal fun lendingValidationErrors(
    amountCents: Long,
    selectedPlan: LendingPlanEntity?,
    isAssetAccount: Boolean,
    sub: LendingSub,
    principalCents: Long?,
    splitDifferenceCents: Long?
): List<String> = buildList {
    if (!isAssetAccount) add("资金账户")
    if (selectedPlan == null) add("出借计划")
    when (sub) {
        LendingSub.DISBURSEMENT -> if (selectedPlan != null) {
            if (selectedPlan.status != LendingPlanStatus.PENDING_DISBURSEMENT) add("待借出计划")
            if (amountCents != selectedPlan.principalCents) add("借出金额须等于计划本金")
        }
        LendingSub.REPAYMENT -> {
            if (selectedPlan != null && selectedPlan.status != LendingPlanStatus.ACTIVE) add("进行中的出借计划")
            if (amountCents > 0L && splitDifferenceCents != 0L) add("本金与利息合计")
            if (principalCents != null && selectedPlan != null && principalCents > selectedPlan.remainingPrincipalCents) {
                add("收回本金不能超过剩余应收")
            }
        }
    }
}

internal fun editableMoney(cents: Long): String = String.format(Locale.US, "%.2f", cents / 100.0)

internal fun editableMoneyCents(expression: String): Long? {
    val amount = expression.takeIf { it.isNotBlank() }?.let(AmountExpression::evaluate) ?: return null
    if (!amount.isFinite() || amount < 0.0 || amount > Long.MAX_VALUE / 100.0) return null
    return (amount * 100).roundToLong()
}

/** 必填校验独立成纯函数，避免把大型类型分支和编辑器全部状态放进同一个 FIR 分析边界。 */
internal fun editorMissingFields(
    amountCents: Long,
    pendingItemPresent: Boolean,
    directionChosen: Boolean,
    kind: EditorKind,
    selectedAccount: AccountEntity?,
    merchantText: String,
    selectedCategory: CategoryEntity?,
    incomeSub: IncomeSub,
    editingTransaction: TransactionEntity?,
    lendingPlanId: String?,
    loanPlanId: String?,
    selectableReimbursableCount: Int,
    selectedExpenseCount: Int,
    selectedReimbursementCents: Long,
    repaySub: RepaySub,
    accountId: String,
    toAccountId: String,
    transferFeeExpr: String,
    evaluatedTransferFee: Double?,
    transferFeeCents: Long,
    loanSplitDifference: Long?,
    lendingSub: LendingSub,
    selectedLendingPlan: LendingPlanEntity?,
    principalCents: Long?,
    lendingSplitDifference: Long?,
    accountTailConflict: Boolean,
    parsedCardTail: String?,
    balanceConflict: Boolean,
    balanceResolution: BalanceResolution?,
    assetBalanceShortfallCents: Long
): List<String> = buildList {
    if (amountCents <= 0) add("金额")
    if (pendingItemPresent && !directionChosen) add("方向")
    when (kind) {
        EditorKind.EXPENSE -> {
            if (selectedAccount == null) add("账户")
            if (merchantText.isBlank()) add("商户")
            if (selectedCategory == null) add("分类")
        }
        EditorKind.INCOME -> {
            if (selectedAccount == null) add("账户")
            if (incomeSub == IncomeSub.LOAN_DISBURSEMENT && selectedAccount?.type != AccountType.ASSET.name) {
                add("资产账户")
            }
            if (merchantText.isBlank() && !(editingTransaction != null && incomeSub == IncomeSub.INCOME && lendingPlanId != null)) {
                add(if (incomeSub == IncomeSub.LOAN_DISBURSEMENT) "借款来源" else "收入来源")
            }
            if (selectedCategory == null && incomeSub == IncomeSub.INCOME && !(editingTransaction != null && lendingPlanId != null)) add("收入分类")
            if (incomeSub == IncomeSub.LOAN_DISBURSEMENT && loanPlanId == null) add("贷款计划")
            if (incomeSub == IncomeSub.REIMBURSEMENT && selectableReimbursableCount > 0) {
                reimbursementSelectionError(
                    outstandingCount = selectableReimbursableCount,
                    selectedCount = selectedExpenseCount,
                    selectedCents = selectedReimbursementCents,
                    arrivalCents = amountCents
                )?.let(::add)
            }
        }
        EditorKind.TRANSFER -> {
            if (accountId.isBlank() || toAccountId.isBlank()) add("转出/转入账户")
            if (accountId == toAccountId) add("转出与转入账户不能相同")
            if (transferFeeExpr.isNotBlank() && (evaluatedTransferFee == null || transferFeeCents < 0)) add("手续费")
        }
        EditorKind.REPAY -> {
            if (accountId.isBlank()) add("付款账户")
            if (repaySub == RepaySub.CREDIT_CARD && toAccountId.isBlank()) add("信用卡")
            if (repaySub == RepaySub.LOAN && loanPlanId == null) add("贷款计划")
            if (repaySub == RepaySub.LOAN && amountCents > 0L && loanSplitDifference != 0L) add("本金、利息与费用合计")
        }
        EditorKind.LENDING -> addAll(
            lendingValidationErrors(
                amountCents = amountCents,
                selectedPlan = selectedLendingPlan,
                isAssetAccount = selectedAccount?.type == AccountType.ASSET.name,
                sub = lendingSub,
                principalCents = principalCents,
                splitDifferenceCents = lendingSplitDifference
            )
        )
    }
    if (accountTailConflict) add("资金账户（银行尾号 $parsedCardTail）")
    else if (balanceConflict && balanceResolution == null) add("余额对账选择")
    if (assetBalanceShortfallCents > 0L && balanceResolution != BalanceResolution.NOTIFICATION) {
        add("余额不足（还差 ${formatMoney(assetBalanceShortfallCents)}）")
    }
}

internal fun initialEditorKind(
    savedKind: String?,
    initialLoanPlanId: String?,
    editingType: String?,
    parsedIsExpense: Boolean?
): EditorKind = savedKind?.let { runCatching { EditorKind.valueOf(it) }.getOrNull() }
    ?: when {
        initialLoanPlanId != null -> EditorKind.REPAY
        editingType == TransactionType.INCOME.name ||
            editingType == TransactionType.REFUND.name ||
            editingType == TransactionType.REIMBURSEMENT.name ||
            editingType == TransactionType.LOAN_DISBURSEMENT.name -> EditorKind.INCOME
        parsedIsExpense == false -> EditorKind.INCOME
        else -> EditorKind.EXPENSE
    }

internal fun initialIncomeSub(
    savedSub: String?,
    editingType: String?,
    parsedIsRefund: Boolean
): IncomeSub = savedSub?.let { runCatching { IncomeSub.valueOf(it) }.getOrNull() }
    ?: when {
        editingType == TransactionType.LOAN_DISBURSEMENT.name -> IncomeSub.LOAN_DISBURSEMENT
        editingType == TransactionType.REIMBURSEMENT.name -> IncomeSub.REIMBURSEMENT
        editingType == TransactionType.REFUND.name || parsedIsRefund -> IncomeSub.REFUND
        else -> IncomeSub.INCOME
    }

internal fun visibleEditorKinds(
    editingReimbursement: Boolean,
    editingLending: Boolean,
    editingIncome: Boolean,
    editingAny: Boolean
): List<EditorKind> = when {
    editingReimbursement || editingLending -> listOf(EditorKind.INCOME)
    editingIncome -> listOf(EditorKind.EXPENSE, EditorKind.INCOME, EditorKind.LENDING)
    editingAny -> listOf(EditorKind.EXPENSE, EditorKind.INCOME)
    else -> EditorKind.entries
}

internal fun visibleEditorIncomeSubs(
    editingReimbursement: Boolean,
    editingLending: Boolean,
    editingAny: Boolean
): List<IncomeSub> = when {
    editingReimbursement -> listOf(IncomeSub.REIMBURSEMENT)
    editingLending -> listOf(IncomeSub.INCOME)
    editingAny -> listOf(IncomeSub.INCOME, IncomeSub.REFUND, IncomeSub.LOAN_DISBURSEMENT)
    else -> IncomeSub.entries
}

internal fun editorTransactionType(
    kind: EditorKind,
    incomeSub: IncomeSub,
    repaySub: RepaySub,
    lendingSub: LendingSub
): TransactionType? = when (kind) {
    EditorKind.EXPENSE -> TransactionType.EXPENSE
    EditorKind.INCOME -> incomeSub.type
    EditorKind.LENDING -> if (lendingSub == LendingSub.DISBURSEMENT) TransactionType.EXPENSE else TransactionType.INCOME
    EditorKind.REPAY -> if (repaySub == RepaySub.LOAN) TransactionType.LOAN_PAYMENT else null
    EditorKind.TRANSFER -> null
}
