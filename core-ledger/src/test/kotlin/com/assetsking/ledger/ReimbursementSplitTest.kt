package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertEquals

class ReimbursementSplitTest {

    @Test
    fun `covers all expenses fully when amount is enough`() {
        assertEquals(listOf(1000L, 2000L), ReimbursementSplit.cover(listOf(1000, 2000), 4000))
    }

    @Test
    fun `last expense is partially covered when amount runs out`() {
        assertEquals(listOf(1000L, 2000L, 500L), ReimbursementSplit.cover(listOf(1000, 2000, 800), 3500))
    }

    @Test
    fun `amount smaller than first expense partially covers it`() {
        assertEquals(listOf(300L, 0L), ReimbursementSplit.cover(listOf(1000, 500), 300))
    }

    @Test
    fun `no expenses means no covers`() {
        assertEquals(emptyList(), ReimbursementSplit.cover(emptyList(), 1000))
    }
}
