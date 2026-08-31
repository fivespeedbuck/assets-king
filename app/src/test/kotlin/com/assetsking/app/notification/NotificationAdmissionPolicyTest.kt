package com.assetsking.app.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationAdmissionPolicyTest {
    @Test
    fun realWechatPaymentOnMessageChannelIsKept() {
        assertTrue(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "微信支付",
                content = "已支付¥19.80",
                parsedAmountCents = 1980L
            )
        )
    }

    @Test
    fun officialWechatWalletDebitOnMessageChannelIsKept() {
        assertTrue(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "微信支付",
                content = "[14条]微信支付: 零钱扣款¥6.00",
                parsedAmountCents = 600L
            )
        )
    }

    @Test
    fun officialWechatAutomaticDebitOnMessageChannelIsKept() {
        assertTrue(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "微信支付",
                content = "[3条]微信支付: 自动扣费¥1.00",
                parsedAmountCents = 100L
            )
        )
    }

    @Test
    fun ordinaryWechatChatContainingPriceIsStillRejected() {
        assertFalse(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "老王",
                content = "今天菜市场75元一斤",
                parsedAmountCents = 7500L
            )
        )
    }

    @Test
    fun contactNamedWechatPayCannotBypassWithoutMoneyMovementTemplate() {
        assertFalse(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "微信支付",
                content = "今天菜市场75元一斤",
                parsedAmountCents = 7500L
            )
        )
    }

    @Test
    fun alipayGenericMessageWithOnlyBareExpenseWordIsStillRejected() {
        assertFalse(
            shouldKeepMessageLikeNotification(
                packageName = "com.eg.android.AlipayGphone",
                channelId = "message_channel",
                category = "msg",
                title = "交易提醒",
                content = "支出19.80元",
                parsedAmountCents = 1980L
            )
        )
    }

    @Test
    fun realAlipayAutomaticDebitMessageOnMessageChannelIsKept() {
        assertTrue(
            shouldKeepMessageLikeNotification(
                packageName = "com.eg.android.AlipayGphone",
                channelId = "message_channel",
                category = "msg",
                title = "交易提醒",
                content = "你有一笔34.75元的免密/自动扣款支付，点此查看详情。",
                parsedAmountCents = 3475L
            )
        )
    }

    @Test
    fun realAlipayExpenseSummaryMessageOnMessageChannelIsKept() {
        assertTrue(
            shouldKeepMessageLikeNotification(
                packageName = "com.eg.android.AlipayGphone",
                channelId = "VPushChannel_1",
                category = "msg",
                title = "交易提醒",
                content = "你有一笔34.75元的支出，点此查看详情。",
                parsedAmountCents = 3475L
            )
        )
    }

    @Test
    fun alipayMarketingMessageWithAmountIsRejected() {
        assertFalse(
            shouldKeepMessageLikeNotification(
                packageName = "com.eg.android.AlipayGphone",
                channelId = "message_channel",
                category = "msg",
                title = "会员权益",
                content = "消费满19.80元可领取优惠券，点击查看详情。",
                parsedAmountCents = 1980L
            )
        )
    }

    @Test
    fun amountlessOfficialWechatRefundIsKeptAsEvidence() {
        assertTrue(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "微信支付",
                content = "[13条]微信支付: 退款到账通知",
                parsedAmountCents = null
            )
        )
    }

    @Test
    fun amountlessOfficialWechatWithdrawalIsKeptForManualCompletion() {
        assertTrue(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "微信支付",
                content = "[9条]微信支付: 零钱提现已到账",
                parsedAmountCents = null
            )
        )
    }

    @Test
    fun contactNamedWechatPayCannotSpoofAmountlessRefund() {
        assertFalse(
            shouldKeepMessageLikeNotification(
                packageName = "com.tencent.mm",
                channelId = "message_channel_new_id",
                category = "msg",
                title = "微信支付",
                content = "退款到账通知",
                parsedAmountCents = null
            )
        )
    }
}
