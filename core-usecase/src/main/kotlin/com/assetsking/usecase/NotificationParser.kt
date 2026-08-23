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
    val bankHint: String?,
    /**
     * 银行在通知里自报的账户余额（分）。这是**权威数字**，确认这笔流水时可以直接
     * 拿来对账 —— 比让用户自己去银行 app 看一眼再打勾强得多。
     */
    val balanceCents: Long? = null,
    /** 卡号后 4 位。余额必须配尾号才敢用：否则不知道这个余额是哪张卡的。 */
    val cardTail: String? = null,
    /** 是否退款/退回类。用于区分「退款对冲」和「转账」（转账不是退款，不能对冲掉）。 */
    val isRefund: Boolean = false
)

/**
 * 信用卡正式出账证据。它只更新信用账户的账单状态，绝不能生成消费流水。
 * 最低还款额故意不建字段：资产大王不采用最低还款模型，避免误入账务计算。
 */
data class ParsedCreditStatement(
    val statementAmountCents: Long,
    val cardTail: String,
    val statementMonth: Int?,
    val dueMonth: Int,
    val dueDay: Int
)

object CreditStatementNotificationParser {
    private const val NUM = """([\d,]+(?:\.\d+)?)"""
    private val amountPattern = Regex("""(?:人民币)?账单金额\s*[¥￥]?\s*$NUM\s*元?""")
    private val cardTailPattern = Regex("""尾号[为是]?\s*(\d{4})""")
    private val statementMonthPattern = Regex("""信用卡\s*(\d{1,2})月""")
    private val dueDatePattern = Regex("""还款到期\s*(\d{1,2})月(\d{1,2})日""")

    fun parse(content: String?, title: String?): ParsedCreditStatement? {
        val text = listOfNotNull(title, content).joinToString(" ")
        if (!text.contains("信用卡") || !text.contains("账单金额")) return null

        val amountYuan = amountPattern.find(text)?.groupValues?.getOrNull(1)
            ?.replace(",", "")?.toDoubleOrNull() ?: return null
        val cardTail = cardTailPattern.find(text)?.groupValues?.getOrNull(1) ?: return null
        val dueMatch = dueDatePattern.find(text) ?: return null
        val dueMonth = dueMatch.groupValues[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val dueDay = dueMatch.groupValues[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val statementMonth = statementMonthPattern.find(text)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.takeIf { it in 1..12 }

        return ParsedCreditStatement(
            statementAmountCents = Math.round(amountYuan * 100),
            cardTail = cardTail,
            statementMonth = statementMonth,
            dueMonth = dueMonth,
            dueDay = dueDay
        ).takeIf { it.statementAmountCents > 0L }
    }
}

/**
 * 从微信/支付宝支付通知原文中提取金额、商户、收支方向、银行。
 * 纯函数，无副作用。
 */
object NotificationParser {
    // ── 金额模式 ──
    /**
     * 金额数字本体，支持千分位逗号。
     * 原先各模式写死 `(\d+\.?\d*)`，遇到银行短信里的 "1,234.56元" 只会抓到 "234.56"，
     * 金额少一个数量级且毫无报错 —— 提成共享片段，一处改全部生效。
     */
    private const val NUM = """([\d,]+(?:\.\d+)?)"""

    private val amountPatterns = listOf(
        // 全角 ￥(U+FFE5) 必须一起认：广发银行账单短信用的就是全角，只认半角会整条漏掉
        Regex("""[¥￥]\s*$NUM"""),
        // 银行短信最常见的写法：「支出（网络支付充值）人民币1.00」。
        // 排除「余额人民币…」，否则抓到的是余额不是这笔金额。
        Regex("""(?<!余额)(?:人民币|RMB|CNY)\s*$NUM"""),
        Regex("""$NUM\s*元"""),
        Regex("""消费\s*$NUM"""),
        Regex("""支付\s*$NUM"""),
        Regex("""付款\s*$NUM"""),
        Regex("""扣款\s*$NUM"""),
        Regex("""支出[金额]?\s*$NUM"""),
        Regex("""-\s*[¥￥]?\s*$NUM"""),
        Regex("""转入\s*$NUM"""),
        Regex("""收款\s*$NUM"""),
        Regex("""退款\s*$NUM""")
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
        "向你", "转给", "付给"
    )

    /**
     * 只表示「支付渠道」、不表示收支方向的字样，判断方向前先抹掉。
     *
     * 真实样本【宁波银行】您尾号3721账户**收入（网络支付回提）**人民币1.00 ——
     * 「网络支付」里的“支付”命中支出信号，压过“收入”，整笔收入被记成支出。
     * 「微信支付收款1.00元」同理。
     */
    private val channelNoise = Regex("""微信支付|支付宝|云闪付|网络支付|支付通道""")

    /**
     * 「钱已经动了」的标志词。命中则不受下面软否决影响。
     * 真实信用卡消费短信普遍写「…消费人民币35.00元，可用额度3000.00元」——
     * 只要一刀切掉「额度」，真消费也会被丢掉，所以必须分两层。
     */
    private val settledMarkers = Regex(
        """消费|支出|收入|收款|到账|入账|成功还款|成功充值|扣款成功|付款成功|支付成功|交易成功|转账成功"""
    )

    /**
     * 硬否决：带金额但钱肯定没动，即使句子里出现「消费」也不能记账。
     * 全部来自这台手机真实短信箱：
     *   【广发银行】…信用卡06月账单￥4,718.94，还款日07月15日        → 还款日
     *   【宁波银行】…本期应还款316.50元，将于2026年07月23日扣款       → 应还
     *   【招联金融】您今天应还1352.29元扣款失败                      → 扣款失败
     *   【账单提醒】…本期实际消费79.0元…本期应付金额为38.94元         → 账单提醒（含“消费”，故必须硬否决）
     *   【广发银行】…已办8937.78元24期总账分期，使用消费额度…         → 总账分期（债务重组不是新消费）
     *   【分期乐】确认向您3637账户预先申请200,000元待审核             → 待审核
     *
     * 「分期」两个字不能单独否决 —— 真实的广发消费推送尾巴上挂着营销文案
     * 「…消费人民币24.99元，交易商户:财付通-美团平台商户。【福利：分期还款利息低至1.7折起】」，
     * 一刀切会把每笔信用卡消费都丢掉。只认「总账分期 / 办理分期」这种确实在办分期的说法。
     */
    private val hardNonTransaction = Regex(
        """账单提醒|本期应付|应还|还款日|扣款失败|待审核|拒收请回复|退订|优惠券|返现券""" +
            """|可提至|最高可|可贷|提额|周转便捷|已还请忽略|已还忽略|请确保|总账分期|办理分期"""
    )

    /** 软否决：只在整条都找不到「钱已动」标志时才否决 —— 提额、营销、借款额度类 */
    private val softNonTransaction = Regex("""额度|申请|临额|借钱|领取""")

    // ── 商户模式 ──
    /**
     * 商户名取到标点为止。
     * 银行通知常在商户后面直接接营销文案，中间没有空格：
     *   「交易商户:财付通-美团平台商户。【福利：分期还款利息低至1.7折起】」
     * 原先用 `\S+` 会把整条尾巴一起吞掉，长度超 30 又被整体丢弃，结果商户名为空 ——
     * 自动分类和学规则全部失效。
     */
    private const val NAME = """([^，。；、！\s【】(（]+)"""

    private val merchantPatterns = listOf(
        // 支付宝真机自动扣款：「你在上海格物致品网络科技有限公司有一笔40.00元…」
        Regex("""你在\s*([^，。；、！【】]{1,40}?)\s*有一笔"""),
        // 招商银行短信常见写法：「在支付宝-李杰快捷支付10.00元」；商户位于
        // “在”和“快捷支付/消费/扣款”之间，不能只依赖支付 App 的通知格式。
        Regex("""在\s*([^，。；、！\s【】(（]+?)\s*(快捷支付|消费|扣款|支付|付款)"""),
        Regex("""收款方[：:]\s*$NAME"""),
        Regex("""商户[：:]\s*$NAME"""),
        Regex("""商户全称[：:]\s*$NAME"""),
        Regex("""对方[：:]\s*$NAME"""),
        Regex("""商品说明[：:]\s*$NAME"""),
        Regex("""商品[：:]\s*$NAME"""),
        Regex("""向\s*(\S+?)\s*(付款|支付|消费|转账)"""),
        Regex("""给\s*(\S+?)\s*(付款|支付|转账)""")
    )

    /**
     * 银行自报余额：「余额657.09」「余额人民币657.09」「余额为92.36元」。
     * 和交易金额分开抓 —— 交易金额那边是特意排除「余额人民币…」的，这里正好相反。
     */
    private val balancePattern = Regex("""余额[为是]?\s*(?:人民币|RMB|CNY)?\s*$NUM""")

    /** 卡号尾 4 位：「尾号3721 / 尾号为3304」（宁波/云闪付）、「您账户3683」（招行）。 */
    private val cardTailPatterns = listOf(
        Regex("""尾号[为是]?\s*(\d{4})"""),
        Regex("""账户(\d{4})""")
    )

    // ── 银行/卡模式 ──
    private val bankPatterns = listOf(
        // 微信：招商银行储蓄卡(1234)、兴业银行信用卡(5678)
        // 必须限定汉字：原先 \S{2,6} 会跨过标点，「付款方式：宁波银行」被抓成
        // 「款方式：宁波银行」，账户匹配直接废掉。
        Regex("""([一-龥]{2,6}银行)\S{0,4}(?:储蓄卡|信用卡|借记卡|贷记卡)?"""),
        // 支付宝：付款方式：宁波银行储蓄卡(1234)
        Regex("""付款方式[：:]\s*(\S{2,10}?)(?:储蓄卡|信用卡|借记卡|贷记卡|\()"""),
        Regex("""([一-龥]{2,6}银行)"""),
        // 银行app直接推送：您尾号1234的储蓄卡
        Regex("""尾号\d{4}\S{0,2}(\S{2,8}?)(?:储蓄卡|信用卡|卡)"""),
        // 花呗/借呗/余额宝
        Regex("""(花呗|借呗|余额宝|零钱通|京东白条|美团月付)""")
    )

    fun parse(content: String?, title: String?): ParsedNotification {
        val text = listOfNotNull(title, content).joinToString(" ")

        // 方向判断专用文本：抹掉支付渠道字样；「收款方：瑞幸咖啡」是我付钱给商户、
        // 不是我收款，改写成「付给」才不会被当成收入。金额和商户仍从原文抓。
        val dirText = channelNoise.replace(text, "").replace("收款方", "付给")

        // 收支方向判断：退款 > 收入 > 支出
        val isRefund = refundKeywords.any { dirText.contains(it) }
        val hasIncome = incomeKeywords.any { dirText.contains(it) }
        val hasExpense = expenseKeywords.any { dirText.contains(it) }

        val looksLikeIncome = isRefund || (hasIncome && !hasExpense)
        val looksLikeExpense = hasExpense && !isRefund

        // 提取金额。放行短信 app 后，短信箱里带「元」的绝大多数不是交易（营销、账单、
        // 还款提醒），抓不掉就会把待确认箱冲垮，所以先过两层否决。
        val amountCents: Long? = when {
            hardNonTransaction.containsMatchIn(text) -> null
            !settledMarkers.containsMatchIn(text) && softNonTransaction.containsMatchIn(text) -> null
            else -> extractAmount(text)
        }

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

        // 余额只在这确实是一笔交易时才取：营销短信里的数字没有对账价值。
        // 余额可以是 0，所以下限是 >=0 而不是 >0。
        val balanceCents = if (amountCents == null) null else
            balancePattern.find(text)?.groupValues?.getOrNull(1)
                ?.replace(",", "")?.toDoubleOrNull()
                ?.takeIf { it >= 0 && it <= 100_000_000 }
                ?.let { Math.round(it * 100) }

        return ParsedNotification(
            amountCents = amountCents,
            merchant = merchant,
            isExpense = when {
                amountCents == null -> null
                looksLikeIncome -> false
                looksLikeExpense -> true
                else -> null
            },
            bankHint = bankHint,
            balanceCents = balanceCents,
            cardTail = cardTailPatterns.firstNotNullOfOrNull {
                it.find(text)?.groupValues?.getOrNull(1)
            },
            isRefund = isRefund
        )
    }

    private fun extractAmount(text: String): Long? {
        for (pattern in amountPatterns) {
            val match = pattern.find(text) ?: continue
            val amountStr = match.groupValues.getOrNull(1) ?: continue
            val yuan = amountStr.replace(",", "").toDoubleOrNull() ?: continue
            if (yuan <= 0 || yuan > 1_000_000) continue
            // 必须四舍五入：1.15 的 double 是 1.1499999…，(yuan * 100).toLong() 截断成 114 分，
            // 每笔悄悄少一分钱。
            return Math.round(yuan * 100)
        }
        return null
    }

    // 商户正则容易误匹配银行名，过滤掉
    private val bankBlacklist = setOf(
        "银行", "储蓄卡", "信用卡", "借记卡", "花呗", "借呗", "余额宝"
    )
}
