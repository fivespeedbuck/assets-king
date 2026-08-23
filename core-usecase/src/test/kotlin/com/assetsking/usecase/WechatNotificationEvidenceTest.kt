package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WechatNotificationEvidenceTest {
    private val payment = WechatNotificationEvidence.Raw(
        id = "0|com.tencent.mm|-656511598|null|10323:1787402935000",
        packageName = "com.tencent.mm",
        title = "微信支付",
        content = "[12条]微信支付: 已支付¥4.67",
        postedAt = 1787402935000
    )
    private val refund = WechatNotificationEvidence.Raw(
        id = "0|com.tencent.mm|-656511598|null|10323:1787402936938",
        packageName = "com.tencent.mm",
        title = "微信支付",
        content = "[13条]微信支付: 退款到账通知",
        postedAt = 1787402936938
    )

    @Test
    fun `amountless refund is recognized as official incomplete evidence`() {
        assertTrue(WechatNotificationEvidence.isAmountlessRefund(refund))
    }

    @Test
    fun `adjacent update in same notification stream inherits pending payment amount`() {
        assertEquals(
            WechatNotificationEvidence.Match(payment.id, 467L),
            WechatNotificationEvidence.matchAmountlessRefund(refund, listOf(payment))
        )
    }

    @Test
    fun `non adjacent sequence is not guessed`() {
        val unrelated = payment.copy(content = "[10条]微信支付: 已支付¥4.67")
        assertNull(WechatNotificationEvidence.matchAmountlessRefund(refund, listOf(unrelated)))
    }

    @Test
    fun `different notification stream is not guessed`() {
        val unrelated = payment.copy(id = "0|com.tencent.mm|other|null|10323:1787402935000")
        assertNull(WechatNotificationEvidence.matchAmountlessRefund(refund, listOf(unrelated)))
    }

    @Test
    fun `stale payment is not guessed`() {
        val stale = payment.copy(postedAt = refund.postedAt - 11 * 60_000L)
        assertNull(WechatNotificationEvidence.matchAmountlessRefund(refund, listOf(stale)))
    }
}
