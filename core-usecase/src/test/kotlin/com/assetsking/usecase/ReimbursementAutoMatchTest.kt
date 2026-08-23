package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReimbursementAutoMatchTest {
    @Test
    fun `selects the only exact combination`() {
        assertEquals(
            listOf("a", "c"),
            uniqueExactReimbursementMatch(
                candidates = listOf(
                    ReimbursementMatchCandidate("a", 10_000L),
                    ReimbursementMatchCandidate("b", 15_000L),
                    ReimbursementMatchCandidate("c", 20_000L)
                ),
                arrivalCents = 30_000L
            )
        )
    }

    @Test
    fun `returns null when two exact combinations are possible`() {
        assertNull(
            uniqueExactReimbursementMatch(
                candidates = listOf(
                    ReimbursementMatchCandidate("a", 10_000L),
                    ReimbursementMatchCandidate("b", 10_000L),
                    ReimbursementMatchCandidate("c", 20_000L)
                ),
                arrivalCents = 20_000L
            )
        )
    }

    @Test
    fun `returns null when no exact combination exists`() {
        assertNull(
            uniqueExactReimbursementMatch(
                candidates = listOf(ReimbursementMatchCandidate("a", 10_000L)),
                arrivalCents = 9_999L
            )
        )
    }
}
