package com.assetsking.ledger

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Repayments and statement conversions for the current statement live in [current, next). */
data class CardStatementCycle(
    val currentStatementDate: LocalDate,
    val nextStatementDate: LocalDate,
    val repaymentStartMillis: Long,
    val repaymentEndMillis: Long
) {
    val currentStatementEpochDay: Long get() = currentStatementDate.toEpochDay()
}

fun cardStatementCycle(
    statementDay: Int?,
    today: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault()
): CardStatementCycle {
    fun statementDate(month: YearMonth, day: Int): LocalDate =
        month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    val currentMonth = YearMonth.from(today)
    val day = statementDay ?: 1
    val candidate = statementDate(currentMonth, day)
    val currentStatementMonth = if (candidate.isAfter(today)) currentMonth.minusMonths(1) else currentMonth
    val currentStatement = statementDate(currentStatementMonth, day)
    val nextStatement = statementDate(currentStatementMonth.plusMonths(1), day)
    fun millis(date: LocalDate): Long = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

    return CardStatementCycle(
        currentStatementDate = currentStatement,
        nextStatementDate = nextStatement,
        repaymentStartMillis = millis(currentStatement),
        repaymentEndMillis = millis(nextStatement)
    )
}
