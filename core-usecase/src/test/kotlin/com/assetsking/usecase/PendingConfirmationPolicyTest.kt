package com.assetsking.usecase

import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingConfirmationPolicyTest {
    private fun validInput(
        isExpense: Boolean? = true,
        isRefund: Boolean = false,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null,
        accountCardTail: String? = null,
        currentBalanceCents: Long? = 10_000L
    ) = PendingConfirmationInput(
        amountCents = 1_000L,
        isExpense = isExpense,
        isRefund = isRefund,
        accountId = "cash",
        category = "DINING",
        merchant = "测试商户",
        accountType = AccountType.ASSET,
        accountCardTail = accountCardTail,
        bankCardTail = bankCardTail,
        currentBalanceCents = currentBalanceCents,
        bankBalanceCents = bankBalanceCents
    )

    @Test
    fun `unknown direction cannot be treated as expense`() {
        val result = PendingConfirmationPolicy.validate(validInput(isExpense = null))

        assertNull(result.type)
        assertTrue(PendingConfirmationField.DIRECTION in result.missing)
        assertFalse(result.canConfirm)
        assertEquals(TransactionType.EXPENSE, PendingConfirmationPolicy.typeFor(true, false))
    }

    @Test
    fun `refund keeps explicit refund type`() {
        assertEquals(
            TransactionType.REFUND,
            PendingConfirmationPolicy.typeFor(isExpense = false, isRefund = true)
        )
    }

    @Test
    fun `missing amount account and category block confirmation`() {
        val result = PendingConfirmationPolicy.validate(
            validInput().copy(amountCents = null, accountId = null, category = "UNCATEGORIZED")
        )

        assertTrue(PendingConfirmationField.AMOUNT in result.missing)
        assertTrue(PendingConfirmationField.ACCOUNT in result.missing)
        assertTrue(PendingConfirmationField.CATEGORY in result.missing)
        assertFalse(result.canConfirm)
    }

    @Test
    fun `matching bank balance is confirmable`() {
        val result = PendingConfirmationPolicy.validate(
            validInput(bankBalanceCents = 9_000L, bankCardTail = "1234", accountCardTail = "1234")
        )

        assertTrue(result.canConfirm)
    }

    @Test
    fun `mismatched bank balance blocks confirmation`() {
        val result = PendingConfirmationPolicy.validate(
            validInput(bankBalanceCents = 8_999L, bankCardTail = "1234", accountCardTail = "1234")
        )

        assertTrue(PendingConfirmationField.BALANCE in result.missing)
        assertFalse(result.canConfirm)
    }

    @Test
    fun `bank tail mismatch blocks confirmation even when balance arithmetic matches`() {
        val result = PendingConfirmationPolicy.validate(
            validInput(bankBalanceCents = 9_000L, bankCardTail = "1234", accountCardTail = "5678")
        )

        assertTrue(PendingConfirmationField.BALANCE in result.missing)
    }
}
