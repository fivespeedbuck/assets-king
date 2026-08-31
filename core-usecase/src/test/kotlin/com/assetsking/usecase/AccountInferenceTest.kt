package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountInferenceTest {

    private val candidates = listOf(
        AccountInference.Candidate("cmb", "招商银行"),
        AccountInference.Candidate("nbcb", "宁波银行"),
        AccountInference.Candidate("wechat", "微信零钱"),
        AccountInference.Candidate("alipay", "支付宝余额"),
    )

    @Test
    fun `bank evidence wins over history and source`() {
        assertEquals(
            "nbcb",
            AccountInference.infer(bankMatchedAccountId = "nbcb", merchantHistoryAccountId = "cmb", sourcePackage = "com.tencent.mm", candidates = candidates)
        )
    }

    @Test
    fun `merchant history wins over source default`() {
        assertEquals(
            "cmb",
            AccountInference.infer(bankMatchedAccountId = null, merchantHistoryAccountId = "cmb", sourcePackage = "com.tencent.mm", candidates = candidates)
        )
    }

    @Test
    fun `wechat only defaults to wechat wallet`() {
        assertEquals(
            "wechat",
            AccountInference.infer(bankMatchedAccountId = null, merchantHistoryAccountId = null, sourcePackage = "com.tencent.mm", candidates = candidates)
        )
    }

    @Test
    fun `alipay only defaults to alipay balance`() {
        assertEquals(
            "alipay",
            AccountInference.infer(null, null, "com.eg.android.AlipayGphone", candidates)
        )
    }

    @Test
    fun `no evidence and no matching wallet returns null`() {
        assertNull(AccountInference.infer(null, null, "com.some.bank", candidates))
        assertNull(
            AccountInference.infer(null, null, "com.tencent.mm", listOf(AccountInference.Candidate("cmb", "招商银行")))
        )
    }

    @Test
    fun `unknown account ids fall through`() {
        // 历史/银行指向的账户已不存在（归档删除）时继续往下走
        assertEquals(
            "wechat",
            AccountInference.infer("gone", "gone2", "com.tencent.mm", candidates)
        )
    }

    @Test
    fun `bank source label never becomes payment channel`() {
        assertEquals("", AccountInference.channelLabel("sms", "招商银行"))
        assertEquals("", AccountInference.channelLabel("com.cmb.pb", "招商银行"))
        assertEquals("支付宝", AccountInference.channelLabel("sms", "招商银行", "支付宝"))
        assertEquals("微信支付", AccountInference.channelLabel("com.cmb.pb", "招商银行", "微信支付"))
    }

    @Test
    fun `unique bank card tail wins even when bank name is absent from account name`() {
        val resolution = AccountInference.resolveBankAccount(
            cardTail = "3721",
            bankHint = "宁波银行",
            candidates = candidates.map { it.copy(cardTail = if (it.id == "nbcb") "3721" else null) }
        )
        assertEquals("nbcb", resolution.accountId)
        assertEquals("nbcb", AccountInference.infer(resolution.accountId, "alipay", "com.eg.android.AlipayGphone", candidates, resolution.isAmbiguous))
    }

    @Test
    fun `unmapped bank card tail blocks wallet fallback`() {
        val resolution = AccountInference.resolveBankAccount("9999", "宁波银行", candidates)
        assertNull(resolution.accountId)
        assertEquals(true, resolution.isAmbiguous)
        assertNull(AccountInference.infer(resolution.accountId, null, "com.eg.android.AlipayGphone", candidates, resolution.isAmbiguous))
    }

    @Test
    fun `conflicting account matches block wallet fallback`() {
        val resolution = AccountInference.resolveBankAccount(
            "3721", "宁波银行", candidates.map { it.copy(cardTail = if (it.id == "nbcb" || it.id == "cmb") "3721" else null) }
        )
        assertNull(resolution.accountId)
        assertEquals(true, resolution.isAmbiguous)
        assertNull(AccountInference.infer(resolution.accountId, "alipay", "com.eg.android.AlipayGphone", candidates, resolution.isAmbiguous))
    }
}
