package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionRefundPresentationTest {
    @Test
    fun exactLinkedRefundMarksBothTransactionsAsRefunded() {
        val expense = transaction("expense", 2_490L, TransactionType.EXPENSE)
        val refund = transaction("refund", 2_490L, TransactionType.REFUND, refundOfId = expense.id)

        val badges = transactionRefundBadges(listOf(expense, refund))

        assertEquals(TransactionRefundBadge.REFUNDED, badges[expense.id])
        assertEquals(TransactionRefundBadge.REFUNDED, badges[refund.id])
    }

    @Test
    fun partialRefundMarksOnlyTheRefundTransaction() {
        val expense = transaction("expense", 2_490L, TransactionType.EXPENSE)
        val refund = transaction("refund", 1_000L, TransactionType.REFUND, refundOfId = expense.id)

        val badges = transactionRefundBadges(listOf(expense, refund))

        assertNull(badges[expense.id])
        assertEquals(TransactionRefundBadge.REFUND, badges[refund.id])
    }

    @Test
    fun multipleLinkedPartialRefundsThatReachTheOriginalAmountMarkAllAsRefunded() {
        val expense = transaction("expense", 2_490L, TransactionType.EXPENSE)
        val firstRefund = transaction("refund-1", 1_000L, TransactionType.REFUND, refundOfId = expense.id)
        val secondRefund = transaction("refund-2", 1_490L, TransactionType.REFUND, refundOfId = expense.id)

        val badges = transactionRefundBadges(listOf(expense, firstRefund, secondRefund))

        assertEquals(TransactionRefundBadge.REFUNDED, badges[expense.id])
        assertEquals(TransactionRefundBadge.REFUNDED, badges[firstRefund.id])
        assertEquals(TransactionRefundBadge.REFUNDED, badges[secondRefund.id])
    }

    @Test
    fun unlinkedRefundIsStillClearlyMarkedWithoutGuessingSameAmountExpense() {
        val expense = transaction("unrelated-expense", 2_490L, TransactionType.EXPENSE)
        val refund = transaction("unlinked-refund", 2_490L, TransactionType.REFUND)

        val badges = transactionRefundBadges(listOf(expense, refund))

        assertNull(badges[expense.id])
        assertEquals(TransactionRefundBadge.REFUND, badges[refund.id])
    }

    private fun transaction(
        id: String,
        amountCents: Long,
        type: TransactionType,
        refundOfId: String? = null
    ) = TransactionEntity(
        id = id,
        accountId = "cash",
        amountCents = amountCents,
        type = type.name,
        category = "打车",
        occurredAt = 1L,
        refundOfId = refundOfId
    )
}
