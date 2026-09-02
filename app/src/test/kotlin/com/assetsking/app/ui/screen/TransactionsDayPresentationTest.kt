package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionsDayPresentationTest {
    private val categories = listOf(
        CategoryEntity("necessary", "必要类", "必要", null, "home", defaultNecessary = true),
        CategoryEntity("optional", "非必要类", "非必要", null, "game", defaultNecessary = false)
    )

    @Test
    fun allRealCashMovementTypesUseTheSameDailyDirection() {
        val summary = transactionsDayPresentation(
            transactions = listOf(
                tx("income", 120_000L, "INCOME"),
                tx("refund", 23_400L, "REFUND"),
                tx("reimbursement", 2_500L, "REIMBURSEMENT"),
                tx("disbursement", 55_000L, "LOAN_DISBURSEMENT"),
                tx("expense", 31_200L, "EXPENSE", category = "necessary"),
                tx("fee", 300L, "FEE", category = "necessary"),
                tx("payment", 21_000L, "LOAN_PAYMENT"),
                tx("prepayment", 4_000L, "LOAN_PREPAYMENT"),
                tx("ignored", 99_999L, "INCOME", status = "IGNORED")
            ),
            categories = categories
        )

        assertEquals(200_900L, summary.inflowCents)
        assertEquals(56_500L, summary.outflowCents)
        assertEquals(144_400L, summary.netCents)
    }

    @Test
    fun spendingMixUsesExplicitOrCategoryNecessityAndNetConsumption() {
        val day = listOf(
            tx("necessary-expense", 4_000L, "EXPENSE", category = "necessary", reimbursedCents = 500L),
            tx("optional-expense", 6_000L, "EXPENSE", category = "necessary", necessity = false),
            tx("category-optional", 2_000L, "FEE", category = "optional"),
            tx("refund", 1_500L, "REFUND", refundOfId = "optional-expense")
        )

        val summary = transactionsDayPresentation(day, categories)

        assertEquals(3_500L, summary.necessaryCents)
        assertEquals(6_500L, summary.optionalCents)
        assertEquals(0L, summary.unclassifiedCents)
        assertEquals(35, summary.necessaryPercent)
        assertEquals(65, summary.optionalPercent)
    }

    @Test
    fun laterLinkedRefundCanOffsetTheOriginalDaysSpendingMix() {
        val expense = tx("expense", 10_000L, "EXPENSE", category = "optional")
        val laterRefund = tx("later-refund", 3_000L, "REFUND", refundOfId = expense.id)

        val summary = transactionsDayPresentation(
            transactions = listOf(expense),
            categories = categories,
            refundTransactions = listOf(expense, laterRefund)
        )

        assertEquals(7_000L, summary.optionalCents)
        assertEquals(0L, summary.inflowCents)
    }

    @Test
    fun noSpendingHasNoNecessityPercent() {
        val summary = transactionsDayPresentation(listOf(tx("income", 100L, "INCOME")), categories)

        assertNull(summary.necessaryPercent)
        assertNull(summary.optionalPercent)
    }

    @Test
    fun unknownNecessityIsNeitherFadedNorForcedIntoTheRatio() {
        val unknown = tx("unknown", 2_500L, "EXPENSE", category = "missing")
        val summary = transactionsDayPresentation(
            transactions = listOf(unknown, tx("known", 7_500L, "EXPENSE", category = "necessary")),
            categories = categories
        )

        assertEquals(2_500L, summary.unclassifiedCents)
        assertNull(summary.necessaryPercent)
        assertNull(summary.optionalPercent)
        assertNull(transactionSpendingNecessity(unknown, null))
    }

    @Test
    fun spendingNecessityOnlyAppliesToKnownSpending() {
        val optional = categories.last()
        assertEquals(false, transactionSpendingNecessity(tx("expense", 100L, "EXPENSE", category = optional.name), optional))
        assertNull(transactionSpendingNecessity(tx("income", 100L, "INCOME", category = optional.name), optional))
        assertEquals(true, transactionSpendingNecessity(tx("explicit", 100L, "EXPENSE", necessity = true), null))
        assertNull(transactionSpendingNecessity(tx("unknown", 100L, "EXPENSE"), null))
        assertNull(
            transactionSpendingNecessity(
                tx("archived", 100L, "EXPENSE", category = optional.name),
                optional.copy(isArchived = true)
            )
        )
    }

    private fun tx(
        id: String,
        amountCents: Long,
        type: String,
        category: String = "测试",
        status: String = "CONFIRMED",
        necessity: Boolean? = null,
        refundOfId: String? = null,
        reimbursedCents: Long = 0L
    ) = TransactionEntity(
        id = id,
        accountId = "cash",
        amountCents = amountCents,
        type = type,
        category = category,
        occurredAt = 1L,
        status = status,
        necessity = necessity,
        refundOfId = refundOfId,
        reimbursedCents = reimbursedCents
    )
}
