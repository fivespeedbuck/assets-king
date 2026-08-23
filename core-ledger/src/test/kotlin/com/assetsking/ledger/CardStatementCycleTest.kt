package com.assetsking.ledger

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CardStatementCycleTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun derivesTheCurrentRepaymentWindow() {
        val cycle = cardStatementCycle(8, LocalDate.of(2026, 8, 23), zone)

        assertEquals(LocalDate.of(2026, 8, 8), cycle.currentStatementDate)
        assertEquals(LocalDate.of(2026, 9, 8), cycle.nextStatementDate)
    }

    @Test
    fun monthEndStatementDayDoesNotDriftAfterFebruary() {
        val february = cardStatementCycle(31, LocalDate.of(2026, 2, 28), zone)
        val march = cardStatementCycle(31, LocalDate.of(2026, 3, 31), zone)

        assertEquals(LocalDate.of(2026, 2, 28), february.currentStatementDate)
        assertEquals(LocalDate.of(2026, 3, 31), february.nextStatementDate)
        assertEquals(LocalDate.of(2026, 3, 31), march.currentStatementDate)
    }
}
