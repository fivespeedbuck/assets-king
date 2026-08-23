package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.CreditCardInstallmentScheduleEntity
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.ledger.cardStatementCycle
import com.assetsking.model.AccountType
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal enum class MonthRepaymentSource {
    LOAN_PLAN,
    CREDIT_STATEMENT,
    CREDIT_INSTALLMENT
}

internal data class MonthRepaymentItem(
    val label: String,
    val amount: Long,
    val dueDay: Long,
    val paid: Boolean,
    val source: MonthRepaymentSource,
    val sourceId: String
)

/**
 * 一张信用账户的统一还款投影。当前账单一旦存在，就是本期应还的权威总额；
 * 落在该还款日前的分期期次只用于解释账单构成，不能再单独累加。
 */
internal data class CreditAccountRepaymentProjection(
    val currentDebtCents: Long,
    val statementDebtCents: Long,
    val unbilledOrdinaryDebtCents: Long,
    val installmentPrincipalCents: Long,
    val otherDebtCents: Long,
    val forecastChargeCents: Long,
    val forecastTotalRepaymentCents: Long,
    val accountDebtNeedsReview: Boolean,
    val nextDueDay: Long?,
    val nextTotalCents: Long,
    val nextInstallmentPrincipalCents: Long,
    val nextInstallmentChargeCents: Long,
    val nextOtherDebtCents: Long,
    val nextBreakdownNeedsReview: Boolean,
    val nextIsStatementPayment: Boolean,
    val statementIsAuthority: Boolean,
    val nextMonthDueDay: Long?,
    val nextMonthTotalCents: Long,
    val nextMonthInstallmentPrincipalCents: Long,
    val nextMonthInstallmentChargeCents: Long,
    val nextMonthOtherDebtCents: Long,
    val nextMonthNeedsReview: Boolean
)

internal fun creditAccountRepaymentProjection(
    account: AccountEntity,
    statementRemainingCents: Long,
    cardInstallments: List<CreditCardInstallmentEntity>,
    cardSchedules: List<CreditCardInstallmentScheduleEntity>,
    today: LocalDate = LocalDate.now()
): CreditAccountRepaymentProjection {
    val activePlans = cardInstallments
        .filter { it.cardAccountId == account.id && it.status == "ACTIVE" }
        .associateBy { it.id }
    val activeSchedules = cardSchedules
        .filter { schedule ->
            val plan = activePlans[schedule.planId]
            plan != null && schedule.revision == plan.scheduleRevision && schedule.status == "UPCOMING"
        }
        .sortedBy { it.dueDateEpochDay }
    fun remainingPrincipal(schedule: CreditCardInstallmentScheduleEntity): Long =
        (schedule.principalDueCents - schedule.principalPaidCents).coerceAtLeast(0L)
    fun remainingCharge(schedule: CreditCardInstallmentScheduleEntity): Long =
        (schedule.expectedInterestCents - schedule.interestPaidCents).coerceAtLeast(0L) +
            (schedule.expectedFeeCents - schedule.feePaidCents).coerceAtLeast(0L) +
            schedule.expectedUnclassifiedChargeCents.coerceAtLeast(0L)
    fun remainingTotal(schedule: CreditCardInstallmentScheduleEntity): Long =
        remainingPrincipal(schedule) + remainingCharge(schedule)

    val currentDebt = account.balanceCents.coerceAtLeast(0L)
    val rawStatementDebt = statementRemainingCents.coerceAtLeast(0L)
    val statementDebt = rawStatementDebt.coerceAtMost(currentDebt)
    val installmentPrincipal = activePlans.values.sumOf { it.remainingPrincipalCents.coerceAtLeast(0L) }
    val otherDebt = (currentDebt - installmentPrincipal).coerceAtLeast(0L)
    val accountDebtNeedsReview = rawStatementDebt > currentDebt || installmentPrincipal > currentDebt
    val forecastCharge = activeSchedules.sumOf(::remainingCharge)
    val statementDue = currentCreditDueDate(account, today)?.toEpochDay()
    val futureAccountDue = nextCreditDueDate(account, today)?.toEpochDay()
    val authoritativeStatement = statementDebt > 0L
    val currentCycleStart = cardStatementCycle(account.statementDay, today).currentStatementEpochDay
    val currentStatementConvertedPlanIds = activePlans.values
        .filter {
            it.installmentType == "STATEMENT_INSTALLMENT" &&
                it.statementCycleStartEpochDay == currentCycleStart
        }
        .mapTo(mutableSetOf()) { it.id }
    val statementIncludedSchedules = if (authoritativeStatement && statementDue != null) {
        activeSchedules.filter {
            it.planId !in currentStatementConvertedPlanIds && it.dueDateEpochDay <= statementDue
        }
    } else {
        emptyList()
    }
    val separatelyDueAtStatement = if (authoritativeStatement && statementDue != null) {
        activeSchedules.filter {
            it.planId in currentStatementConvertedPlanIds && it.dueDateEpochDay == statementDue
        }
    } else {
        emptyList()
    }
    val statementIncludedTotal = statementIncludedSchedules.sumOf(::remainingTotal)
    val statementOrdinaryDebt = (statementDebt - statementIncludedTotal).coerceAtLeast(0L)
    val statementPaymentTotal = statementDebt + separatelyDueAtStatement.sumOf(::remainingTotal)
    val statementBreakdownNeedsReview = accountDebtNeedsReview ||
        statementIncludedTotal > statementDebt ||
        statementOrdinaryDebt > otherDebt ||
        (statementDue == null && activePlans.isNotEmpty())

    val earlierConvertedSchedules = if (authoritativeStatement && statementDue != null) {
        activeSchedules.filter {
            it.planId in currentStatementConvertedPlanIds && it.dueDateEpochDay < statementDue
        }
    } else {
        emptyList()
    }
    val earlierConvertedDue = earlierConvertedSchedules.firstOrNull()?.dueDateEpochDay
    val nextDue = when {
        authoritativeStatement && earlierConvertedDue != null -> earlierConvertedDue
        authoritativeStatement -> statementDue
        else -> listOfNotNull(
            futureAccountDue.takeIf { otherDebt > 0L },
            activeSchedules.firstOrNull()?.dueDateEpochDay
        ).minOrNull()
    }
    val nextIsStatementPayment = authoritativeStatement && earlierConvertedDue == null
    val nextSchedules = when {
        nextIsStatementPayment -> statementIncludedSchedules + separatelyDueAtStatement
        authoritativeStatement && earlierConvertedDue != null ->
            earlierConvertedSchedules.filter { it.dueDateEpochDay == earlierConvertedDue }
        nextDue != null -> activeSchedules.filter { it.dueDateEpochDay == nextDue }
        else -> emptyList()
    }
    val scheduledPrincipal = nextSchedules.sumOf(::remainingPrincipal)
    val scheduledCharge = nextSchedules.sumOf(::remainingCharge)
    val scheduledTotal = scheduledPrincipal + scheduledCharge
    val rawNextOtherDebt = when {
        nextIsStatementPayment -> statementOrdinaryDebt
        !authoritativeStatement && nextDue == futureAccountDue -> otherDebt
        else -> 0L
    }
    val nextTotal = when {
        nextIsStatementPayment -> statementPaymentTotal
        else -> rawNextOtherDebt + scheduledTotal
    }
    val nextBreakdownNeedsReview = accountDebtNeedsReview ||
        (nextIsStatementPayment && statementBreakdownNeedsReview)
    val nextInstallmentPrincipal = scheduledPrincipal.coerceAtMost(nextTotal)
    val nextInstallmentCharge = scheduledCharge.coerceAtMost((nextTotal - nextInstallmentPrincipal).coerceAtLeast(0L))
    val normalizedNextOtherDebt = (nextTotal - nextInstallmentPrincipal - nextInstallmentCharge).coerceAtLeast(0L)
    val unbilledOrdinaryDebt = if (authoritativeStatement) {
        (otherDebt - statementOrdinaryDebt).coerceAtLeast(0L)
    } else {
        otherDebt
    }
    val nextMonthSchedules = futureAccountDue?.let { dueDay ->
        activeSchedules.filter { it.dueDateEpochDay == dueDay }
    }.orEmpty()
    val nextMonthInstallmentPrincipal = nextMonthSchedules.sumOf(::remainingPrincipal)
    val nextMonthInstallmentCharge = nextMonthSchedules.sumOf(::remainingCharge)
    val nextMonthOtherDebt = unbilledOrdinaryDebt.takeIf { futureAccountDue != null } ?: 0L
    val nextMonthTotal = nextMonthOtherDebt + nextMonthInstallmentPrincipal + nextMonthInstallmentCharge
    val nextMonthNeedsReview = accountDebtNeedsReview ||
        (futureAccountDue == null && (unbilledOrdinaryDebt > 0L || activeSchedules.isNotEmpty()))

    return CreditAccountRepaymentProjection(
        currentDebtCents = currentDebt,
        statementDebtCents = if (authoritativeStatement) statementPaymentTotal else 0L,
        unbilledOrdinaryDebtCents = unbilledOrdinaryDebt,
        installmentPrincipalCents = installmentPrincipal,
        otherDebtCents = otherDebt,
        forecastChargeCents = forecastCharge,
        forecastTotalRepaymentCents = currentDebt + forecastCharge,
        accountDebtNeedsReview = accountDebtNeedsReview,
        nextDueDay = nextDue,
        nextTotalCents = nextTotal,
        nextInstallmentPrincipalCents = nextInstallmentPrincipal,
        nextInstallmentChargeCents = nextInstallmentCharge,
        nextOtherDebtCents = normalizedNextOtherDebt,
        nextBreakdownNeedsReview = nextBreakdownNeedsReview,
        nextIsStatementPayment = nextIsStatementPayment,
        statementIsAuthority = authoritativeStatement,
        nextMonthDueDay = futureAccountDue,
        nextMonthTotalCents = nextMonthTotal,
        nextMonthInstallmentPrincipalCents = nextMonthInstallmentPrincipal,
        nextMonthInstallmentChargeCents = nextMonthInstallmentCharge,
        nextMonthOtherDebtCents = nextMonthOtherDebt,
        nextMonthNeedsReview = nextMonthNeedsReview
    )
}

/** 首页与贷款页共享的本月应还/已还唯一投影；页面不得再各自拼金额或笔数。 */
internal fun monthRepaymentItems(
    plans: List<LoanPlanEntity>,
    accounts: List<AccountEntity>,
    cardInstallments: List<CreditCardInstallmentEntity>,
    cardSchedules: List<CreditCardInstallmentScheduleEntity>,
    cardRemainingDueByCard: Map<String, Long>,
    transactions: List<TransactionEntity> = emptyList(),
    transfers: List<TransferEntity> = emptyList(),
    today: LocalDate = LocalDate.now(),
    outstandingThrough: LocalDate = YearMonth.from(today).atEndOfMonth(),
    installmentsForPlan: (LoanPlanEntity) -> List<LoanInstallment> = { jsonToInstallments(it.installmentsJson) }
): List<MonthRepaymentItem> {
    val month = YearMonth.from(today)
    val monthStartDay = month.atDay(1).toEpochDay()
    val monthEndDay = month.atEndOfMonth().toEpochDay()
    val outstandingEndDay = outstandingThrough.toEpochDay()
    val accountsById = accounts.associateBy { it.id }
    val plansById = plans.associateBy { it.id }
    val zone = ZoneId.systemDefault()
    fun occurredEpochDay(occurredAt: Long): Long =
        Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate().toEpochDay()

    return buildList {
        plans.filter { plan ->
            plan.status != "PAID_OFF" &&
                accountsById[plan.accountId]?.let { it.type == AccountType.LOAN.name && !it.archived } == true
        }.forEach { plan ->
            val accountName = accountsById.getValue(plan.accountId).name
            installmentsForPlan(plan)
                .filter { it.status != InstallmentStatus.PAID && it.dueDateEpochDay <= outstandingEndDay }
                .forEach { installment ->
                    add(MonthRepaymentItem("$accountName 第${installment.number}期", installment.total.cents, installment.dueDateEpochDay, false, MonthRepaymentSource.LOAN_PLAN, plan.id))
                }
        }

        val cardPlansById = cardInstallments.filter { it.status == "ACTIVE" }.associateBy { it.id }
        val activeCardSchedules = cardSchedules.filter { schedule ->
            val plan = cardPlansById[schedule.planId]
            plan != null && schedule.revision == plan.scheduleRevision && schedule.status == "UPCOMING"
        }
        fun cardScheduleAmount(schedule: CreditCardInstallmentScheduleEntity): Long {
            val expected = schedule.principalDueCents + schedule.expectedInterestCents +
                schedule.expectedFeeCents + schedule.expectedUnclassifiedChargeCents
            val alreadyPaid = schedule.principalPaidCents + schedule.interestPaidCents + schedule.feePaidCents
            return (expected - alreadyPaid).coerceAtLeast(0L)
        }
        val currentCycleStartByCard = accounts
            .filter { it.type == AccountType.CREDIT.name && !it.archived }
            .associate { it.id to cardStatementCycle(it.statementDay, today).currentStatementEpochDay }
        val currentStatementConvertedPlanIds = cardPlansById.values
            .filter { plan ->
                plan.installmentType == "STATEMENT_INSTALLMENT" &&
                    plan.statementCycleStartEpochDay == currentCycleStartByCard[plan.cardAccountId]
            }
            .mapTo(mutableSetOf()) { it.id }
        val schedulesHandledByStatement = mutableSetOf<String>()
        accounts.filter { it.type == AccountType.CREDIT.name && !it.archived && it.balanceCents > 0L }.forEach { account ->
            val statementRemaining = minOf(
                cardRemainingDueByCard[account.id] ?: account.statementOriginalDueCents,
                account.balanceCents
            ).coerceAtLeast(0L)
            val dueDate = currentCreditDueDate(account, today)
            if (statementRemaining > 0L && dueDate != null && dueDate.toEpochDay() <= outstandingEndDay) {
                val dueDay = dueDate.toEpochDay()
                val schedulesForCard = activeCardSchedules.filter {
                    cardPlansById[it.planId]?.cardAccountId == account.id
                }
                val schedulesAlreadyIncluded = schedulesForCard.filter {
                    it.planId !in currentStatementConvertedPlanIds && it.dueDateEpochDay <= dueDay
                }
                val separatelyDueAtStatement = schedulesForCard.filter {
                    it.planId in currentStatementConvertedPlanIds && it.dueDateEpochDay == dueDay
                }
                schedulesHandledByStatement += (schedulesAlreadyIncluded + separatelyDueAtStatement).map { it.id }
                val statementPayment = statementRemaining + separatelyDueAtStatement.sumOf(::cardScheduleAmount)
                add(MonthRepaymentItem("${account.name} 本期账单", statementPayment, dueDay, false, MonthRepaymentSource.CREDIT_STATEMENT, account.id))
            }
        }

        activeCardSchedules.forEach { schedule ->
            if (schedule.id in schedulesHandledByStatement) return@forEach
            val cardPlan = cardPlansById[schedule.planId] ?: return@forEach
            val account = accountsById[cardPlan.cardAccountId]
                ?.takeIf { !it.archived && it.balanceCents > 0L }
                ?: return@forEach
            if (schedule.dueDateEpochDay > outstandingEndDay) return@forEach
            val amount = cardScheduleAmount(schedule)
            if (amount > 0L) {
                add(MonthRepaymentItem("${account.name} · ${cardPlan.label} 第${schedule.number}期", amount, schedule.dueDateEpochDay, false, MonthRepaymentSource.CREDIT_INSTALLMENT, account.id))
            }
        }

        transactions.filter {
            it.status == "CONFIRMED" &&
                it.amountCents > 0L &&
                (it.type == "LOAN_PAYMENT" || it.type == "LOAN_PREPAYMENT") &&
                occurredEpochDay(it.occurredAt) in monthStartDay..monthEndDay
        }.forEach { payment ->
            val accountName = payment.loanPlanId
                ?.let(plansById::get)
                ?.accountId
                ?.let(accountsById::get)
                ?.name
                ?: "贷款"
            add(MonthRepaymentItem("$accountName 实际还款", payment.amountCents, occurredEpochDay(payment.occurredAt), true, MonthRepaymentSource.LOAN_PLAN, payment.id))
        }

        transfers.filter {
            it.amountCents > 0L &&
                accountsById[it.fromAccountId]?.let { account -> account.type == AccountType.ASSET.name && !account.archived } == true &&
                accountsById[it.toAccountId]?.let { account -> account.type == AccountType.CREDIT.name && !account.archived } == true &&
                occurredEpochDay(it.occurredAt) in monthStartDay..monthEndDay
        }.forEach { payment ->
            add(MonthRepaymentItem("${accountsById[payment.toAccountId]?.name ?: "信用账户"} 实际还款", payment.amountCents, occurredEpochDay(payment.occurredAt), true, MonthRepaymentSource.CREDIT_STATEMENT, payment.id))
        }
    }.sortedWith(compareBy({ it.paid }, { it.dueDay }, { it.label }))
}
