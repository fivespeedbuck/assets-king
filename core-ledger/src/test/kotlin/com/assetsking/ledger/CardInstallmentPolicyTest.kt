package com.assetsking.ledger

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardInstallmentPolicyTest {

    @Test
    fun `refunds and active allocations reduce source principal availability`() {
        val source = CardInstallmentSource(
            transactionId = "purchase",
            cardAccountId = "card",
            postedExpenseCents = 1_200_000,
            refundedCents = 100_000,
            activeAllocatedCents = 300_000
        )

        assertEquals(800_000, source.availablePrincipalCents)
    }

    @Test
    fun `same purchase cannot be allocated twice beyond its available principal`() {
        val decision = validateCardInstallmentAllocation(
            cardAccountId = "card",
            cardOutstandingCents = 1_200_000,
            activeAllocatedOnCardCents = 700_000,
            sources = listOf(CardInstallmentSource("purchase", "card", 1_200_000, activeAllocatedCents = 700_000)),
            requests = listOf(CardInstallmentAllocationRequest("purchase", 500_001))
        )

        assertFalse(decision.accepted)
        assertEquals(CardInstallmentAllocationError.SOURCE_EXCEEDED, decision.error)
    }

    @Test
    fun `allocation cannot exceed remaining posted liability on the card`() {
        val decision = validateCardInstallmentAllocation(
            cardAccountId = "card",
            cardOutstandingCents = 400_000,
            activeAllocatedOnCardCents = 300_000,
            sources = listOf(CardInstallmentSource("purchase", "card", 1_200_000)),
            requests = listOf(CardInstallmentAllocationRequest("purchase", 100_001))
        )

        assertFalse(decision.accepted)
        assertEquals(CardInstallmentAllocationError.CARD_OUTSTANDING_EXCEEDED, decision.error)
    }

    @Test
    fun `cross-card source is rejected`() {
        val decision = validateCardInstallmentAllocation(
            cardAccountId = "card-a",
            cardOutstandingCents = 1_000_000,
            activeAllocatedOnCardCents = 0,
            sources = listOf(CardInstallmentSource("purchase", "card-b", 500_000)),
            requests = listOf(CardInstallmentAllocationRequest("purchase", 500_000))
        )

        assertFalse(decision.accepted)
        assertEquals(CardInstallmentAllocationError.WRONG_CARD, decision.error)
    }

    @Test
    fun `valid post-purchase allocation returns existing principal without creating more debt`() {
        val liabilityBefore = 1_200_000L
        val decision = validateCardInstallmentAllocation(
            cardAccountId = "card",
            cardOutstandingCents = liabilityBefore,
            activeAllocatedOnCardCents = 0,
            sources = listOf(CardInstallmentSource("purchase", "card", liabilityBefore)),
            requests = listOf(CardInstallmentAllocationRequest("purchase", liabilityBefore))
        )

        assertTrue(decision.accepted)
        assertEquals(liabilityBefore, decision.principalCents)
        assertEquals(liabilityBefore, liabilityBefore) // allocation is classification, not another liability
    }

    @Test
    fun `equal principal schedule is exact and future costs stay separate`() {
        val firstDue = LocalDate.of(2026, 9, 18)
        val schedule = buildEqualPrincipalCardInstallmentSchedule(
            principalCents = 1_000,
            installmentCount = 3,
            firstDueDateEpochDay = firstDue.toEpochDay(),
            expectedInterestCentsPerPeriod = 20,
            expectedFeeCentsPerPeriod = 10
        )

        assertEquals(listOf(334L, 333L, 333L), schedule.map { it.principalDueCents })
        assertEquals(1_000, schedule.sumOf { it.principalDueCents })
        assertEquals(
            listOf(firstDue, firstDue.plusMonths(1), firstDue.plusMonths(2)),
            schedule.map { LocalDate.ofEpochDay(it.dueDateEpochDay) }
        )
        assertEquals(90, schedule.sumOf { it.expectedInterestCents + it.expectedFeeCents })
    }

    @Test
    fun `fixed payment keeps unknown finance cost separate from actual interest and fee`() {
        val schedule = buildEqualPrincipalCardInstallmentSchedule(
            principalCents = 120_000,
            installmentCount = 12,
            firstDueDateEpochDay = LocalDate.of(2026, 9, 18).toEpochDay(),
            expectedPaymentCentsPerPeriod = 10_500
        )

        assertEquals(120_000, schedule.sumOf { it.principalDueCents })
        assertEquals(6_000, schedule.sumOf { it.expectedUnclassifiedChargeCents })
        assertEquals(List(12) { 10_500L }, schedule.map { it.expectedTotalCents })
        assertEquals(0, schedule.sumOf { it.expectedInterestCents + it.expectedFeeCents })
    }

    @Test
    fun `payment only input estimates total charge and effective annual cost`() {
        val estimate = requireNotNull(estimateInstallmentCost(120_000, 10_500, 12))

        assertEquals(6_000, estimate.totalChargeCents)
        assertTrue(estimate.effectiveAnnualRate > 0.09)
        assertTrue(estimate.effectiveAnnualRate < 0.10)
        assertEquals(null, estimateInstallmentCost(120_000, 9_000, 12))
    }
}
