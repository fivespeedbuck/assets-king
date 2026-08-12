package com.assetsking.usecase

/**
 * 支付通知解析结果。
 * @param amountCents 提取的金额（分），null 表示未识别
 * @param merchant 提取的商户名，null 表示未识别
 * @param isExpense true=支出，false=收入，null=无法判断
 * @param bankHint 从通知中提取的银行/卡名称，可用于匹配合适的账户
 */
data class ParsedNotification(
    val amountCents: Long?,
    val merchant: String?,
    val isExpense: Boolean?,
    val bankHint: String?
)

/**
 * 从微信/支付宝支付通知原文中提取金额、商户、收支方向、银行。
 * 纯函数，无副作用。
 */
object NotificationParser {
    // ── 金额模式 ──
    private val amountPatterns = listOf(
        Regex("""¥\s*(\d+\.?\d*)"""),
        Regex("""(\d+\.?\d*)\s*元"""),
        Regex("""消费\s*(\d+\.?\d*)"""),
        Regex("""支付\s*(\d+\.?\d*)"""),
        Regex("""付款\s*(\d+\.?\d*)"""),
        Regex("""扣款\s*(\d+\.?\d*)"""),
        Regex("""支出[金额]?\s*(\d+\.?\d*)"""),
        Regex("""-\s*¥?\s*(\d+\.?\d*)"""),
        Regex("""转入\s*(\d+\.?\d*)"""),
        Regex("""收款\s*(\d+\.?\d*)"""),
        Regex("""退款\s*(\d+\.?\d*)""")
    )

    // ── 收入信号（顺序敏感：退款先于收款）──
    private val refundKeywords = listOf("退款", "退回", "退还", "返现")
    private val incomeKeywords = listOf(
        "收款", "入账", "到账", "转入", "收入",
        "转账给你", "转入你", "汇入", "工资", "报销", "收益"
    )
    private val expenseKeywords = listOf(
        "支付", "付款", "消费", "扣款", "支出", "转账给",
        "已付", "成功付款", "快捷支付", "在线支付", "扫码支付",
        "向你", "转给"
    )

    // ── 商户模式 ──
    private val merchantPatterns = listOf(
        Regex("""收款方[：:]\s*(\S+)"""),
        Regex("""商户[：:]\s*(\S+)"""),
        Regex("""商户全称[：:]\s*(\S+)"""),
        Regex("""对方[：:]\s*(\S+)"""),
        Regex("""商品说明[：:]\s*(\S+)"""),
        Regex("""商品[：:]\s*(\S+)"""),
        Regex("""向\s*(\S+?)\s*(付款|支付|消费|转账)"""),
        Regex("""给\s*(\S+?)\s*(付款|支付|转账)""")
    )

    // ── 银行/卡模式 ──
    private val bankPatterns = listOf(
        // 微信：招商银行储蓄卡(1234)、兴业银行信用卡(5678)
        Regex("""(\S{2,6}银行)\S{0,4}(?:储蓄卡|信用卡|借记卡|贷记卡)?"""),
        // 支付宝：付款方式：宁波银行储蓄卡(1234)
        Regex("""付款方式[：:]\s*(\S{2,10}?)(?:储蓄卡|信用卡|借记卡|贷记卡|\()"""),
        Regex("""(\S{2,6}银行)"""),
        // 银行app直接推送：您尾号1234的储蓄卡
        Regex("""尾号\d{4}\S{0,2}(\S{2,8}?)(?:储蓄卡|信用卡|卡)"""),
        // 花呗/借呗/余额宝
        Regex("""(花呗|借呗|余额宝|零钱通|京东白条|美团月付)""")
    )

    fun parse(content: String?, title: String?): ParsedNotification {
        val text = listOfNotNull(title, content).joinToString(" ")

        // 收支方向判断：退款 > 收入 > 支出
        val isRefund = refundKeywords.any { text.contains(it) }
        val hasIncome = incomeKeywords.any { text.contains(it) }
        val hasExpense = expenseKeywords.any { text.contains(it) }

        val looksLikeIncome = isRefund || (hasIncome && !hasExpense)
        val looksLikeExpense = hasExpense && !isRefund

        // 提取金额——收入用收入模式先尝试
        val amountCents: Long? = extractAmount(text)

        // 提取商户
        val merchant = merchantPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() && it.length < 30 && it !in bankBlacklist }
        }

        // 提取银行提示
        val bankHint = bankPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() && it.length < 20 }
        }

        return ParsedNotification(
            amountCents = amountCents,
            merchant = merchant,
            isExpense = when {
                amountCents == null -> null
                looksLikeIncome -> false
                looksLikeExpense -> true
                else -> null
            },
            bankHint = bankHint
        )
    }

    private fun extractAmount(text: String): Long? {
        for (pattern in amountPatterns) {
            val match = pattern.find(text) ?: continue
            val amountStr = match.groupValues.getOrNull(1) ?: continue
            val yuan = amountStr.toDoubleOrNull() ?: continue
            if (yuan <= 0 || yuan > 1_000_000) continue
            return (yuan * 100).toLong()
        }
        return null
    }

    // 商户正则容易误匹配银行名，过滤掉
    private val bankBlacklist = setOf(
        "银行", "储蓄卡", "信用卡", "借记卡", "花呗", "借呗", "余额宝"
    )
}
