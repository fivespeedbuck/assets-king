package com.assetsking.usecase

/**
 * 默认账户推断（REQ 账户对账 §18-20）纯函数。
 *
 * 优先级：银行证据（尾号/行名直接命中）> 商户历史（最近一次使用的账户）>
 * 来源推断（仅微信→微信零钱，仅支付宝→支付宝余额）。返回 null 由调用方回退
 * （如列表第一个账户）。
 *
 * REQ §20 的「10 秒窗口内银行通知替换钱包默认值」在归并后的待确认卡上自然成立：
 * 卡内有银行证据时本条链路由银行证据直接命中。
 */
object AccountInference {
    data class Candidate(val id: String, val name: String, val cardTail: String? = null)

    data class BankAccountResolution(val accountId: String?, val isAmbiguous: Boolean = false)

    /**
     * 银行证据必须先按卡尾号匹配；卡尾号唯一时，不再要求账户名称里包含银行名。
     * 这样合并了支付宝/微信通知的待确认项，也不会因为主通知来源是钱包而选错账户。
     */
    fun resolveBankAccount(
        cardTail: String?,
        bankHint: String?,
        candidates: List<Candidate>
    ): BankAccountResolution {
        val normalizedTail = cardTail?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }
        if (normalizedTail != null) {
            val tailMatches = candidates.filter {
                it.cardTail?.filter(Char::isDigit)?.takeLast(4) == normalizedTail
            }
            when {
                tailMatches.size == 1 -> return BankAccountResolution(tailMatches.single().id)
                tailMatches.size > 1 -> return BankAccountResolution(null, isAmbiguous = true)
                // 银行明确给出尾号但本机尚未登记：银行名不能越过这个事实替用户猜账户，
                // 也不能回退成来源钱包或商户历史账户。
                else -> return BankAccountResolution(null, isAmbiguous = true)
            }
        }
        val normalizedHint = bankHint?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedHint != null) {
            val nameMatches = candidates.filter { it.name.contains(normalizedHint) || normalizedHint.contains(it.name) }
            when {
                nameMatches.size == 1 -> return BankAccountResolution(nameMatches.single().id)
                nameMatches.size > 1 -> return BankAccountResolution(null, isAmbiguous = true)
            }
        }
        return BankAccountResolution(null)
    }

    /**
     * 支付渠道标签（REQ 流水§5）：渠道与资金账户分开保存展示。
     *
     * 银行名/短信来源是资金账户证据，不是支付渠道；只有解析出的正文渠道或
     * 明确的微信/支付宝来源才能写入渠道。无法识别时留空，避免把「招商银行」、
     * 「银行短信」沉淀成自定义渠道。
     */
    fun channelLabel(
        packageName: String?,
        sourceLabel: String?,
        parsedChannel: String? = null
    ): String {
        parsedChannel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (packageName) {
            "com.tencent.mm" -> "微信支付"
            "com.eg.android.AlipayGphone" -> "支付宝"
            else -> ""
        }
    }

    fun infer(
        bankMatchedAccountId: String?,
        merchantHistoryAccountId: String?,
        sourcePackage: String?,
        candidates: List<Candidate>,
        bankEvidenceAmbiguous: Boolean = false
    ): String? {
        // 1. 银行证据：这条交易的真实资金账户
        if (bankEvidenceAmbiguous) return null
        bankMatchedAccountId?.let { id -> if (candidates.any { it.id == id }) return id }
        // 2. 商户历史：该标准商户最近一次使用的账户
        merchantHistoryAccountId?.let { id -> if (candidates.any { it.id == id }) return id }
        // 3. 来源推断：只有微信/支付宝单方证据时，默认钱包
        val sourceAccount = when (sourcePackage) {
            "com.tencent.mm" -> candidates.firstOrNull { "零钱" in it.name }?.id
            "com.eg.android.AlipayGphone" -> candidates.firstOrNull { "余额" in it.name }?.id
            else -> null
        }
        return sourceAccount
    }
}
