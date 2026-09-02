package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.RecordStatus
import com.assetsking.model.TransactionType
import kotlin.math.roundToInt

/** 流水连续列表与月历共用的单日资金移动和消费结构口径。 */
internal data class TransactionsDayPresentation(
    val inflowCents: Long,
    val outflowCents: Long,
    val necessaryCents: Long,
    val optionalCents: Long,
    val unclassifiedCents: Long
) {
    val netCents: Long get() = inflowCents - outflowCents
    val spendingCents: Long get() = necessaryCents + optionalCents + unclassifiedCents

    val necessaryPercent: Int?
        get() = spendingCents.takeIf { it > 0L && unclassifiedCents == 0L }?.let {
            ((necessaryCents.toDouble() / it.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        }

    val optionalPercent: Int?
        get() = necessaryPercent?.let { 100 - it }
}

internal fun transactionsDayPresentation(
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    refundTransactions: List<TransactionEntity> = transactions
): TransactionsDayPresentation {
    val confirmed = transactions.filter { it.status == RecordStatus.CONFIRMED.name }
    val refundOffsets = refundTransactions.asSequence()
        .filter {
            it.status == RecordStatus.CONFIRMED.name &&
                it.type == TransactionType.REFUND.name &&
                it.refundOfId != null
        }
        .groupBy { it.refundOfId!! }
        .mapValues { (_, refunds) -> refunds.sumOf { it.amountCents } }

    var necessaryCents = 0L
    var optionalCents = 0L
    var unclassifiedCents = 0L
    confirmed.asSequence()
        .filter { it.type == TransactionType.EXPENSE.name || it.type == TransactionType.FEE.name }
        .forEach { expense ->
            val netCents = (
                expense.amountCents -
                    expense.reimbursedCents -
                    (refundOffsets[expense.id] ?: 0L)
                ).coerceAtLeast(0L)
            when (effectiveNecessity(expense, categories)) {
                true -> necessaryCents += netCents
                false -> optionalCents += netCents
                null -> unclassifiedCents += netCents
            }
        }

    return TransactionsDayPresentation(
        inflowCents = confirmed.asSequence()
            .filter {
                it.type == TransactionType.INCOME.name ||
                    it.type == TransactionType.REFUND.name ||
                    it.type == TransactionType.REIMBURSEMENT.name ||
                    it.type == TransactionType.LOAN_DISBURSEMENT.name
            }
            .sumOf { it.amountCents },
        outflowCents = confirmed.asSequence()
            .filter {
                it.type == TransactionType.EXPENSE.name ||
                    it.type == TransactionType.FEE.name ||
                    it.type == TransactionType.LOAN_PAYMENT.name ||
                    it.type == TransactionType.LOAN_PREPAYMENT.name
            }
            .sumOf { it.amountCents },
        necessaryCents = necessaryCents,
        optionalCents = optionalCents,
        unclassifiedCents = unclassifiedCents
    )
}

private fun effectiveNecessity(
    transaction: TransactionEntity,
    categories: List<CategoryEntity>
): Boolean? = transaction.necessity ?: categories.firstOrNull {
    !it.isArchived && (it.id == transaction.category || it.name == transaction.category)
}?.defaultNecessary

internal fun transactionSpendingNecessity(
    transaction: TransactionEntity,
    category: CategoryEntity?
): Boolean? = if (
    transaction.type == TransactionType.EXPENSE.name ||
    transaction.type == TransactionType.FEE.name
) {
    transaction.necessity ?: category?.takeUnless { it.isArchived }?.defaultNecessary
} else {
    null
}
