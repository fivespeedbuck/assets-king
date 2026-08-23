package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal enum class CreditFlowScope(val label: String) {
    STATEMENT("本期账单"),
    UNBILLED("未出账"),
    ALL("全部")
}

internal data class CreditCycleWindow(
    val statementStart: LocalDate,
    val statementEnd: LocalDate,
    val unbilledStart: LocalDate,
    val unbilledEnd: LocalDate
)

internal fun creditCycleWindow(statementDay: Int?, today: LocalDate): CreditCycleWindow? {
    val day = statementDay?.takeIf { it in 1..31 } ?: return null
    fun statementDate(month: YearMonth): LocalDate = month.atDay(day.coerceAtMost(month.lengthOfMonth()))

    val thisMonth = YearMonth.from(today)
    val thisMonthStatement = statementDate(thisMonth)
    val latestStatement = if (thisMonthStatement.isAfter(today)) {
        statementDate(thisMonth.minusMonths(1))
    } else {
        thisMonthStatement
    }
    val previousStatement = statementDate(YearMonth.from(latestStatement).minusMonths(1))
    return CreditCycleWindow(
        statementStart = previousStatement.plusDays(1),
        statementEnd = latestStatement,
        unbilledStart = latestStatement.plusDays(1),
        unbilledEnd = today
    )
}

internal fun creditFlowTransactions(
    accountId: String,
    transactions: List<TransactionEntity>,
    scope: CreditFlowScope,
    statementDay: Int?,
    today: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
    statementOutstandingCents: Long? = null
): List<TransactionEntity> {
    val accountTransactions = transactions.filter { it.accountId == accountId && it.status == "CONFIRMED" }
    val window = creditCycleWindow(statementDay, today)
        ?: return if (scope == CreditFlowScope.ALL) accountTransactions.sortedByDescending { it.occurredAt } else emptyList()
    val range = when (scope) {
        CreditFlowScope.STATEMENT -> window.statementStart..window.statementEnd
        CreditFlowScope.UNBILLED -> window.unbilledStart..window.unbilledEnd
        CreditFlowScope.ALL -> window.statementStart..window.unbilledEnd
    }
    return accountTransactions
        .filter { transaction ->
            val date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate()
            val settledStatement = statementOutstandingCents == 0L &&
                date in window.statementStart..window.statementEnd
            date in range && !settledStatement
        }
        .sortedByDescending { it.occurredAt }
}

internal fun creditCycleRangeLabel(scope: CreditFlowScope, window: CreditCycleWindow): String = when (scope) {
    CreditFlowScope.STATEMENT -> "${window.statementStart.monthValue}月${window.statementStart.dayOfMonth}日—${window.statementEnd.monthValue}月${window.statementEnd.dayOfMonth}日"
    CreditFlowScope.UNBILLED -> "${window.unbilledStart.monthValue}月${window.unbilledStart.dayOfMonth}日—${window.unbilledEnd.monthValue}月${window.unbilledEnd.dayOfMonth}日"
    CreditFlowScope.ALL -> "当前未还账款"
}
