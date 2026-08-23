package com.assetsking.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingTransferConfirmationTest {
    @Test
    fun `银行收入证据预填为转入账户`() {
        assertEquals(
            PendingTransferAccounts(fromAccountId = "", toAccountId = "cmb"),
            pendingTransferAccounts(isExpense = false, evidenceAccountId = "cmb")
        )
    }

    @Test
    fun `银行支出证据预填为转出账户`() {
        assertEquals(
            PendingTransferAccounts(fromAccountId = "nbcb", toAccountId = ""),
            pendingTransferAccounts(isExpense = true, evidenceAccountId = "nbcb")
        )
    }

    @Test
    fun `方向未知时不猜转账两端`() {
        assertEquals(
            PendingTransferAccounts(fromAccountId = "", toAccountId = ""),
            pendingTransferAccounts(isExpense = null, evidenceAccountId = "cmb")
        )
    }

    @Test
    fun `银行收入的尾号校验转入账户而不是来源钱包`() {
        assertEquals(
            "cmb",
            pendingTransferEvidenceAccountId(
                isExpense = false,
                fromAccountId = "wechat",
                toAccountId = "cmb"
            )
        )
    }

    @Test
    fun `银行支出的尾号校验转出账户`() {
        assertEquals(
            "nbcb",
            pendingTransferEvidenceAccountId(
                isExpense = true,
                fromAccountId = "nbcb",
                toAccountId = "alipay"
            )
        )
    }
}
