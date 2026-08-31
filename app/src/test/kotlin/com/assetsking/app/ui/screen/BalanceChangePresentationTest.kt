package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import com.assetsking.usecase.ParsedNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BalanceChangePresentationTest {
    private val account = AccountEntity(
        id = "cmb",
        name = "招商银行",
        type = AccountType.ASSET.name,
        balanceCents = 133_542L,
        cardTail = "3683"
    )

    @Test
    fun expenseAndRefundUseNotificationAfterBalance() {
        val evidence = ParsedNotification(
            amountCents = 3_475L,
            merchant = "帕帕保险",
            isExpense = true,
            bankHint = "招商银行",
            balanceCents = 133_542L,
            cardTail = "3683"
        )

        assertEquals(BalanceChange(137_017L, 133_542L), balanceChangeFromEvidence(account, TransactionType.EXPENSE, 3_475L, evidence))
        assertEquals(BalanceChange(130_067L, 133_542L), balanceChangeFromEvidence(account, TransactionType.REFUND, 3_475L, evidence))
    }

    @Test
    fun missingOrWrongCardEvidenceIsHidden() {
        val missing = ParsedNotification(100L, null, true, null)
        val wrongTail = missing.copy(balanceCents = 1_000L, cardTail = "9999")

        assertNull(balanceChangeFromEvidence(account, TransactionType.EXPENSE, 100L, missing))
        assertNull(balanceChangeFromEvidence(account, TransactionType.EXPENSE, 100L, wrongTail))
    }
}
