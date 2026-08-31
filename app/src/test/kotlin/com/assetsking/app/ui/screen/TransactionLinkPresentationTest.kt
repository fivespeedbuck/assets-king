package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionLinkPresentationTest {
    @Test
    fun transferAccountLabelAddsOnlyAConfiguredCardTail() {
        assertEquals("招商银行 · 3934", transferAccountLabel("招商银行", "3934"))
        assertEquals("宁波银行", transferAccountLabel("宁波银行", "  "))
    }

    @Test
    fun lendingInterestShowsAnExplicitLendingPlanBadge() {
        val transaction = TransactionEntity(
            id = "interest",
            accountId = "cash",
            amountCents = 2_000L,
            type = "INCOME",
            category = "利息收益",
            occurredAt = 1L,
            lendingPlanId = "lending-plan"
        )

        assertEquals(
            listOf(TransactionLinkBadge.LENDING_PLAN),
            transactionLinkBadges(transaction)
        )
    }

    @Test
    fun recurringExpenseShowsTheRecurringDebitBadge() {
        val transaction = TransactionEntity(
            id = "recurring-expense",
            accountId = "cash",
            amountCents = 8_800L,
            type = "EXPENSE",
            category = "居住生活/房租",
            occurredAt = 1L,
            recurringRuleId = "monthly-rent"
        )

        assertEquals(
            listOf(TransactionLinkBadge.RECURRING_PAYMENT),
            transactionLinkBadges(transaction)
        )
        assertEquals("周期扣款", TransactionLinkBadge.RECURRING_PAYMENT.label)
    }
}
