package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionEditorSuggestionsTest {
    @Test
    fun oneCharacterQueryPrioritizesPrefixThenContainsAndDeduplicates() {
        assertEquals(
            listOf("美团外卖", "美团买菜", "周末美团券"),
            historyTextSuggestions(
                query = "美",
                candidates = listOf("美团外卖", "美团买菜", "美团外卖", "周末美团券", "盒马")
            )
        )
    }

    @Test
    fun exactCurrentValueAndBlankCandidatesAreNotSuggested() {
        assertEquals(
            listOf("出差打车"),
            historyTextSuggestions("出差", listOf("", "出差", "出差打车"))
        )
    }

    @Test
    fun loanPaymentSplitMustExactlyEqualTotal() {
        assertEquals(0L, loanPaymentSplitDifferenceCents(10_000L, 8_000L, 1_500L, 500L))
        assertEquals(200L, loanPaymentSplitDifferenceCents(10_000L, 7_800L, 1_500L, 500L))
        assertEquals(-300L, loanPaymentSplitDifferenceCents(10_000L, 8_300L, 1_500L, 500L))
    }

    @Test
    fun invalidLoanPaymentSplitCannotPassValidation() {
        assertNull(loanPaymentSplitDifferenceCents(10_000L, null, 0L, 0L))
        assertNull(loanPaymentSplitDifferenceCents(10_000L, -1L, 0L, 0L))
        assertNull(loanPaymentSplitDifferenceCents(10_000L, Long.MAX_VALUE, Long.MAX_VALUE, 0L))
    }

    @Test
    fun refundSourceCandidatesPreferExactUnrefundedExpenseAndStayOptional() {
        fun transaction(
            id: String,
            amount: Long,
            occurredAt: Long,
            type: TransactionType = TransactionType.EXPENSE,
            refundOfId: String? = null,
            accountId: String = "card"
        ) = TransactionEntity(
            id = id,
            accountId = accountId,
            amountCents = amount,
            type = type.name,
            category = "数码产品",
            occurredAt = occurredAt,
            merchant = id,
            refundOfId = refundOfId
        )

        val candidates = refundSourceCandidates(
            transactions = listOf(
                transaction("older-exact", 2_200L, 10L),
                transaction("newer-large", 5_000L, 20L),
                transaction("other-account", 2_200L, 30L, accountId = "cash"),
                transaction("partial-refund", 2_000L, 40L, TransactionType.REFUND, refundOfId = "newer-large")
            ),
            accountId = "card",
            refundAmountCents = 2_200L,
            refundOccurredAt = 50L
        )

        assertEquals(listOf("older-exact", "newer-large"), candidates.map { it.transaction.id })
        assertEquals(listOf(2_200L, 3_000L), candidates.map { it.remainingCents })
    }

    @Test
    fun editingRefundDoesNotCountItselfAgainstItsSource() {
        val expense = TransactionEntity(
            id = "expense",
            accountId = "card",
            amountCents = 3_000L,
            type = TransactionType.EXPENSE.name,
            category = "餐饮",
            occurredAt = 10L
        )
        val refund = TransactionEntity(
            id = "refund",
            accountId = "card",
            amountCents = 3_000L,
            type = TransactionType.REFUND.name,
            category = "餐饮",
            occurredAt = 20L,
            refundOfId = expense.id
        )

        assertEquals(
            listOf("expense"),
            refundSourceCandidates(listOf(expense, refund), "card", 3_000L, 20L, refund.id)
                .map { it.transaction.id }
        )
    }
}
