package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.recurringOccursOn
import com.assetsking.model.TransactionType
import java.time.Instant
import java.time.ZoneId

internal const val RECURRING_DEBIT_LABEL = "周期扣款"
internal const val RECURRING_AMOUNT_TOLERANCE_PERCENT = 20

internal fun recurringAmountMatches(expectedCents: Long, actualCents: Long): Boolean =
    expectedCents > 0L && kotlin.math.abs(actualCents - expectedCents) * 100 <= expectedCents * RECURRING_AMOUNT_TOLERANCE_PERCENT

/** 周期规则只是普通支出的业务身份；未来计划和周期收入不打“周期扣款”标。 */
internal fun isRecurringDebit(transaction: TransactionEntity): Boolean =
    transaction.status == "CONFIRMED" &&
        transaction.recurringRuleId != null && transaction.type == TransactionType.EXPENSE.name

/**
 * 周期规则只描述计划；实际扣款的账户、渠道、平台和商户可能到发生时才知道。
 * 因此人工认领候选只看：已确认、未被其他规则认领、同类型、预计金额 ±20%、同一个本地自然日。
 */
internal fun recurringMatchCandidates(
    rule: RecurringRuleEntity,
    transactions: List<TransactionEntity>,
    zone: ZoneId = ZoneId.systemDefault()
): List<TransactionEntity> = transactions
    .asSequence()
    .filter { transaction ->
        transaction.status == "CONFIRMED" &&
            transaction.deletedAt == null &&
            transaction.recurringRuleId == null &&
            transaction.type == rule.type &&
            recurringAmountMatches(rule.amountCents, transaction.amountCents) &&
            recurringOccursOn(rule, transaction.occurredAt, zone)
    }
    .sortedWith(compareBy<TransactionEntity> {
        kotlin.math.abs(it.amountCents - rule.amountCents)
    }.thenByDescending { it.occurredAt })
    .toList()

internal fun recurringSameLocalDate(first: Long, second: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(second).atZone(zone).toLocalDate()

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
