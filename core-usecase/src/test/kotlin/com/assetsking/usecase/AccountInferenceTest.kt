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
}
