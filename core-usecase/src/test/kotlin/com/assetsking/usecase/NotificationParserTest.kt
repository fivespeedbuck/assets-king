package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解析器是自动记账的入口，抓错金额就是账目错，所以用**真实抓到的通知原文**做用例，
 * 不用手编的理想文本。原文来自 `adb shell dumpsys notification --noredact`。
 */
class NotificationParserTest {
    @Test
    fun guangfaStatementExtractsFullBillAndNeverModelsMinimumPayment() {
        val parsed = CreditStatementNotificationParser.parse(
            content = "【广发银行】您尾号3304广发信用卡06月人民币账单金额1,570.44元，最低还款116.00元，还款到期07月15日。点 n.95508.com/x 即可极速办理账单分期，以批核为准。",
            title = null
        )

        requireNotNull(parsed)
        assertEquals(157_044L, parsed.statementAmountCents)
        assertEquals("3304", parsed.cardTail)
        assertEquals(6, parsed.statementMonth)
        assertEquals(7, parsed.dueMonth)
        assertEquals(15, parsed.dueDay)
    }


    // ── 真实样本：宁波银行短信（com.android.mms.service）──
    // 这条曾经整条被丢掉：没有 ¥ 也没有「元」，「支出」后面隔着一个括号，
    // 11 条金额模式一条都不匹配。
    @Test
    fun `宁波银行短信 人民币金额`() {
        val p = NotificationParser.parse(
            content = "【宁波银行】您尾号3721账户支出（网络支付充值）人民币1.00，余额657.09。",
            title = "宁波银行"
        )
        assertEquals(100L, p.amountCents, "应抓到 1.00 而不是余额 657.09")
        assertEquals(true, p.isExpense)
        assertEquals("宁波银行", p.bankHint)
    }

    // 同一条通道的收入版。原先「网络支付」里的“支付”压过“收入”，
    // 转回来的钱会被记成又花了一笔，负债和缺口双向算错。
    @Test
    fun `宁波银行短信 收入不能被渠道名带成支出`() {
        val p = NotificationParser.parse(
            content = "【宁波银行】您尾号3721账户收入（网络支付回提）人民币1.00，余额658.09。",
            title = "宁波银行"
        )
        assertEquals(100L, p.amountCents)
        assertEquals(false, p.isExpense, "收入不能被判成支出")
    }

    // ── 真实样本：招商银行 App 推送（cmb.pb），不带「元」也不带 ¥ ──
    @Test
    fun `招商银行推送 收款`() {
        val p = NotificationParser.parse(
            content = "您账户3683于08月13日15:52收款人民币1.00",
            title = "招商银行"
        )
        assertEquals(100L, p.amountCents, "不能把账号 3683 或日期抓成金额")
        assertEquals(false, p.isExpense)
        assertEquals("招商银行", p.bankHint)
    }

    @Test
    fun `余额里的人民币不能被当成本笔金额`() {
        val p = NotificationParser.parse(
            content = "【工商银行】您尾号1234账户支出人民币1.00，余额人民币657.09。",
            title = null
        )
        assertEquals(100L, p.amountCents)
    }

    @Test
    fun `只报余额不含交易的短信 不该抓成一笔支出`() {
        val p = NotificationParser.parse(content = "【宁波银行】您账户余额人民币657.09。", title = null)
        assertNull(p.amountCents)
    }

    // ── 千分位逗号：原先 "1,234.56元" 会被抓成 234.56 ──
    @Test
    fun `千分位逗号 元结尾`() {
        val p = NotificationParser.parse(content = "消费金额1,234.56元", title = null)
        assertEquals(123456L, p.amountCents)
    }

    @Test
    fun `千分位逗号 人民币前缀`() {
        val p = NotificationParser.parse(content = "您账户支出人民币12,345.00，余额0.01。", title = null)
        assertEquals(1234500L, p.amountCents)
    }

    @Test
    fun `整数千分位无小数`() {
        val p = NotificationParser.parse(content = "转入人民币1,000", title = null)
        assertEquals(100000L, p.amountCents)
    }

    // ── 浮点截断：1.15 的 double 是 1.1499999…，截断会少一分钱 ──
    @Test
    fun `分位四舍五入不丢一分钱`() {
        assertEquals(115L, NotificationParser.parse("消费1.15元", null).amountCents)
        assertEquals(29L, NotificationParser.parse("消费0.29元", null).amountCents)
        assertEquals(8135L, NotificationParser.parse("消费81.35元", null).amountCents)
    }

    // ── 回归：原有格式不能被改坏 ──
    @Test
    fun `微信支付格式`() {
        val p = NotificationParser.parse(content = "微信支付 ¥12.50 收款方：瑞幸咖啡", title = "微信支付")
        assertEquals(1250L, p.amountCents)
        assertEquals("瑞幸咖啡", p.merchant)
        assertEquals(true, p.isExpense)
    }

    @Test
    fun `微信支付消息通道真机样本`() {
        val p = NotificationParser.parse(content = "已支付¥19.80", title = "微信支付")
        assertEquals(1980L, p.amountCents)
        assertEquals(true, p.isExpense)
    }

    @Test
    fun `微信零钱扣款消息解析为支出`() {
        val p = NotificationParser.parse(content = "[14条]微信支付: 零钱扣款¥6.00", title = "微信支付")
        assertEquals(600L, p.amountCents)
        assertEquals(true, p.isExpense)
    }

    @Test
    fun `微信自动扣费消息解析为支出`() {
        val p = NotificationParser.parse(content = "[3条]微信支付: 自动扣费¥1.00", title = "微信支付")
        assertEquals(100L, p.amountCents)
        assertEquals(true, p.isExpense)
    }

    @Test
    fun `美团括号退款金额不能误取到账天数`() {
        val p = NotificationParser.parse(
            content = "您在歪马送酒有一笔【47.00】元的退款，预计1-3个工作日到账。",
            title = "退款状态提醒"
        )
        assertEquals(4_700L, p.amountCents)
        assertEquals(false, p.isExpense)
        assertEquals(true, p.isRefund)
    }

    @Test
    fun `到账天数区间本身不是金额`() {
        val p = NotificationParser.parse(
            content = "您的订单已取消，退款将原路返回，预计1-3个工作日到账。",
            title = "订单已取消"
        )
        assertEquals(null, p.amountCents)
    }

    @Test
    fun `美团取消退款不能把订单尾号当金额`() {
        val p = NotificationParser.parse(
            content = "您尾号6964的订单已取消，38.7元退款原路返还，预计1-3个工作日内到账，请注意查收。",
            title = "订单退款提醒"
        )

        assertEquals(3_870L, p.amountCents, "应取退款 38.7 元，不能取尾号 6964 或到账区间 1-3")
        assertEquals(false, p.isExpense)
        assertEquals(true, p.isRefund)
    }

    @Test
    fun `美团订单支付成功真机样本`() {
        val p = NotificationParser.parse(
            content = "您的美团订单已支付成功，点击查看详情>",
            title = "您已成功付款25.30元"
        )
        assertEquals(2530L, p.amountCents)
        assertEquals(true, p.isExpense)
    }

    @Test
    fun `支付宝格式带银行`() {
        val p = NotificationParser.parse(
            content = "支付成功 30.00元 付款方式：宁波银行储蓄卡(3721)",
            title = "支付宝"
        )
        assertEquals(3000L, p.amountCents)
        assertEquals("宁波银行", p.bankHint)
    }

    @Test
    fun `招商银行短信提取在后商户`() {
        val first = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月23日20:21在支付宝-李杰快捷支付10.00元，余额3458.36",
            title = "95555"
        )
        val second = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月23日20:03在支付宝-四川链动万商科技有限公司快捷支付22.66元，余额3468.36",
            title = "95555"
        )
        val wechat = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月23日20:04在微信-瑞幸咖啡快捷支付12.00元，余额3456.36",
            title = "95555"
        )
        assertEquals("李杰", first.merchant)
        assertEquals("四川链动万商科技有限公司", second.merchant)
        assertEquals("瑞幸咖啡", wechat.merchant)
    }

    @Test
    fun `招商银行短信新格式保留真实商户而不是银行名`() {
        val mala = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月24日11：48在支付宝- 杭景元东北老式麻辣烫（和邦大…快捷支付29。19元，余额3399.24",
            title = "95555"
        )
        val guming = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月24日1151在财付通-微信支付-浙江古茗快捷支付31.70元，余额3367.54",
            title = "95555"
        )
        assertEquals("杭景元东北老式麻辣烫", mala.merchant)
        assertEquals("浙江古茗", guming.merchant)
        assertEquals(2_919L, mala.amountCents)
        assertEquals(3_170L, guming.amountCents)
        assertEquals("支付宝", mala.paymentChannel)
        assertEquals("微信支付", guming.paymentChannel)
    }

    @Test
    fun `广发信用卡交易商户支付宝前缀`() {
        val parsed = NotificationParser.parse(
            content = "【广发银行】您尾号3304信用卡24日12:26消费26.90人民币，交易商户:支付宝-厦门滋利医疗器械有限公司。",
            title = "95508"
        )
        assertEquals(2_690L, parsed.amountCents)
        assertEquals("厦门滋利医疗器械有限公司", parsed.merchant)
        assertEquals("支付宝", parsed.paymentChannel)
        assertEquals("3304", parsed.cardTail)
    }

    @Test
    fun `招商银行转出余额充值退款与宁波动态密码模板`() {
        val transferOut = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月24日12:33实时转至他行人民币1.00，余额3366.54，收款人陈扬",
            title = "95555"
        )
        val balanceTopUp = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月22日20:20在支付宝-支付宝-余额充值-陈扬快捷支付20.00元，余额3548.03",
            title = "95555"
        )
        val refund = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月24日12:30在支付宝-张豪盛退款22.00元，余额3367.54",
            title = "95555"
        )
        val otp = NotificationParser.parse(
            content = "【宁波银行】动态密码774752，序号72，两分钟后失效。您正在向收款人官大荣，尾号2497账户转账1200.00元，请勿泄露动态密码。",
            title = "宁波银行"
        )
        val transferIn = NotificationParser.parse(
            content = "【宁波银行】您尾号3721账户收入（网银转账）人民币1.00，余额21.64。",
            title = "宁波银行"
        )

        assertEquals(100L, transferOut.amountCents)
        assertEquals(true, transferOut.isExpense)
        assertEquals("陈扬", transferOut.merchant)
        assertEquals(2_000L, balanceTopUp.amountCents)
        assertEquals("余额充值-陈扬", balanceTopUp.merchant)
        assertEquals("支付宝", balanceTopUp.paymentChannel)
        assertEquals(2_200L, refund.amountCents)
        assertEquals(false, refund.isExpense)
        assertEquals(true, refund.isRefund)
        assertEquals("张豪盛", refund.merchant)
        assertNull(otp.amountCents)
        assertEquals(100L, transferIn.amountCents)
        assertEquals(false, transferIn.isExpense)
    }

    @Test
    fun `云闪付广发信用卡真机样本`() {
        val p = NotificationParser.parse(
            content = "您尾号为3304的银行卡于22日20时26分消费12.42元",
            title = "支付助手：付款成功"
        )
        assertEquals(1242L, p.amountCents)
        assertEquals(true, p.isExpense)
        assertEquals("3304", p.cardTail)
    }

    @Test
    fun `收入方向`() {
        val p = NotificationParser.parse(content = "微信支付收款1.00元到账", title = null)
        assertEquals(100L, p.amountCents)
        assertEquals(false, p.isExpense)
    }

    @Test
    fun `退款优先于收款判断`() {
        val p = NotificationParser.parse(content = "退款9.90元已到账", title = null)
        assertEquals(990L, p.amountCents)
        assertEquals(false, p.isExpense)
    }

    @Test
    fun `拼多多微信退款真机样本`() {
        val p = NotificationParser.parse(
            content = "尊敬的用户，您的2.87元退款已退款成功。钱款已退回至您的微信账户中。",
            title = "退款成功通知"
        )
        assertEquals(287L, p.amountCents)
        assertEquals(false, p.isExpense)
        assertEquals(true, p.isRefund)
    }

    // ── 噪音：抓不到金额就必须返回 null，否则每条聊天/验证码都进待确认箱 ──
    @Test
    fun `聊天消息和验证码不产生金额`() {
        assertNull(NotificationParser.parse("在吗？晚上一起吃饭", "老王").amountCents)
        assertNull(NotificationParser.parse("您的验证码是1234，5分钟内有效", null).amountCents)
    }

    @Test
    fun `超出量级的数字被拒绝`() {
        assertNull(NotificationParser.parse("中奖人民币99,999,999.00", null).amountCents)
    }

    @Test
    fun `零金额被拒绝`() {
        assertNull(NotificationParser.parse("支出人民币0.00", null).amountCents)
    }

    // ── 真实短信箱噪音：全部来自这台手机，一条都不能变成流水 ──
    // 放行短信 app 之后这批是主要污染源，逐条锁死。
    @Test
    fun `营销和提额短信不产生金额`() {
        val junk = listOf(
            "【分期乐】支付账户额度通知 确认向您3637账户预先申请200,000元待审核 拒收请回复R",
            "【中信银行信用卡】您信用卡临时额度可提至39000元（审核为准）如同意请于2026年07月11日前回LKT申领",
            "【网商银行】网商贷额度有机会提升，还有机会享利率降价，限时有效。-拒收请回复R",
            "【滴滴数科】您已获得借钱申请机会，可享30天息费返现券，周转便捷，最高可贷20万"
        )
        junk.forEach { assertNull(NotificationParser.parse(it, null).amountCents, "不该记账：$it") }
    }

    @Test
    fun `账单和还款提醒不产生金额`() {
        val bills = listOf(
            // 含「消费」二字，所以必须靠硬否决拦，软否决拦不住
            "【账单提醒】您本账单周期为7月1日-7月31日，本期实际消费79.0元，本期应付金额为38.94元。【中国电信】",
            "【广发银行】您尾号3304信用卡06月账单￥4,718.94，还款日07月15日",
            "【花呗】本月还款日07月15日，账单156.60元，戳链接还款，已还忽略",
            "【宁波银行】您通过云闪付-借钱申请的宁波银行宁来花贷款（尾号622725）本期应还款316.50元，将于2026年07月23日扣款，请确保尾号3721还款账户资金充足。",
            "【招联金融】您今天应还1352.29元扣款失败，请17点前补足招商银行3683卡余额。",
            "【广发银行】您尾号3304信用卡已办8937.78元24期总账分期，使用消费额度8937.78元，消费额度剩余24948.54元。"
        )
        bills.forEach { assertNull(NotificationParser.parse(it, null).amountCents, "不该记账：$it") }
    }

    @Test
    fun `广发分期条款短信不是第二笔消费`() {
        val terms = "【广发银行】您尾号3304广发信用卡分期已受理，本金 人民币2679.00元，分24期入账，" +
            "每期应还本金111.63元、每期分期利息8.04元，近似折算年化利率6.77%，" +
            "实际金额以账单列示为准。分期后可用消费额度13675.00元。"

        assertNull(NotificationParser.parse(terms, "106980095508").amountCents)
    }

    // ── 钱真的动了的，一条都不能被上面的否决误杀 ──
    @Test
    fun `信用卡真实消费 带可用额度也要记`() {
        val p = NotificationParser.parse(
            content = "【招商银行】您的信用卡8月13日15:52消费人民币35.00元，可用额度3000.00元",
            title = null
        )
        assertEquals(3500L, p.amountCents, "「可用额度」只能软否决，不能连真消费一起丢")
        assertEquals(true, p.isExpense)
    }

    @Test
    fun `成功还款和成功充值要记`() {
        assertEquals(
            135229L,
            NotificationParser.parse("【招联金融】您于07月17日成功还款1352.29元，可前往App查看详情。", null).amountCents
        )
        assertEquals(
            3000L,
            NotificationParser.parse("【话费充值提醒】您已成功充值30.0元，截至今日您的账户余额为92.36元。", null).amountCents
        )
    }

    @Test
    fun `全角人民币符号`() {
        assertEquals(471894L, NotificationParser.parse("消费￥4,718.94", null).amountCents)
    }

    // ── 真实样本：发现精彩（广发信用卡，com.cs_credit_bank）──
    // 尾巴上挂着「【福利：分期还款利息低至1.7折起】」，是最容易被过滤误杀的一条。
    @Test
    fun `广发信用卡消费 营销尾巴不能误杀也不能吞掉商户名`() {
        val p = NotificationParser.parse(
            content = "您尾号3304广发卡13日16:11消费人民币24.99元，交易商户:财付通-美团平台商户。" +
                "【福利：分期还款利息低至1.7折起】",
            title = "信用卡消费"
        )
        assertEquals(2499L, p.amountCents, "尾巴里的「分期」不能把真实消费否决掉")
        assertEquals(true, p.isExpense)
        assertEquals("财付通-美团平台商户", p.merchant, "商户名要断在句号处，不能吞掉营销文案")
    }

    // ── 真实样本：花呗（支付宝推送）。花呗是信用额度，不是现金支出 ──
    @Test
    fun `花呗支付 认得出金额和花呗账户`() {
        val p = NotificationParser.parse(
            content = "你有一笔23.79元的支出，点此查看详情。使用花呗支付，请及时还款。",
            title = "交易提醒"
        )
        assertEquals(2379L, p.amountCents, "「请及时还款」不能被当成还款提醒否决掉")
        assertEquals(true, p.isExpense)
        assertEquals("花呗", p.bankHint, "要能落到花呗账户，不能记成银行卡扣款")
    }

    // 通知原文里本来就没有商户名的，merchant 必须是 null 而不是抓个错的
    @Test
    fun `原文无商户名时不瞎猜`() {
        assertNull(NotificationParser.parse("你有一笔35.00元的支出，点此查看详情。", "交易提醒").merchant)
        assertNull(NotificationParser.parse("您账户3683于08月13日15:52收款人民币1.00", "招商银行").merchant)
        assertNull(NotificationParser.parse("[8条]微信支付: 已支付¥24.99", "微信支付").merchant)
    }

    @Test
    fun `在线支付里的线不能被识别为商户`() {
        val parsed = NotificationParser.parse(
            content = "您账户3683于08月26日18:20在线支付人民币30.30元，余额2946.82",
            title = "招商银行"
        )
        assertEquals(3030L, parsed.amountCents)
        assertNull(parsed.merchant)
    }

    // ── 自动对账：银行自报余额 + 尾号 ──
    @Test
    fun `宁波银行短信同时给出余额和尾号`() {
        val p = NotificationParser.parse(
            content = "【宁波银行】您尾号3721账户支出（网络支付充值）人民币1.00，余额657.09。",
            title = "宁波银行"
        )
        assertEquals(100L, p.amountCents, "交易金额是 1.00")
        assertEquals(65709L, p.balanceCents, "余额是 657.09，两个数不能串")
        assertEquals("3721", p.cardTail)
    }

    @Test
    fun `支付宝自动扣款真机样本提取商户`() {
        val parsed = NotificationParser.parse(
            content = "你在上海格物致品网络科技有限公司有一笔40.00元的免密/自动扣款支付，点此查看详情。",
            title = "交易提醒"
        )

        assertEquals(4_000L, parsed.amountCents)
        assertEquals("上海格物致品网络科技有限公司", parsed.merchant)
        assertEquals(true, parsed.isExpense)
    }

    @Test
    fun `微信零钱全部提现以银行实际到账净额为准`() {
        val p = NotificationParser.parse(
            content = "【招商银行】您账户3683于08月22日20:38收款325.09元，余额3872.12，备注：财付通-陈扬-微信零钱提现",
            title = "招商银行"
        )
        assertEquals(32_509L, p.amountCents)
        assertEquals(false, p.isExpense)
        assertEquals(387_212L, p.balanceCents)
        assertEquals("3683", p.cardTail)
    }

    @Test
    fun `支付宝提现以银行实际到账净额为准`() {
        val p = NotificationParser.parse(
            content = "【宁波银行】您尾号3721账户收入（网络支付回提）人民币19.90，余额46.03。",
            title = "宁波银行"
        )
        assertEquals(1_990L, p.amountCents)
        assertEquals(false, p.isExpense)
        assertEquals(4_603L, p.balanceCents)
        assertEquals("3721", p.cardTail)
    }

    @Test
    fun `招行短信的账户号也算尾号`() {
        val p = NotificationParser.parse("您账户3683于08月13日15:52收款人民币1.00", "招商银行")
        assertEquals("3683", p.cardTail)
    }

    @Test
    fun `余额为零要能对账`() {
        val p = NotificationParser.parse("您尾号3721账户支出人民币10.00，余额0.00。", null)
        assertEquals(0L, p.balanceCents, "余额 0 是真实状态，不能当成没抓到")
    }

    @Test
    fun `没有余额的通知不瞎给余额`() {
        val p = NotificationParser.parse("[8条]微信支付: 已支付¥24.99", "微信支付")
        assertNull(p.balanceCents)
        assertNull(p.cardTail)
    }

    @Test
    fun `不是交易的短信不给余额`() {
        // 话费余额不是银行卡余额，且没有尾号，不能拿去对账
        val p = NotificationParser.parse("【账单提醒】本期应付金额为38.94元，您的账户余额为92.36元。", null)
        assertNull(p.amountCents)
        assertNull(p.balanceCents)
    }

    @Test
    fun `空内容不崩`() {
        val p = NotificationParser.parse(null, null)
        assertNull(p.amountCents)
        assertTrue(p.isExpense == null)
    }
}
