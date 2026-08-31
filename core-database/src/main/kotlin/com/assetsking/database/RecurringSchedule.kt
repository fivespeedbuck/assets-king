package com.assetsking.database

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 周期规则的日期锚点始终来自首次运行日；短月按该月最后一天执行，之后恢复原始日号。
 * 例如 8 月 31 日的月度规则：9 月 30 日、10 月 31 日，而不是永久漂移到每月 30 日。
 */
fun recurringOccursOn(
    rule: RecurringRuleEntity,
    occurredAt: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Boolean {
    val anchor = recurringAnchorAt(rule, zone)
    val date = Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate()
    if (date.isBefore(anchor.toLocalDate())) return false
    return recurringRunAtForDate(rule, date, zone) != null
}

fun recurringNextRunAt(
    rule: RecurringRuleEntity,
    from: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val anchor = recurringAnchorAt(rule, zone)
    val current = Instant.ofEpochMilli(from).atZone(zone)
    return when (rule.interval) {
        "DAILY" -> current.plusDays(1)
        "WEEKLY" -> current.plusWeeks(1)
        "MONTHLY" -> recurringMonthRun(anchor, YearMonth.from(current).plusMonths(1), zone)
        "QUARTERLY" -> recurringMonthRun(anchor, YearMonth.from(current).plusMonths(3), zone)
        "YEARLY" -> recurringMonthRun(anchor, YearMonth.from(current).plusYears(1), zone)
        else -> current.plusMonths(1)
    }.toInstant().toEpochMilli()
}

/** Returns the scheduled instant for [date], or null when [date] is not an occurrence of this rule. */
fun recurringRunAtForDate(
    rule: RecurringRuleEntity,
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): Long? {
    val anchor = recurringAnchorAt(rule, zone)
    val anchorDate = anchor.toLocalDate()
    val matching = when (rule.interval) {
        "DAILY" -> !date.isBefore(anchorDate)
        "WEEKLY" -> !date.isBefore(anchorDate) &&
            java.time.temporal.ChronoUnit.DAYS.between(anchorDate, date) % 7L == 0L
        "MONTHLY" -> matchesAnchoredMonth(anchorDate, date, 1)
        "QUARTERLY" -> matchesAnchoredMonth(anchorDate, date, 3)
        "YEARLY" -> matchesAnchoredMonth(anchorDate, date, 12)
        else -> false
    }
    if (!matching) return null
    return date.atTime(anchor.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
}

private fun recurringAnchorAt(rule: RecurringRuleEntity, zone: ZoneId) =
    Instant.ofEpochMilli(rule.firstRunAt.takeIf { it > 0L } ?: rule.nextRunAt).atZone(zone)

private fun matchesAnchoredMonth(anchor: LocalDate, date: LocalDate, stepMonths: Long): Boolean {
    val months = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(date))
    if (months < 0L || months % stepMonths != 0L) return false
    return date.dayOfMonth == minOf(anchor.dayOfMonth, date.lengthOfMonth())
}

private fun recurringMonthRun(anchor: java.time.ZonedDateTime, month: YearMonth, zone: ZoneId) =
    month.atDay(minOf(anchor.dayOfMonth, month.lengthOfMonth())).atTime(anchor.toLocalTime()).atZone(zone)
