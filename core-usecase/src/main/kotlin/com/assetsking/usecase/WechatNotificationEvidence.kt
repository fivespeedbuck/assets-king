package com.assetsking.usecase

/** 只处理真机已经证实的微信支付聚合通知模板，不对普通聊天做任何推断。 */
object WechatNotificationEvidence {
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val MAX_ADJACENT_DELAY_MS = 10 * 60_000L
    private val sequencePattern = Regex("""^\[(\d+)条]微信支付[：:]""")

    data class Raw(
        val id: String,
        val packageName: String,
        val title: String?,
        val content: String,
        val postedAt: Long
    )

    data class Match(val paymentId: String, val amountCents: Long)

    fun isOfficialPaymentSender(title: String?, content: String): Boolean {
        val normalizedTitle = title.orEmpty().trim()
        return normalizedTitle == "微信支付" ||
            content.startsWith("微信支付:") || content.startsWith("微信支付：") ||
            content.contains("]微信支付:") || content.contains("]微信支付：")
    }

    fun isAmountlessRefund(raw: Raw): Boolean =
        raw.packageName == WECHAT_PACKAGE &&
            isOfficialPaymentSender(raw.title, raw.content) &&
            sequence(raw.content) != null &&
            "退款到账通知" in raw.content &&
            NotificationParser.parse(raw.content, raw.title).amountCents == null

    fun isAmountlessWithdrawal(raw: Raw): Boolean =
        raw.packageName == WECHAT_PACKAGE &&
            isOfficialPaymentSender(raw.title, raw.content) &&
            sequence(raw.content) != null &&
            "零钱提现已到账" in raw.content &&
            NotificationParser.parse(raw.content, raw.title).amountCents == null

    fun shouldKeepAmountless(raw: Raw): Boolean = isAmountlessRefund(raw) || isAmountlessWithdrawal(raw)

    /**
     * 微信把支付与退款更新在同一系统通知流里。只有流 key 相同、聚合序号正好 +1、
     * 十分钟内且候选唯一，才继承原付款金额；任何一项不满足都不猜。
     */
    fun matchAmountlessRefund(refund: Raw, pendingPayments: List<Raw>): Match? {
        if (!isAmountlessRefund(refund)) return null
        val refundSequence = sequence(refund.content) ?: return null
        val refundStream = streamKey(refund.id) ?: return null
        val matches = pendingPayments.mapNotNull { payment ->
            if (payment.packageName != WECHAT_PACKAGE ||
                !isOfficialPaymentSender(payment.title, payment.content) ||
                streamKey(payment.id) != refundStream ||
                sequence(payment.content) != refundSequence - 1
            ) return@mapNotNull null
            val delay = refund.postedAt - payment.postedAt
            if (delay !in 0..MAX_ADJACENT_DELAY_MS) return@mapNotNull null
            val parsed = NotificationParser.parse(payment.content, payment.title)
            val amount = parsed.amountCents ?: return@mapNotNull null
            if (parsed.isExpense != true || parsed.isRefund) return@mapNotNull null
            Match(payment.id, amount)
        }
        return matches.singleOrNull()
    }

    private fun sequence(content: String): Int? =
        sequencePattern.find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun streamKey(id: String): String? {
        val separator = id.lastIndexOf(':')
        if (separator <= 0 || id.substring(separator + 1).toLongOrNull() == null) return null
        return id.substring(0, separator)
    }
}
