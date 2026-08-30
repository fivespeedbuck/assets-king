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

    data class Match(val evidenceId: String, val amountCents: Long)

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
     * 微信退款通知没有金额时，尝试挂到同一时间窗内唯一一条外部退款证据（例如美团）。
     * 只接受唯一最近候选；多笔退款同时靠近时宁可留待补金额，也不猜错订单。
     */
    fun matchAmountlessRefundToExternalRefund(
        raw: Raw,
        candidates: List<Raw>,
        excludedEvidenceIds: Set<String> = emptySet()
    ): Match? {
        if (!isAmountlessRefund(raw)) return null
        val matches = candidates.mapNotNull { candidate ->
            if (candidate.id in excludedEvidenceIds || candidate.packageName == WECHAT_PACKAGE) {
                return@mapNotNull null
            }
            val parsed = NotificationParser.parse(candidate.content, candidate.title)
            val amount = parsed.amountCents ?: return@mapNotNull null
            if (!parsed.isRefund) return@mapNotNull null
            val delay = kotlin.math.abs(raw.postedAt - candidate.postedAt)
            if (delay > MAX_ADJACENT_DELAY_MS) return@mapNotNull null
            Match(candidate.id, amount) to delay
        }
        if (matches.isEmpty()) return null
        val nearestDistance = matches.minOf { it.second }
        val nearest = matches.filter { it.second == nearestDistance }
        return nearest.singleOrNull()?.first
    }

    /** 外部有金额退款先到时，反向寻找唯一相邻的微信无金额退款证据。 */
    fun matchExternalRefundToAmountless(
        externalRefund: Raw,
        candidates: List<Raw>
    ): Match? {
        val externalParsed = NotificationParser.parse(externalRefund.content, externalRefund.title)
        if (externalRefund.packageName == WECHAT_PACKAGE ||
            externalParsed.amountCents == null ||
            !externalParsed.isRefund
        ) return null
        val matches = candidates.mapNotNull { candidate ->
            if (!isAmountlessRefund(candidate)) return@mapNotNull null
            val delay = kotlin.math.abs(externalRefund.postedAt - candidate.postedAt)
            if (delay > MAX_ADJACENT_DELAY_MS) return@mapNotNull null
            Match(candidate.id, externalParsed.amountCents) to delay
        }
        if (matches.isEmpty()) return null
        val nearestDistance = matches.minOf { it.second }
        return matches.filter { it.second == nearestDistance }.singleOrNull()?.first
    }

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
        val lastSeparator = id.lastIndexOf(':')
        if (lastSeparator <= 0) return null
        if (id.substring(lastSeparator + 1).toLongOrNull() != null) {
            return id.substring(0, lastSeparator)
        }
        val postedAtSeparator = id.lastIndexOf(':', lastSeparator - 1)
        if (postedAtSeparator <= 0 ||
            id.substring(postedAtSeparator + 1, lastSeparator).toLongOrNull() == null
        ) return null
        return id.substring(0, postedAtSeparator)
    }

}
