package com.assetsking.app.ui.screen

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
}
