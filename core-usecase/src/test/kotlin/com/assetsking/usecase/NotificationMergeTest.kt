package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationMergeTest {

    private fun notif(amount: Long, expense: Boolean, merchant: String? = null, refund: Boolean = false) =
        ParsedNotification(amountCents = amount, merchant = merchant, isExpense = expense, bankHint = null, isRefund = refund)

    // ── 判重 ──

    @Test
    fun `same amount direction and time is duplicate`() {
        val a = notif(3500, expense = true)
        val b = notif(3500, expense = true)
        assertTrue(NotificationMerge.isDuplicate(a, 100_000, b, 100_000 + 60_000))
    }

    @Test
    fun `different direction is not duplicate`() {
        assertFalse(NotificationMerge.isDuplicate(notif(3500, true), 100_000, notif(3500, false), 100_000 + 60_000))
    }

    @Test
    fun `conflicting merchants are not duplicate`() {
        assertFalse(NotificationMerge.isDuplicate(notif(3500, true, "美团"), 100_000, notif(3500, true, "饿了么"), 100_000 + 60_000))
    }

    @Test
    fun `beyond 5 minutes is not duplicate`() {
        assertFalse(NotificationMerge.isDuplicate(notif(3500, true), 100_000, notif(3500, true), 100_000 + 6 * 60_000))
    }

    // ── 退款对冲 vs 转账 ──

    @Test
    fun `refund plus expense same amount is offset`() {
        assertTrue(NotificationMerge.isRefundOffset(notif(3500, false, refund = true), 100_000, notif(3500, true), 100_000 + 60_000))
    }

    @Test
    fun `transfer out and in is NOT offset`() {
        // 转出 + 转入，均无退款字样：是对冲不该抵消
        assertFalse(NotificationMerge.isRefundOffset(notif(10000, true), 100_000, notif(10000, false), 100_000 + 60_000))
    }

    @Test
    fun `same direction is not offset`() {
        assertFalse(NotificationMerge.isRefundOffset(notif(3500, true), 100_000, notif(3500, true), 100_000 + 60_000))
    }

    @Test
    fun `beyond 24h is not offset`() {
        assertFalse(NotificationMerge.isRefundOffset(notif(3500, false, refund = true), 100_000, notif(3500, true), 100_000 + 25 * 3600_000))
    }
}
