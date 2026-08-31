package com.assetsking.database

import com.assetsking.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurringScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun monthlyThirtyFirstFallsBackInSeptemberAndReturnsInOctober() {
        val august31 = at("2026-08-31")
        val rule = RecurringRuleEntity(
            id = "monthly-31",
            accountId = "",
            amountCents = 8_800L,
            type = TransactionType.EXPENSE.name,
            category = "housing-rent",
            merchant = null,
            note = "房租",
            interval = "MONTHLY",
            nextRunAt = august31,
            firstRunAt = august31
        )

        val september30 = at("2026-09-30")
        val october31 = at("2026-10-31")
        assertTrue(recurringOccursOn(rule, september30, zone))
        assertEquals(october31, recurringNextRunAt(rule, september30, zone))
        assertTrue(recurringOccursOn(rule, october31, zone))
    }

    private fun at(date: String): Long = LocalDate.parse(date)
        .atTime(9, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}
