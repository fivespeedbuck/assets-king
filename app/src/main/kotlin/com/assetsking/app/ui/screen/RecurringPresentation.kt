package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.model.TransactionType

internal const val RECURRING_DEBIT_LABEL = "周期扣款"

/** 周期规则只是普通支出的业务身份；未来计划和周期收入不打“周期扣款”标。 */
internal fun isRecurringDebit(transaction: TransactionEntity): Boolean =
    transaction.status == "CONFIRMED" &&
        transaction.recurringRuleId != null && transaction.type == TransactionType.EXPENSE.name

internal fun pendingDeductionRules(
    rules: List<RecurringRuleEntity>,
    transactions: List<TransactionEntity>,
    start: Long,
    end: Long
): List<RecurringRuleEntity> = rules.filter { rule ->
    rule.isActive && rule.type == TransactionType.EXPENSE.name &&
        rule.nextRunAt in start..end &&
        transactions.none { tx -> isRecurringDebit(tx) && tx.recurringRuleId == rule.id && tx.occurredAt in start..end }
}.sortedBy { it.nextRunAt }

internal data class RecurringDebitMonthSummary(
    val pendingRules: List<RecurringRuleEntity>,
    val claimedTransactions: List<TransactionEntity>
) {
    val pendingCents: Long get() = pendingRules.sumOf { it.amountCents }
    val deductedCents: Long get() = claimedTransactions.sumOf { it.amountCents }
}

/** 首页卡和周期账单页共用同一月度口径；删除规则不抹掉历史真实扣款的审计身份。 */
internal fun recurringDebitMonthSummary(
    rules: List<RecurringRuleEntity>,
    transactions: List<TransactionEntity>,
    start: Long,
    end: Long
): RecurringDebitMonthSummary {
    val monthTransactions = transactions.filter { it.occurredAt in start..end }
    return RecurringDebitMonthSummary(
        pendingRules = pendingDeductionRules(rules, monthTransactions, start, end),
        claimedTransactions = monthTransactions.filter(::isRecurringDebit).sortedBy { it.occurredAt }
    )
}
