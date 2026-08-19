package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertEquals

class TransferPairMergeTest {

    private fun leg(id: String, amount: Long, expense: Boolean, at: Long) =
        TransferPairMerge.Leg(id, amount, expense, at)

    private val t0 = 1_700_000_000_000L

    @Test
    fun `pairs equal-amount opposite-direction within window`() {
        val pairs = TransferPairMerge.findPairs(listOf(
            leg("out", 8_000L, true, t0),
            leg("in", 8_000L, false, t0 + 60_000)
        ))
        assertEquals(1, pairs.size)
        assertEquals("out", pairs[0].out.id)
        assertEquals("in", pairs[0].inLeg.id)
    }

    @Test
    fun `same direction is not paired`() {
        val pairs = TransferPairMerge.findPairs(listOf(
            leg("a", 8_000L, true, t0),
            leg("b", 8_000L, true, t0 + 60_000)
        ))
        assertEquals(0, pairs.size)
    }

    @Test
    fun `different amounts are not paired`() {
        val pairs = TransferPairMerge.findPairs(listOf(
            leg("out", 8_000L, true, t0),
            leg("in", 7_999L, false, t0 + 60_000)
        ))
        assertEquals(0, pairs.size)
    }

    @Test
    fun `outside time window is not paired`() {
        val pairs = TransferPairMerge.findPairs(listOf(
            leg("out", 8_000L, true, t0),
            leg("in", 8_000L, false, t0 + 10 * 60_000 + 1)
        ))
        assertEquals(0, pairs.size)
    }

    @Test
    fun `each leg is used at most once`() {
        val pairs = TransferPairMerge.findPairs(listOf(
            leg("out", 8_000L, true, t0),
            leg("in1", 8_000L, false, t0 + 60_000),
            leg("in2", 8_000L, false, t0 + 120_000)
        ))
        assertEquals(1, pairs.size)
    }
}
