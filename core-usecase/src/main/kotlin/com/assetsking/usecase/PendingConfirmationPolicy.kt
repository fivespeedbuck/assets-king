package com.assetsking.usecase

import com.assetsking.ledger.BalanceMath
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType

/**
 * 待确认箱快捷/批量确认的纯校验规则。
 *
 * 预填只是建议，只有所有必填字段明确且余额校验通过时才允许直接落账。
 * 编辑器可以在用户补全字段后复用同一条余额冲突判断。
 */
enum class PendingConfirmationField {
    AMOUNT,
    DIRECTION,
    ACCOUNT,
    CATEGORY,
    MERCHANT,
    BALANCE
}

data class PendingConfirmationInput(
    val amountCents: Long?,
    val isExpense: Boolean?,
    val isRefund: Boolean,
    val accountId: String?,
    val category: String?,
    val merchant: String?,
    val accountType: AccountType?,
    val accountCardTail: String?,
    val bankCardTail: String?,
    val currentBalanceCents: Long?,
    val bankBalanceCents: Long?
)

data class PendingConfirmationValidation(
    val type: TransactionType?,
    val missing: Set<PendingConfirmationField>
) {
    val canConfirm: Boolean get() = missing.isEmpty()
}

object PendingConfirmationPolicy {
    /** 未明确方向时返回 null，禁止调用方把它静默当成支出。 */
    fun typeFor(isExpense: Boolean?, isRefund: Boolean): TransactionType? = when {
        isExpense == null -> null
        isRefund -> TransactionType.REFUND
        isExpense -> TransactionType.EXPENSE
        else -> TransactionType.INCOME
    }

    fun validate(input: PendingConfirmationInput): PendingConfirmationValidation {
        val type = typeFor(input.isExpense, input.isRefund)
        val missing = buildSet {
            if (input.amountCents == null || input.amountCents <= 0) {
                add(PendingConfirmationField.AMOUNT)
            }
            if (type == null) add(PendingConfirmationField.DIRECTION)
            if (input.accountId.isNullOrBlank()) add(PendingConfirmationField.ACCOUNT)
            if (input.category.isNullOrBlank() || input.category == "UNCATEGORIZED" || input.category == "待分类") {
                add(PendingConfirmationField.CATEGORY)
            }
            if (input.merchant.isNullOrBlank()) add(PendingConfirmationField.MERCHANT)
            if (balanceConflict(
                    type = type,
                    amountCents = input.amountCents,
                    accountType = input.accountType,
                    accountCardTail = input.accountCardTail,
                    bankCardTail = input.bankCardTail,
                    currentBalanceCents = input.currentBalanceCents,
                    bankBalanceCents = input.bankBalanceCents
                )
            ) {
                add(PendingConfirmationField.BALANCE)
            }
        }
        return PendingConfirmationValidation(type, missing)
    }

    /**
     * 只有带卡号尾号的银行余额才能和具体账户做校验；无尾号的余额不作为冲突证据。
     * 信用卡/贷款余额沿用现有账户口径，不把银行短信中的“余额”当成可用额度计算。
     */
    fun balanceConflict(
        type: TransactionType?,
        amountCents: Long?,
        accountType: AccountType?,
        accountCardTail: String?,
        bankCardTail: String?,
        currentBalanceCents: Long?,
        bankBalanceCents: Long?
    ): Boolean {
        if (type == null || amountCents == null || amountCents <= 0 || accountType == null) return false
        if (bankBalanceCents == null || bankCardTail == null) return false
        if (accountCardTail != bankCardTail) return true
        if (accountType != AccountType.ASSET || currentBalanceCents == null) return false
        val delta = BalanceMath.transactionDelta(accountType, type, amountCents)
        return !BalanceMath.checkBalance(currentBalanceCents, delta, bankBalanceCents).matches
    }
}
