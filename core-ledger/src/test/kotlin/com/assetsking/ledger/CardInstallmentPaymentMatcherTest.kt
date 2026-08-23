package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertEquals

class CardInstallmentPaymentMatcherTest {

    @Test
    fun `unique exact payment matches nearest due schedule and only proposes principal`() {
        val decision = matchCardInstallmentPayment(
            cardAccountId = "card",
            paymentCents = 10_500L,
            paymentEpochDay = 100L,
            candidates = listOf(
                candidate("old", dueDay = 69L),
                candidate("nearest", dueDay = 101L)
            )
        )

        assertEquals(CardInstallmentPaymentMatchKind.MATCHED, decision.kind)
        assertEquals(listOf("nearest"), decision.scheduleIds)
        assertEquals(10_000L, decision.principalCents)
    }

    @Test
    fun `same score candidates are ambiguous instead of silently guessed`() {
        val decision = matchCardInstallmentPayment(
            cardAccountId = "card",
            paymentCents = 10_500L,
            paymentEpochDay = 100L,
            candidates = listOf(
                candidate("plan-a", dueDay = 100L),
                candidate("plan-b", dueDay = 100L)
            )
        )

        assertEquals(CardInstallmentPaymentMatchKind.AMBIGUOUS, decision.kind)
        assertEquals(listOf("plan-a", "plan-b"), decision.scheduleIds)
        assertEquals(0L, decision.principalCents)
    }

    @Test
    fun `wrong card amount mismatch and distant due date do not match`() {
        val wrongCard = matchCardInstallmentPayment(
            "other-card", 10_500L, 100L, listOf(candidate("schedule", dueDay = 100L))
        )
        val wrongAmount = matchCardInstallmentPayment(
            "card", 10_000L, 100L, listOf(candidate("schedule", dueDay = 100L))
        )
        val tooFar = matchCardInstallmentPayment(
            "card", 10_500L, 100L, listOf(candidate("schedule", dueDay = 132L))
        )

        assertEquals(CardInstallmentPaymentMatchKind.NO_MATCH, wrongCard.kind)
        assertEquals(CardInstallmentPaymentMatchKind.NO_MATCH, wrongAmount.kind)
        assertEquals(CardInstallmentPaymentMatchKind.NO_MATCH, tooFar.kind)
    }

    private fun candidate(id: String, dueDay: Long) = CardInstallmentPaymentCandidate(
        scheduleId = id,
        planId = "plan-$id",
        cardAccountId = "card",
        dueDateEpochDay = dueDay,
        principalRemainingCents = 10_000L,
        expectedPaymentCents = 10_500L
    )
}
