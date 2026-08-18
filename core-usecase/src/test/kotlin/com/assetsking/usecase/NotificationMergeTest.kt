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

    // ── 内容指纹（REQ 监听 §12）──

    @Test
    fun `fingerprint ignores whitespace and punctuation`() {
        val a = NotificationMerge.contentFingerprint("招商银行", "尾号3721支出 24.98元，余额 657.09 元")
        val b = NotificationMerge.contentFingerprint("招商银行", "尾号3721支出24.98元余额657.09元")
        assertTrue(a == b)
        assertTrue(a.isNotBlank())
    }

    @Test
    fun `same evidence with different id is duplicate by fingerprint`() {
        // 补扫重读收件箱：id 前缀不同、receivedAt 差 7 天，但指纹相同、postedAt 相同 → 判重
        val fp = NotificationMerge.contentFingerprint("95555", "尾号3721支出24.98元，余额657.09元")
        assertTrue(NotificationMerge.isSameEvidence(fp, 1_000_000L, fp, 1_000_000L))
    }

    @Test
    fun `identical content hours apart is NOT same evidence`() {
        // 同一订阅内容相同的两次真实扣款：指纹相同但时间差数小时 → 不判重，防误杀
        val fp = NotificationMerge.contentFingerprint(null, "支出50.00元")
        assertFalse(NotificationMerge.isSameEvidence(fp, 1_000_000L, fp, 1_000_000L + 6 * 3600_000L))
    }

    @Test
    fun `different content is not same evidence`() {
        val a = NotificationMerge.contentFingerprint(null, "支出50.00元")
        val b = NotificationMerge.contentFingerprint(null, "支出50.01元")
        assertFalse(NotificationMerge.isSameEvidence(a, 1_000_000L, b, 1_000_000L))
    }

    @Test
    fun `blank fingerprint never matches`() {
        // 迁移 v13→v14 回填前的旧行指纹为空：不能互相判重，也不能把新行误杀
        assertFalse(NotificationMerge.isSameEvidence("", 1_000_000L, "", 1_000_000L))
        assertFalse(NotificationMerge.isSameEvidence("fp", 1_000_000L, "", 1_000_000L))
    }
}
