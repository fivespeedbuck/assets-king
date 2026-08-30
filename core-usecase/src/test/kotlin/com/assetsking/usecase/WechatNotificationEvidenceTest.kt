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

    @Test
    fun `amountless wechat refund can link to a unique external refund`() {
        val meituanRefund = WechatNotificationEvidence.Raw(
            id = "meituan-refund",
            packageName = "com.sankuai.meituan",
            title = "退款通知",
            content = "您有一笔14.60元的退款",
            postedAt = refund.postedAt + 1_000L
        )

        assertEquals(
            WechatNotificationEvidence.Match(meituanRefund.id, 1_460L),
            WechatNotificationEvidence.matchAmountlessRefundToExternalRefund(
                refund,
                listOf(meituanRefund)
            )
        )
    }

    @Test
    fun `external partial refund amount wins over adjacent payment amount`() {
        val meituanRefund = WechatNotificationEvidence.Raw(
            id = "meituan-partial-refund",
            packageName = "com.sankuai.meituan",
            title = "退款通知",
            content = "您有一笔0.17元的退款",
            postedAt = refund.postedAt + 1_000L
        )

        assertEquals(
            17L,
            WechatNotificationEvidence.matchAmountlessRefundToExternalRefund(
                refund,
                listOf(payment, meituanRefund)
            )?.amountCents
        )
    }

    @Test
    fun `multiple equally near external refunds stay unguessed`() {
        val first = WechatNotificationEvidence.Raw(
            id = "meituan-refund-1",
            packageName = "com.sankuai.meituan",
            title = "退款通知",
            content = "您有一笔14.60元的退款",
            postedAt = refund.postedAt - 1_000L
        )
        val second = first.copy(
            id = "meituan-refund-2",
            content = "您有一笔8.90元的退款",
            postedAt = refund.postedAt + 1_000L
        )

        assertNull(
            WechatNotificationEvidence.matchAmountlessRefundToExternalRefund(
                refund,
                listOf(first, second)
            )
        )
    }
}
