package com.assetsking.ledger

import kotlin.math.abs

data class CardInstallmentPaymentCandidate(
    val scheduleId: String,
    val planId: String,
    val cardAccountId: String,
    val dueDateEpochDay: Long,
    val principalRemainingCents: Long,
    val expectedPaymentCents: Long
)

enum class CardInstallmentPaymentMatchKind {
    MATCHED,
    AMBIGUOUS,
    NO_MATCH
}

data class CardInstallmentPaymentMatchDecision(
    val kind: CardInstallmentPaymentMatchKind,
    val scheduleIds: List<String> = emptyList(),
    val principalCents: Long = 0L
)

/**
 * Conservative automatic matcher for a real cash-account -> credit-card Transfer.
 * Forecast charges may identify the quoted payment, but only principal is proposed as paid.
 */
fun matchCardInstallmentPayment(
    cardAccountId: String,
    paymentCents: Long,
    paymentEpochDay: Long,
    candidates: List<CardInstallmentPaymentCandidate>,
    maximumDueDistanceDays: Long = 31L
): CardInstallmentPaymentMatchDecision {
    if (paymentCents <= 0L || maximumDueDistanceDays < 0L) {
        return CardInstallmentPaymentMatchDecision(CardInstallmentPaymentMatchKind.NO_MATCH)
    }
    val exact = candidates.filter {
        it.cardAccountId == cardAccountId &&
            it.principalRemainingCents > 0L &&
            it.expectedPaymentCents == paymentCents
    }
    if (exact.isEmpty()) {
        return CardInstallmentPaymentMatchDecision(CardInstallmentPaymentMatchKind.NO_MATCH)
    }
    val nearestDistance = exact.minOf { abs(it.dueDateEpochDay - paymentEpochDay) }
    if (nearestDistance > maximumDueDistanceDays) {
        return CardInstallmentPaymentMatchDecision(CardInstallmentPaymentMatchKind.NO_MATCH)
    }
    val nearest = exact.filter { abs(it.dueDateEpochDay - paymentEpochDay) == nearestDistance }
        .sortedBy { it.scheduleId }
    if (nearest.size != 1) {
        return CardInstallmentPaymentMatchDecision(
            kind = CardInstallmentPaymentMatchKind.AMBIGUOUS,
            scheduleIds = nearest.map { it.scheduleId }
        )
    }
    return CardInstallmentPaymentMatchDecision(
        kind = CardInstallmentPaymentMatchKind.MATCHED,
        scheduleIds = listOf(nearest.single().scheduleId),
        principalCents = nearest.single().principalRemainingCents
    )
}
