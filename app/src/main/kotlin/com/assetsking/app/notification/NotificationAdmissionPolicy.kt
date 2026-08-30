package com.assetsking.app.notification

import com.assetsking.usecase.WechatNotificationEvidence

internal fun shouldKeepMessageLikeNotification(
    packageName: String,
    channelId: String?,
    category: String?,
    title: String?,
    content: String,
    parsedAmountCents: Long?
): Boolean {
    val isChatApp = packageName == "com.tencent.mm" || packageName == "com.eg.android.AlipayGphone"
    val isMessageLike =
        (category == "msg" && isChatApp) ||
            (packageName == "com.tencent.mm" && channelId == "message_channel_new_id")
    if (!isMessageLike) return true
    if (packageName != "com.tencent.mm") return false

    val normalizedTitle = title.orEmpty().trim()
    val officialPaymentSender = WechatNotificationEvidence.isOfficialPaymentSender(title, content)
    val moneyMoved = listOf(
        "已支付",
        "支付成功",
        "成功付款",
        "付款成功",
        "已收款",
        "收款到账",
        "已退款",
        "退款到账",
        "提现已到账",
        "零钱扣款",
        "已扣款",
        "扣款成功",
        "自动扣费",
        "已扣费",
        "扣费成功"
    ).any { it in normalizedTitle || it in content }
    val amountlessOfficialEvent = WechatNotificationEvidence.shouldKeepAmountless(
        WechatNotificationEvidence.Raw("", packageName, title, content, 0L)
    )
    return officialPaymentSender && moneyMoved && (parsedAmountCents != null || amountlessOfficialEvent)
}
