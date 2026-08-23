package com.assetsking.ledger

import java.time.LocalDate

/**
 * Existing posted card expense that may be allocated to a post-purchase installment.
 * Creating an allocation is a repayment-terms change only; it never posts money.
 */
data class CardInstallmentSource(
    val transactionId: String,
    val cardAccountId: String,
    val postedExpenseCents: Long,
    val refundedCents: Long = 0,
    val activeAllocatedCents: Long = 0
) {
    val availablePrincipalCents: Long
        get() = (postedExpenseCents - refundedCents - activeAllocatedCents).coerceAtLeast(0)
}

data class CardInstallmentAllocationRequest(
    val transactionId: String,
    val principalCents: Long
)

enum class CardInstallmentAllocationError {
    EMPTY,
    NON_POSITIVE,
    SOURCE_NOT_FOUND,
    WRONG_CARD,
    SOURCE_EXCEEDED,
    CARD_OUTSTANDING_EXCEEDED
}

data class CardInstallmentAllocationDecision(
    val accepted: Boolean,
    val principalCents: Long,
    val error: CardInstallmentAllocationError? = null
)

/**
 * One validation seam for the two caps that must hold at the same time:
 * source expense availability and the card's still-outstanding unallocated liability.
 */
fun validateCardInstallmentAllocation(
    cardAccountId: String,
    cardOutstandingCents: Long,
    activeAllocatedOnCardCents: Long,
    sources: List<CardInstallmentSource>,
    requests: List<CardInstallmentAllocationRequest>
): CardInstallmentAllocationDecision {
    if (requests.isEmpty()) {
        return CardInstallmentAllocationDecision(false, 0, CardInstallmentAllocationError.EMPTY)
    }
    if (requests.any { it.principalCents <= 0 }) {
        return CardInstallmentAllocationDecision(false, 0, CardInstallmentAllocationError.NON_POSITIVE)
    }

    val sourcesById = sources.associateBy { it.transactionId }
    val requestedBySource = requests.groupingBy { it.transactionId }.fold(0L) { sum, request ->
        sum + request.principalCents
    }

    requestedBySource.forEach { (transactionId, requestedCents) ->
        val source = sourcesById[transactionId]
            ?: return CardInstallmentAllocationDecision(false, 0, CardInstallmentAllocationError.SOURCE_NOT_FOUND)
        if (source.cardAccountId != cardAccountId) {
            return CardInstallmentAllocationDecision(false, 0, CardInstallmentAllocationError.WRONG_CARD)
        }
        if (requestedCents > source.availablePrincipalCents) {
            return CardInstallmentAllocationDecision(false, 0, CardInstallmentAllocationError.SOURCE_EXCEEDED)
        }
    }

    val requestedTotal = requests.sumOf { it.principalCents }
    val cardAvailable = (cardOutstandingCents - activeAllocatedOnCardCents).coerceAtLeast(0)
    if (requestedTotal > cardAvailable) {
        return CardInstallmentAllocationDecision(
            accepted = false,
            principalCents = 0,
            error = CardInstallmentAllocationError.CARD_OUTSTANDING_EXCEEDED
        )
    }
    return CardInstallmentAllocationDecision(true, requestedTotal)
}

data class CardInstallmentScheduleLine(
    val number: Int,
    val dueDateEpochDay: Long,
    val principalDueCents: Long,
    val expectedInterestCents: Long,
    val expectedFeeCents: Long,
    val expectedUnclassifiedChargeCents: Long = 0
) {
    val expectedTotalCents: Long
        get() = principalDueCents + expectedInterestCents + expectedFeeCents + expectedUnclassifiedChargeCents
}

/**
 * Builds forecast rows only. Remainder cents go to the earliest periods so principal is exact.
 * Expected interest and fees stay forecast until the issuer actually posts them.
 */
fun buildEqualPrincipalCardInstallmentSchedule(
    principalCents: Long,
    installmentCount: Int,
    firstDueDateEpochDay: Long,
    expectedInterestCentsPerPeriod: Long = 0,
    expectedFeeCentsPerPeriod: Long = 0,
    expectedPaymentCentsPerPeriod: Long? = null
): List<CardInstallmentScheduleLine> {
    require(principalCents > 0)
    require(installmentCount > 0)
    require(expectedInterestCentsPerPeriod >= 0)
    require(expectedFeeCentsPerPeriod >= 0)
    require(expectedPaymentCentsPerPeriod == null || (expectedInterestCentsPerPeriod == 0L && expectedFeeCentsPerPeriod == 0L)) {
        "固定每期总还款与息费明细只能选择一种输入方式"
    }

    val basePrincipal = principalCents / installmentCount
    val remainder = (principalCents % installmentCount).toInt()
    val firstDueDate = LocalDate.ofEpochDay(firstDueDateEpochDay)
    return (1..installmentCount).map { number ->
        val principalDue = basePrincipal + if (number <= remainder) 1 else 0
        val unclassifiedCharge = expectedPaymentCentsPerPeriod?.let { payment ->
            require(payment >= principalDue) { "每期总还款不能低于当期本金" }
            payment - principalDue
        } ?: 0L
        CardInstallmentScheduleLine(
            number = number,
            dueDateEpochDay = firstDueDate.plusMonths((number - 1).toLong()).toEpochDay(),
            principalDueCents = principalDue,
            expectedInterestCents = expectedInterestCentsPerPeriod,
            expectedFeeCents = expectedFeeCentsPerPeriod,
            expectedUnclassifiedChargeCents = unclassifiedCharge
        )
    }
}

data class InstallmentCostEstimate(
    val totalChargeCents: Long,
    val monthlyRate: Double,
    val effectiveAnnualRate: Double
)

/**
 * 由“本金 + 固定月供 + 期数”反推月度 IRR 和有效年化成本率。
 * 这是现金流估算，不冒充银行合同中的名义利率，也不预判息费分类。
 */
fun estimateInstallmentCost(
    principalCents: Long,
    paymentCentsPerPeriod: Long,
    installmentCount: Int
): InstallmentCostEstimate? {
    if (principalCents <= 0L || paymentCentsPerPeriod <= 0L || installmentCount <= 0) return null
    val totalPaymentCents = runCatching {
        Math.multiplyExact(paymentCentsPerPeriod, installmentCount.toLong())
    }.getOrNull() ?: return null
    if (totalPaymentCents < principalCents) return null
    val totalChargeCents = totalPaymentCents - principalCents
    if (totalChargeCents == 0L) return InstallmentCostEstimate(0L, 0.0, 0.0)

    fun presentValue(rate: Double): Double = if (rate == 0.0) {
        totalPaymentCents.toDouble()
    } else {
        paymentCentsPerPeriod * (1.0 - Math.pow(1.0 + rate, -installmentCount.toDouble())) / rate
    }

    var low = 0.0
    var high = 0.10
    while (presentValue(high) > principalCents && high < 100.0) high *= 2.0
    if (presentValue(high) > principalCents) return null
    repeat(100) {
        val mid = (low + high) / 2.0
        if (presentValue(mid) > principalCents) low = mid else high = mid
    }
    val monthlyRate = (low + high) / 2.0
    return InstallmentCostEstimate(
        totalChargeCents = totalChargeCents,
        monthlyRate = monthlyRate,
        effectiveAnnualRate = Math.pow(1.0 + monthlyRate, 12.0) - 1.0
    )
}
