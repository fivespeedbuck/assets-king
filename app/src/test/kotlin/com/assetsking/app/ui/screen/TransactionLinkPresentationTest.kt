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
}
