package com.assetsking.usecase

import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.model.AccountType
import com.assetsking.model.RecordStatus
import com.assetsking.model.TransactionType
import java.time.YearMonth
import java.time.ZoneId

/**
 * 同一时间窗口内的现金流口径。
 * 普通支出与贷款还款分开呈现；结余同时扣除两者，未来还款计划不在流水中，因此不会误计。
 */
data class CashFlowSummary(
    val incomeCents: Long,
    val expenseCents: Long,
    val repaymentCents: Long
) {
    val balanceCents: Long get() = incomeCents - expenseCents - repaymentCents
}

fun cashFlowSummary(
    transactions: List<TransactionEntity>,
    transfers: List<TransferEntity> = emptyList(),
    accounts: List<AccountEntity> = emptyList()
): CashFlowSummary {
    val confirmedTransactions = transactions.filter { it.status == RecordStatus.CONFIRMED.name }
    val transactionIds = confirmedTransactions.mapTo(HashSet()) { it.id }
    val refundOffset = confirmedTransactions
        .filter {
            it.type == TransactionType.REFUND.name &&
                it.refundOfId != null &&
                it.refundOfId in transactionIds
        }
        .sumOf { it.amountCents }
    val reimbursementOffset = confirmedTransactions
        .filter { it.type == TransactionType.EXPENSE.name }
        .sumOf { it.reimbursedCents }
    val ordinaryExpense = confirmedTransactions
        .filter { it.type == TransactionType.EXPENSE.name || it.type == TransactionType.FEE.name }
        .sumOf { it.amountCents }

    val accountsById = accounts.associateBy { it.id }
    val creditCardRepayment = transfers.filter { transfer ->
        transfer.amountCents > 0L &&
            accountsById[transfer.fromAccountId]?.let { it.type == AccountType.ASSET.name && !it.archived } == true &&
            accountsById[transfer.toAccountId]?.let { it.type == AccountType.CREDIT.name && !it.archived } == true
    }.sumOf { it.amountCents }

    return CashFlowSummary(
        incomeCents = confirmedTransactions.filter { it.type == TransactionType.INCOME.name }.sumOf { it.amountCents },
        expenseCents = (ordinaryExpense - refundOffset - reimbursementOffset).coerceAtLeast(0L),
        repaymentCents = confirmedTransactions
            .filter {
                it.type == TransactionType.LOAN_PAYMENT.name ||
                    it.type == TransactionType.LOAN_PREPAYMENT.name
            }
            .sumOf { it.amountCents } + creditCardRepayment
    )
}

fun cashFlowSummaryForMonth(
    transactions: List<TransactionEntity>,
    month: YearMonth,
    zoneId: ZoneId = ZoneId.systemDefault(),
    transfers: List<TransferEntity> = emptyList(),
    accounts: List<AccountEntity> = emptyList()
): CashFlowSummary {
    val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endExclusive = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    return cashFlowSummary(
        transactions = transactions.filter { it.occurredAt in start until endExclusive },
        transfers = transfers.filter { it.occurredAt in start until endExclusive },
        accounts = accounts
    )
}
