package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationMergeTest {

    private fun notif(amount: Long, expense: Boolean, merchant: String? = null, refund: Boolean = false) =
        ParsedNotification(amountCents = amount, merchant = merchant, isExpense = expense, bankHint = null, isRefund = refund)

    // ── 判重 ──

    @Test
    fun `same amount direction and time is duplicate`() {
        val a = notif(3500, expense = true)
        val b = notif(3500, expense = true)
        assertTrue(NotificationMerge.isDuplicate(a, 100_000, b, 100_000 + 60_000))
    }

    @Test
    fun `real wechat and meituan payment notifications merge as one expense`() {
        val wechat = NotificationParser.parse(content = "已支付¥19.80", title = "微信支付")
        val meituan = NotificationParser.parse(
            content = "您的美团订单已支付成功，点击查看详情>",
            title = "您已成功付款19.80元"
        )

        assertTrue(NotificationMerge.isDuplicate(wechat, 100_000, meituan, 101_000))
    }

    @Test
    fun `alipay expense and cgb pos installment notice are one purchase`() {
        val alipay = NotificationParser.parse("你有一笔2679.00元的支出，点此查看详情。", "交易提醒")
        val cgb = NotificationParser.parse(
            "您尾号3304广发信用卡的分期已受理，本金2679.00元将使用信用额，使用的额度在每月还款后逐期释放。",
            "POS分期消费短信"
        )

        assertTrue(
            NotificationMerge.isDuplicateAcrossSources(
                "com.eg.android.AlipayGphone", alipay, 100_000,
                "com.cs_credit_bank", cgb, 101_000
            )
        )
    }

    @Test
    fun `alipay merchant alias and bank sms are one strongly correlated purchase`() {
        val alipay = NotificationParser.parse(
            "你在百度地图打车有一笔21.49元的免密/自动扣款支付，点此查看详情。",
            "交易提醒"
        )
        val bankSms = NotificationParser.parse(
            "【招商银行】您账户3683于08月25日17:05在支付宝-支付宝-消费-北京百度网讯科技有限公司快捷支付21.49元，余额3210.13",
            "招商银行"
        )

        assertTrue(
            NotificationMerge.isDuplicateAcrossSources(
                "com.eg.android.AlipayGphone", alipay, 100_000,
                "sms", bankSms, 100_618
            )
        )
    }

    @Test
    fun `identical transactions from same app with different notification keys are not duplicates`() {
        val first = NotificationParser.parse("你有一笔2679.00元的支出，点此查看详情。", "交易提醒")
        val second = NotificationParser.parse("你有一笔2679.00元的支出，点此查看详情。", "交易提醒")

        assertFalse(
            NotificationMerge.isDuplicateAcrossSources(
                "com.eg.android.AlipayGphone", first, 100_000,
                "com.eg.android.AlipayGphone", second, 140_000
            )
        )
    }

    @Test
    fun `different direction is not duplicate`() {
        assertFalse(NotificationMerge.isDuplicate(notif(3500, true), 100_000, notif(3500, false), 100_000 + 60_000))
    }

    @Test
    fun `same amount topups from different cards are two real transfers`() {
        val wechatTopUp = NotificationParser.parse(
            "【招商银行】您账户3683于08月22日20:47在财付通-微信支付-微信零钱充值账户快捷支付5.00元，余额3867.12",
            "招商银行"
        )
        val alipayTopUp = NotificationParser.parse(
            "【宁波银行】您尾号3721账户支出（网络支付充值）人民币5.00，余额41.03。",
            "宁波银行"
        )

        assertFalse(NotificationMerge.isDuplicate(wechatTopUp, 100_000, alipayTopUp, 112_000))
    }

    @Test
    fun `conflicting merchants are not duplicate`() {
        assertFalse(NotificationMerge.isDuplicate(notif(3500, true, "美团"), 100_000, notif(3500, true, "饿了么"), 100_000 + 60_000))
    }

    @Test
    fun `same merchant and same amount are not duplicate without stronger evidence`() {
        assertFalse(NotificationMerge.isDuplicate(notif(3500, true, "瑞幸咖啡"), 100_000, notif(3500, true, "瑞幸咖啡"), 100_000 + 60_000))
    }

    @Test
    fun `beyond 5 minutes is not duplicate`() {
        assertFalse(NotificationMerge.isDuplicate(notif(3500, true), 100_000, notif(3500, true), 100_000 + 6 * 60_000))
    }

    // ── 内容指纹（REQ 监听 §12）──

    @Test
    fun `fingerprint ignores whitespace and punctuation`() {
        val a = NotificationMerge.contentFingerprint("招商银行", "尾号3721支出 24.98元，余额 657.09 元")
        val b = NotificationMerge.contentFingerprint("招商银行", "尾号3721支出24.98元余额657.09元")
        assertTrue(a == b)
        assertTrue(a.isNotBlank())
    }

    @Test
    fun `same evidence with different id is duplicate by fingerprint`() {
        // 补扫重读收件箱：id 前缀不同、receivedAt 差 7 天，但指纹相同、postedAt 相同 → 判重
        val fp = NotificationMerge.contentFingerprint("95555", "尾号3721支出24.98元，余额657.09元")
        assertTrue(NotificationMerge.isSameEvidence(fp, 1_000_000L, fp, 1_000_000L))
    }

    @Test
    fun `same app same text but different system notification keys are two real events`() {
        val fp = NotificationMerge.contentFingerprint("交易提醒", "你有一笔2679.00元的支出，点此查看详情。")
        assertFalse(
            NotificationMerge.isSameEvidence(
                "com.eg.android.AlipayGphone",
                "0|com.eg.android.AlipayGphone|-29999935|null|10326:100000",
                fp,
                100_000,
                "com.eg.android.AlipayGphone",
                "0|com.eg.android.AlipayGphone|-29999932|null|10326:140000",
                fp,
                140_000
            )
        )
    }

    @Test
    fun `same app same system notification key repost is one evidence`() {
        val fp = NotificationMerge.contentFingerprint("交易提醒", "支出40.00元")
        assertTrue(
            NotificationMerge.isSameEvidence(
                "com.eg.android.AlipayGphone",
                "0|com.eg.android.AlipayGphone|-29999941|null|10326:100000",
                fp,
                100_000,
                "com.eg.android.AlipayGphone",
                "0|com.eg.android.AlipayGphone|-29999941|null|10326:101000",
                fp,
                101_000
            )
        )
    }

    @Test
    fun `sms receiver and system sms notification with different titles merge by identical body`() {
        val body = "【招商银行】您账户3683于08月28日00:31在支付宝消费1299.00元，余额2934.82元"
        assertTrue(
            NotificationMerge.isSameContentEvidence(
                "sms", "sms:one", "95555", body, 1_000_000L,
                "com.android.mms.service", "mms:two", "招商银行", body, 1_003_000L
            )
        )
    }

    @Test
    fun `different sms bodies with same amount remain separate`() {
        assertFalse(
            NotificationMerge.isSameContentEvidence(
                "sms", "sms:one", "95555", "【招商银行】账户3683消费1299.00元，余额2934.82元", 1_000_000L,
                "com.android.mms.service", "mms:two", "招商银行", "【招商银行】账户3683消费1299.00元，余额1635.82元", 1_003_000L
            )
        )
    }

    @Test
    fun `same system notification key repost days apart is one evidence`() {
        val fp = NotificationMerge.contentFingerprint("交易提醒", "支出40.00元")
        assertTrue(
            NotificationMerge.isSameEvidence(
                "com.android.mms.service",
                "0|com.android.mms.service|95555|null|1000:100000",
                fp,
                100_000,
                "com.android.mms.service",
                "0|com.android.mms.service|95555|null|1000:259300000",
                fp,
                259_300_000
            )
        )
    }

    @Test
    fun `persisted evidence is compared with current fingerprint rules`() {
        assertTrue(
            NotificationMerge.isSameContentEvidence(
                "com.android.mms.service",
                "0|com.android.mms.service|95555|null|1000:100000",
                "招商银行",
                "支出4.00元，余额2934.82元",
                100_000,
                "com.android.mms.service",
                "0|com.android.mms.service|95555|null|1000:259300000",
                "招商银行",
                "支出4.00元，余额2934.82元",
                259_300_000
            )
        )
    }

    @Test
    fun `different system notification keys remain separate days apart`() {
        val fp = NotificationMerge.contentFingerprint("交易提醒", "支出40.00元")
        assertFalse(
            NotificationMerge.isSameEvidence(
                "com.android.mms.service",
                "0|com.android.mms.service|95555|null|1000:100000",
                fp,
                100_000,
                "com.android.mms.service",
                "0|com.android.mms.service|95556|null|1000:259300000",
                fp,
                259_300_000
            )
        )
    }

    @Test
    fun `sms receiver and rescan ids are the same evidence`() {
        val fp = NotificationMerge.contentFingerprint(
            "95555",
            "【招商银行】您账户3683于08月25日17:47快捷支付35.00元，余额3138.74"
        )

        assertTrue(
            NotificationMerge.isSameEvidence(
                "sms",
                "sms:95555:1787651241232",
                fp,
                1787651241232,
                "sms",
                "sms:rescan:95555:1787651241232",
                fp,
                1787651241232
            )
        )
    }

    @Test
    fun `identical content hours apart is NOT same evidence`() {
        // 同一订阅内容相同的两次真实扣款：指纹相同但时间差数小时 → 不判重，防误杀
        val fp = NotificationMerge.contentFingerprint(null, "支出50.00元")
        assertFalse(NotificationMerge.isSameEvidence(fp, 1_000_000L, fp, 1_000_000L + 6 * 3600_000L))
    }

    @Test
    fun `different content is not same evidence`() {
        val a = NotificationMerge.contentFingerprint(null, "支出50.00元")
        val b = NotificationMerge.contentFingerprint(null, "支出50.01元")
        assertFalse(NotificationMerge.isSameEvidence(a, 1_000_000L, b, 1_000_000L))
    }

    @Test
    fun `blank fingerprint never matches`() {
        // 迁移 v13→v14 回填前的旧行指纹为空：不能互相判重，也不能把新行误杀
        assertFalse(NotificationMerge.isSameEvidence("", 1_000_000L, "", 1_000_000L))
        assertFalse(NotificationMerge.isSameEvidence("fp", 1_000_000L, "", 1_000_000L))
    }
}
