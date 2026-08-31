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
    // 支付宝也会把真实交易推到 MessagingStyle/category=msg。不能像普通聊天一样
    // 一刀切丢弃，否则“免密/自动扣款支付”这类通知在银行短信尚未补回前会直接漏账。
    // 只放行明确的资金动作；营销/权益/账单提醒仍由这里挡住。
    if (packageName == "com.eg.android.AlipayGphone") {
        if (parsedAmountCents == null) return false
        val text = listOfNotNull(title, content).joinToString(" ")
        val explicitAlipayExpense = Regex(
            "你有一笔\\s*[¥￥]?[\\d,]+(?:\\.\\d+)?\\s*元?的支出"
        ).containsMatchIn(text)
        val moneyMoved = listOf(
            "支付成功", "付款成功", "已支付", "成功付款", "消费成功",
            "免密", "自动扣款", "自动扣费", "已扣款", "扣款成功",
            "退款", "退回", "收款到账", "到账"
        ).any { marker -> marker in text }
        return moneyMoved || explicitAlipayExpense
    }
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
