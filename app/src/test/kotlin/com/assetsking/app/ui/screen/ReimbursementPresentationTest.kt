package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ReimbursementPresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `unreimbursed transactions survive natural month changes until fully settled`() {
        val oldPending = tx("old", 10_000L, 0L, 2026, 7, 20)
        val currentCycle = tx("current", 20_000L, 5_000L, 2026, 8, 25)
        val nextCycle = tx("next", 30_000L, 0L, 2026, 8, 26)
        val settled = tx("settled", 40_000L, 40_000L, 2026, 6, 1)

        val pending = outstandingReimbursements(listOf(oldPending, currentCycle, nextCycle, settled))

        assertEquals(listOf("next", "current", "old"), pending.map { it.id })
        assertEquals(55_000L, pending.sumOf { reimbursementRemainingCents(it) })
    }

    @Test
    fun `arrival requires exact selection when outstanding items exist`() {
        assertEquals("待报销款项", reimbursementSelectionError(3, 0, 0L, 30_000L))
        assertEquals("报销金额需与勾选合计一致", reimbursementSelectionError(3, 2, 28_000L, 30_000L))
        assertEquals(null, reimbursementSelectionError(3, 2, 30_000L, 30_000L))
        assertEquals(null, reimbursementSelectionError(0, 0, 0L, 30_000L))
    }

    @Test
    fun `turning off reimbursement immediately changes the visible state`() {
        assertEquals("待报销（到账前仍计入本月支出）", reimbursementToggleLabel(true, false))
        assertEquals("不报销", reimbursementToggleLabel(false, false))
        assertEquals("已报销（需先删除对应报销到账）", reimbursementToggleLabel(false, true))
    }

    @Test
    fun `reimbursement center shows current pending settled and arrival plus carried pending`() {
        val start = LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val currentPending = tx("current-pending", 10_000L, 0L, 2026, 8, 3)
        val currentSettled = tx("current-settled", 20_000L, 20_000L, 2026, 8, 4)
        val arrival = tx("arrival", 20_000L, 0L, 2026, 8, 5).copy(
            type = "REIMBURSEMENT",
            isReimbursable = false
        )
        val oldPending = tx("old-pending", 30_000L, 0L, 2026, 7, 20)

        assertEquals(
            listOf("arrival", "current-settled", "current-pending"),
            reimbursementMonthRecords(
                listOf(oldPending, currentPending, currentSettled, arrival),
                start,
                end
            ).map { it.id }
        )
        assertEquals(
            listOf("old-pending"),
            carriedOutstandingReimbursements(
                listOf(oldPending, currentPending, currentSettled, arrival),
                start,
                end
            ).map { it.id }
        )
    }

    private fun tx(id: String, amount: Long, reimbursed: Long, year: Int, month: Int, day: Int) =
        TransactionEntity(
            id = id,
            accountId = "cash",
            amountCents = amount,
            type = "EXPENSE",
            category = "work",
            occurredAt = LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli(),
            isReimbursable = true,
            reimbursedCents = reimbursed
        )
}
