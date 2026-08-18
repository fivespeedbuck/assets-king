package com.assetsking.ledger

import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallmentMatcherTest {

    private fun inst(number: Int, dueDay: Long, principal: Long, interest: Long = 0, fee: Long = 0, status: InstallmentStatus = InstallmentStatus.UPCOMING) =
        LoanInstallment(
            number = number,
            dueDateEpochDay = dueDay,
            principal = Money(principal),
            interest = Money(interest),
            fee = Money(fee),
            status = status
        )

    @Test
    fun `exact total match prefers nearest due date`() {
        val list = listOf(
            inst(1, dueDay = 100, principal = 50_000, interest = 300),
            inst(2, dueDay = 200, principal = 50_000, interest = 300),
        )
        val m = InstallmentMatcher.match(list, amountCents = 50_300, atEpochDay = 180)
        assertEquals(2, m?.number)
    }

    @Test
    fun `no exact match picks closest total`() {
        val list = listOf(
            inst(1, dueDay = 100, principal = 50_000, interest = 300),
            inst(2, dueDay = 200, principal = 48_000, interest = 100),
        )
        // 扣款 50_350（手续费差异 50）：最近的是 50_300
        val m = InstallmentMatcher.match(list, amountCents = 50_350, atEpochDay = 150)
        assertEquals(1, m?.number)
    }

    @Test
    fun `paid installments are excluded`() {
        val list = listOf(
            inst(1, dueDay = 100, principal = 50_000, interest = 300, status = InstallmentStatus.PAID),
            inst(2, dueDay = 200, principal = 48_000, interest = 100),
        )
        // 已还的那期总金额恰好一致，也不能匹配它
        val m = InstallmentMatcher.match(list, amountCents = 50_300, atEpochDay = 150)
        assertEquals(2, m?.number)
    }

    @Test
    fun `all paid returns null`() {
        val list = listOf(
            inst(1, dueDay = 100, principal = 50_000, status = InstallmentStatus.PAID),
        )
        assertNull(InstallmentMatcher.match(list, 50_000, 150))
    }

    @Test
    fun `overdue installments still match`() {
        val list = listOf(inst(1, dueDay = 50, principal = 30_000, status = InstallmentStatus.OVERDUE))
        assertEquals(1, InstallmentMatcher.match(list, 30_000, 100)?.number)
    }
}
