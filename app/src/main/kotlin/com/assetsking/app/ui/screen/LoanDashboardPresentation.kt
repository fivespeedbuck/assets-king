package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.database.CreditCardInstallmentAllocationEntity
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.CreditCardInstallmentPaymentMatchEntity
import com.assetsking.database.CreditCardInstallmentScheduleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.ledger.CardInstallmentAllocationRequest
import com.assetsking.ledger.V5Metrics
import com.assetsking.model.AccountType
import com.assetsking.model.RecordStatus
import com.assetsking.model.TransactionType
import com.assetsking.usecase.cashFlowSummary

internal data class DebtCompositionItem(
    val key: String,
    val label: String,
    val cents: Long
)

internal data class LoanDashboardUi(
    val totalDebtCents: Long,
    val mustRepayCents: Long,
    val monthPaidCents: Long,
    val netDebtReductionCents: Long,
    val due7DaysCents: Long,
    val repaymentProgress: Float,
    val composition: List<DebtCompositionItem>
)

internal data class PendingCardPaymentCandidateUi(
    val scheduleId: String,
    val planLabel: String,
    val dueDateEpochDay: Long,
    val principalCents: Long
)

internal data class PendingCardPaymentUi(
    val transferId: String,
    val paymentCents: Long,
    val occurredAt: Long,
    val cardLabel: String,
    val candidates: List<PendingCardPaymentCandidateUi>
)

internal fun pendingCardPaymentConfirmations(
    matches: List<CreditCardInstallmentPaymentMatchEntity>,
    transfers: List<TransferEntity>,
    schedules: List<CreditCardInstallmentScheduleEntity>,
    plans: List<CreditCardInstallmentEntity>,
    accounts: List<AccountEntity>
): List<PendingCardPaymentUi> {
    val transfersById = transfers.associateBy { it.id }
    val schedulesById = schedules.associateBy { it.id }
    val plansById = plans.associateBy { it.id }
    val accountsById = accounts.associateBy { it.id }
    return matches.asSequence()
        .filter { it.status == "PENDING" }
        .groupBy { it.transferId }
        .mapNotNull { (transferId, candidates) ->
            val transfer = transfersById[transferId] ?: return@mapNotNull null
            val candidateUi = candidates.mapNotNull { match ->
                val schedule = schedulesById[match.scheduleId] ?: return@mapNotNull null
                val plan = plansById[match.planId] ?: return@mapNotNull null
                PendingCardPaymentCandidateUi(
                    scheduleId = schedule.id,
                    planLabel = plan.label,
                    dueDateEpochDay = schedule.dueDateEpochDay,
                    principalCents = match.principalCents
                )
            }.sortedWith(compareBy({ it.dueDateEpochDay }, { it.planLabel }, { it.scheduleId }))
            if (candidateUi.isEmpty()) return@mapNotNull null
            PendingCardPaymentUi(
                transferId = transfer.id,
                paymentCents = transfer.amountCents,
                occurredAt = transfer.occurredAt,
                cardLabel = accountsById[transfer.toAccountId]?.name ?: "信用卡",
                candidates = candidateUi
            )
        }
        .sortedWith(compareByDescending<PendingCardPaymentUi> { it.occurredAt }.thenBy { it.transferId })
}

internal fun loanDashboardUi(
    metrics: V5Metrics?,
    transactions: List<TransactionEntity>,
    monthStartMillis: Long,
    monthEndMillis: Long,
    monthOutstandingCents: Long? = null,
    monthPaidCents: Long? = null,
    due7DaysCents: Long? = null
): LoanDashboardUi? {
    metrics ?: return null
    val monthPaid = monthPaidCents ?: cashFlowSummary(
        transactions.filter { it.occurredAt in monthStartMillis..monthEndMillis }
    ).repaymentCents
    return LoanDashboardUi(
        totalDebtCents = metrics.totalDebtCents,
        mustRepayCents = monthOutstandingCents ?: metrics.mustRepayCents,
        monthPaidCents = monthPaid,
        netDebtReductionCents = metrics.netDebtReductionCents,
        due7DaysCents = due7DaysCents ?: metrics.due7DaysCents,
        repaymentProgress = repaymentProgress(monthPaid, monthOutstandingCents ?: metrics.mustRepayCents),
        composition = debtComposition(
            cardDebtCents = metrics.cardDebtCents,
            cardInstallmentRemainingCents = metrics.cardInstallmentRemainingCents,
            loanAccountDebtCents = metrics.loanAccountDebtCents,
            loanPlanDebtCents = metrics.loanPlanDebtCents,
            accruedInterestCents = metrics.accruedInterestCents
        )
    )
}

internal fun repaymentProgress(paidCents: Long, remainingCents: Long): Float {
    val paid = paidCents.coerceAtLeast(0L)
    val remaining = remainingCents.coerceAtLeast(0L)
    val total = paid.toDouble() + remaining.toDouble()
    return if (total <= 0.0) 0f else (paid.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

internal fun debtComposition(
    cardDebtCents: Long,
    cardInstallmentRemainingCents: Long,
    loanAccountDebtCents: Long,
    loanPlanDebtCents: Long,
    accruedInterestCents: Long
): List<DebtCompositionItem> {
    val installmentPrincipal = cardInstallmentRemainingCents.coerceIn(0L, cardDebtCents.coerceAtLeast(0L))
    val regularCreditDebt = (cardDebtCents - installmentPrincipal).coerceAtLeast(0L)
    return listOf(
        DebtCompositionItem("LOAN", "贷款本金", loanAccountDebtCents + loanPlanDebtCents),
        DebtCompositionItem("CARD_INSTALLMENT", "信用分期", installmentPrincipal),
        DebtCompositionItem("CARD", "信用账户账款", regularCreditDebt),
        DebtCompositionItem("ACCRUED", "逾期息费", accruedInterestCents)
    ).filter { it.cents > 0L }
}

internal data class CardInstallmentCandidate(
    val transactionId: String,
    val cardAccountId: String,
    val cardName: String,
    val title: String,
    val occurredAt: Long,
    val postedExpenseCents: Long,
    val refundedCents: Long,
    val activeAllocatedCents: Long,
    val availablePrincipalCents: Long,
    val billingStatus: CardInstallmentBillingStatus = CardInstallmentBillingStatus.POSTED
)

internal enum class CardInstallmentBillingStatus {
    POSTED,
    UNBILLED
}

internal fun filterCardInstallmentCandidates(
    candidates: List<CardInstallmentCandidate>,
    cardAccountId: String,
    query: String
): List<CardInstallmentCandidate> {
    val keyword = query.trim()
    return candidates.filter { candidate ->
        val matchesCard = cardAccountId.isBlank() || candidate.cardAccountId == cardAccountId
        val matchesQuery = keyword.isBlank() ||
            candidate.title.contains(keyword, ignoreCase = true) ||
            candidate.cardName.contains(keyword, ignoreCase = true) ||
            "%.2f".format(candidate.availablePrincipalCents / 100.0).contains(keyword)
        matchesCard && matchesQuery
    }
}

internal fun eligibleStatementInstallmentAccounts(
    accounts: List<AccountEntity>,
    cardRemainingDueByCard: Map<String, Long>,
    candidates: List<CardInstallmentCandidate>
): List<AccountEntity> = accounts.filter { account ->
    account.type == AccountType.CREDIT.name &&
        !account.archived &&
        account.balanceCents > 0L &&
        account.statementDay != null &&
        account.dueDay != null &&
        (cardRemainingDueByCard[account.id] ?: 0L) > 0L &&
        candidates.any {
            it.cardAccountId == account.id && it.billingStatus == CardInstallmentBillingStatus.POSTED
        }
}

/**
 * Only exposes already-posted, still-outstanding card consumption to the installment form.
 * The form cannot invent principal, select another account type, or reuse active allocations.
 */
internal fun cardInstallmentCandidates(
    transactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    plans: List<CreditCardInstallmentEntity>,
    allocations: List<CreditCardInstallmentAllocationEntity>,
    today: java.time.LocalDate = java.time.LocalDate.now(),
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
): List<CardInstallmentCandidate> {
    val cards = accounts
        .filter { it.type == AccountType.CREDIT.name && !it.archived }
        .associateBy { it.id }
    val activePlanById = plans.filter { it.status == "ACTIVE" }.associateBy { it.id }
    val allocatedByTransaction = allocations
        .filter { it.planId in activePlanById }
        .groupingBy { it.transactionId }
        .fold(0L) { total, item -> total + item.allocatedPrincipalCents }
    val allocatedPerCard = activePlanById.values
        .groupBy { it.cardAccountId }
        .mapValues { (_, cardPlans) -> cardPlans.sumOf { it.remainingPrincipalCents } }
    val refundedByOriginal = transactions
        .filter {
            it.status == RecordStatus.CONFIRMED.name &&
                it.type == TransactionType.REFUND.name &&
                it.refundOfId != null
        }
        .groupingBy { requireNotNull(it.refundOfId) }
        .fold(0L) { total, item -> total + item.amountCents }

    return transactions.asSequence()
        .filter {
            it.status == RecordStatus.CONFIRMED.name &&
                it.type == TransactionType.EXPENSE.name &&
                it.accountId in cards
        }
        .mapNotNull { transaction ->
            val card = cards.getValue(transaction.accountId)
            val refunded = refundedByOriginal[transaction.id].orZero().coerceAtMost(transaction.amountCents)
            val allocated = allocatedByTransaction[transaction.id].orZero()
            val sourceAvailable = (transaction.amountCents - refunded - allocated).coerceAtLeast(0L)
            val cardAvailable = (card.balanceCents - allocatedPerCard[card.id].orZero()).coerceAtLeast(0L)
            val available = minOf(sourceAvailable, cardAvailable)
            val purchaseDate = java.time.Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate()
            val cycle = creditCycleWindow(card.statementDay, today)
            val billingStatus = if (cycle != null && purchaseDate in cycle.statementStart..cycle.statementEnd) {
                CardInstallmentBillingStatus.POSTED
            } else {
                CardInstallmentBillingStatus.UNBILLED
            }
            if (available <= 0L) null else CardInstallmentCandidate(
                transactionId = transaction.id,
                cardAccountId = card.id,
                cardName = card.name,
                title = transaction.merchant?.trim().takeUnless { it.isNullOrBlank() }
                    ?: transaction.note?.trim().takeUnless { it.isNullOrBlank() }
                    ?: transaction.category,
                occurredAt = transaction.occurredAt,
                postedExpenseCents = transaction.amountCents,
                refundedCents = refunded,
                activeAllocatedCents = allocated,
                availablePrincipalCents = available,
                billingStatus = billingStatus
            )
        }
        .sortedByDescending { it.occurredAt }
        .toList()
}

/** 把账单分期本金稳定地分摊回原消费；最早消费优先，审计结果不随界面排序变化。 */
internal fun allocateStatementPrincipal(
    candidates: List<CardInstallmentCandidate>,
    principalCents: Long
): List<CardInstallmentAllocationRequest> {
    require(principalCents > 0L)
    require(candidates.isNotEmpty())
    require(candidates.map { it.cardAccountId }.distinct().size == 1) { "账单分期只能属于同一信用账户" }
    require(principalCents <= candidates.sumOf { it.availablePrincipalCents }) { "已选消费可分本金不足" }

    var remaining = principalCents
    return candidates
        .sortedWith(compareBy<CardInstallmentCandidate> { it.occurredAt }.thenBy { it.transactionId })
        .mapNotNull { candidate ->
            if (remaining <= 0L) return@mapNotNull null
            val allocated = minOf(remaining, candidate.availablePrincipalCents)
            remaining -= allocated
            CardInstallmentAllocationRequest(candidate.transactionId, allocated)
        }
}

internal fun cardInstallmentPrincipalLimit(
    billingStatus: CardInstallmentBillingStatus,
    statementRemainingCents: Long,
    selectedCandidates: List<CardInstallmentCandidate>
): Long {
    val selectedAvailable = selectedCandidates.sumOf { it.availablePrincipalCents }
    return if (billingStatus == CardInstallmentBillingStatus.POSTED) {
        minOf(statementRemainingCents.coerceAtLeast(0L), selectedAvailable)
    } else {
        selectedCandidates.singleOrNull()?.availablePrincipalCents ?: 0L
    }
}

private fun Long?.orZero(): Long = this ?: 0L
