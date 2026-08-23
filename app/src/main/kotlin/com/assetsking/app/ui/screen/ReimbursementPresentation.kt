package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity

internal enum class ReimbursementBadge(val label: String) {
    PENDING("待报销"),
    SETTLED("已报销"),
    ARRIVAL("报销到账")
}

internal fun reimbursementBadge(transaction: TransactionEntity): ReimbursementBadge? = when {
    transaction.status != "CONFIRMED" -> null
    transaction.type == "REIMBURSEMENT" -> ReimbursementBadge.ARRIVAL
    transaction.isReimbursable && reimbursementRemainingCents(transaction) > 0L -> ReimbursementBadge.PENDING
    transaction.isReimbursable -> ReimbursementBadge.SETTLED
    else -> null
}

internal fun matchesReimbursementFilter(
    transaction: TransactionEntity,
    filter: ReimbursementBadge?
): Boolean = filter == null || reimbursementBadge(transaction) == filter

internal fun reimbursementRemainingCents(transaction: TransactionEntity): Long =
    (transaction.amountCents - transaction.reimbursedCents).coerceAtLeast(0L)

internal fun outstandingReimbursements(transactions: List<TransactionEntity>): List<TransactionEntity> =
    transactions
        .asSequence()
        .filter { it.status == "CONFIRMED" && it.isReimbursable && reimbursementRemainingCents(it) > 0L }
        .sortedByDescending { it.occurredAt }
        .toList()

internal fun reimbursementSelectionError(
    outstandingCount: Int,
    selectedCount: Int,
    selectedCents: Long,
    arrivalCents: Long
): String? = when {
    outstandingCount <= 0 -> null
    selectedCount <= 0 -> "待报销款项"
    selectedCents != arrivalCents -> "报销金额需与勾选合计一致"
    else -> null
}
