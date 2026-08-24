package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.database.CreditCardInstallmentAllocationEntity
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.CreditCardInstallmentPaymentMatchEntity
import com.assetsking.database.CreditCardInstallmentScheduleEntity
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.model.AccountType
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.Money
import com.assetsking.model.TransactionType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class LoanPresentationTest {
    @Test
    fun indebtedCreditAccountCreatedOnHomeIsVisibleOnLoanScreen() {
        val indebted = AccountEntity("card", "广发信用卡", AccountType.CREDIT.name, 250_000L, dueDay = 15)
        val cleared = AccountEntity("cleared", "已还清信用卡", AccountType.CREDIT.name, 0L)
        val archived = AccountEntity("archived", "已归档信用卡", AccountType.CREDIT.name, 300_000L, archived = true)
        val loan = AccountEntity("loan", "普通贷款", AccountType.LOAN.name, 5_000_000L)

        assertEquals(
            listOf(indebted),
            visibleLoanCreditAccounts(listOf(cleared, archived, loan, indebted), mapOf(indebted.id to 100_000L))
        )
    }

    @Test
    fun annualRateParsingRoundsDecimalPercentWithoutFloatingPointTruncation() {
        assertEquals(803, parseAnnualRateBps("8.03"))
        assertEquals(760, parseAnnualRateBps("7.60"))
    }

    @Test
    fun uniformPaymentChangesOnlyUnpaidInstallments() {
        val installments = listOf(
            LoanInstallment(1, 1L, Money(8_000L), Money(500L), status = InstallmentStatus.PAID),
            LoanInstallment(2, 2L, Money(7_000L), Money(400L)),
            LoanInstallment(3, 3L, Money(6_000L), Money(300L))
        )
        val result = applyUniformPaymentToUpcoming(installments, 10_000L)!!
        assertEquals(500L, result[0].interest.cents)
        assertEquals(3_000L, result[1].interest.cents)
        assertEquals(4_000L, result[2].interest.cents)
        assertEquals(10_000L, result[1].total.cents)
    }

    @Test
    fun paidPlanStatusesNeverReplaceTheSingleActualRepaymentRecord() {
        val today = LocalDate.of(2026, 8, 23)
        val account = AccountEntity("loan", "消费贷", AccountType.LOAN.name, 500_000L)
        val paidSchedule = listOf(
            LoanInstallment(1, LocalDate.of(2026, 8, 5).toEpochDay(), Money(100_000L), Money(5_000L), status = InstallmentStatus.PAID),
            LoanInstallment(2, LocalDate.of(2026, 8, 15).toEpochDay(), Money(30_000L), Money(2_705L), status = InstallmentStatus.PAID)
        )
        val plan = LoanPlanEntity(
            "plan", account.id, 500_000L, today.minusMonths(2).toEpochDay(), "CUSTOM", "[]",
            remainingPrincipalCents = 370_000L
        )
        val actualPayment = TransactionEntity(
            "payment", "cash", 342_000L, "LOAN_PAYMENT", "贷款还款",
            today.minusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            loanPlanId = plan.id
        )

        val rows = monthRepaymentItems(
            plans = listOf(plan),
            accounts = listOf(account),
            cardInstallments = emptyList(),
            cardSchedules = emptyList(),
            cardRemainingDueByCard = emptyMap(),
            transactions = listOf(actualPayment),
            today = today,
            installmentsForPlan = { paidSchedule }
        )

        assertEquals(1, rows.size)
        assertEquals(true, rows.single().paid)
        assertEquals(342_000L, rows.single().amount)
    }

    @Test
    fun monthlyRepaymentAuthorityIncludesLoansCardBillsAndCardInstallmentsWithoutZeroDebtCards() {
        val today = LocalDate.of(2026, 8, 10)
        val cash = AccountEntity("cash", "工资卡", AccountType.ASSET.name, 2_000_000L)
        val loan = AccountEntity("loan", "消费贷", AccountType.LOAN.name, 900_000L)
        val card = AccountEntity(
            "card",
            "广发信用卡",
            AccountType.CREDIT.name,
            1_500_000L,
            statementDay = 5,
            dueDay = 15,
            statementOriginalDueCents = 1_000_000L
        )
        val clearedCard = AccountEntity(
            "cleared",
            "已还清信用卡",
            AccountType.CREDIT.name,
            0L,
            statementDay = 5,
            dueDay = 15,
            statementOriginalDueCents = 500_000L
        )
        val loanInstallments = listOf(
            LoanInstallment(1, LocalDate.of(2026, 8, 2).toEpochDay(), Money(100_000L), Money(10_000L), status = InstallmentStatus.PAID),
            LoanInstallment(2, LocalDate.of(2026, 8, 5).toEpochDay(), Money(200_000L), Money(20_000L)),
            LoanInstallment(3, LocalDate.of(2026, 8, 20).toEpochDay(), Money(300_000L), Money(30_000L)),
            LoanInstallment(4, LocalDate.of(2026, 9, 20).toEpochDay(), Money(400_000L), Money(40_000L))
        )
        val plan = LoanPlanEntity(
            id = "loan-plan",
            accountId = loan.id,
            principalCents = 1_000_000L,
            startDateEpochDay = LocalDate.of(2026, 7, 1).toEpochDay(),
            repaymentMethod = "EQUAL_PAYMENT",
            installmentsJson = "[]",
            remainingPrincipalCents = 900_000L
        )
        val cardPlan = CreditCardInstallmentEntity(
            id = "card-plan",
            cardAccountId = card.id,
            label = "账单分期",
            originalPrincipalCents = 900_000L,
            remainingPrincipalCents = 800_000L,
            monthlyPaymentCents = 400_000L,
            periodsRemaining = 2,
            startDateEpochDay = today.toEpochDay()
        )
        val schedules = listOf(
            CreditCardInstallmentScheduleEntity(
                "card-paid", cardPlan.id, 1, 1, LocalDate.of(2026, 8, 3).toEpochDay(),
                350_000L, 50_000L, 0L, principalPaidCents = 350_000L, status = "PAID"
            ),
            CreditCardInstallmentScheduleEntity(
                "card-current", cardPlan.id, 1, 2, LocalDate.of(2026, 8, 18).toEpochDay(),
                350_000L, 50_000L, 0L
            ),
            CreditCardInstallmentScheduleEntity(
                "card-future", cardPlan.id, 1, 3, LocalDate.of(2026, 9, 18).toEpochDay(),
                350_000L, 50_000L, 0L
            )
        )
        fun occurredAt(date: LocalDate) = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val actualLoanPayment = TransactionEntity(
            id = "actual-loan-payment",
            accountId = "cash",
            amountCents = 342_000L,
            type = "LOAN_PAYMENT",
            category = "贷款还款",
            occurredAt = occurredAt(LocalDate.of(2026, 8, 6)),
            loanPlanId = plan.id
        )
        val actualCardPayment = TransferEntity(
            id = "actual-card-payment",
            fromAccountId = "cash",
            toAccountId = card.id,
            amountCents = 100_000L,
            occurredAt = occurredAt(LocalDate.of(2026, 8, 7))
        )

        val rows = monthRepaymentItems(
            plans = listOf(plan),
            accounts = listOf(cash, loan, card, clearedCard),
            cardInstallments = listOf(cardPlan),
            cardSchedules = schedules,
            cardRemainingDueByCard = mapOf(card.id to 600_000L, clearedCard.id to 500_000L),
            transactions = listOf(actualLoanPayment),
            transfers = listOf(actualCardPayment),
            today = today,
            installmentsForPlan = { loanInstallments }
        )
        val outstanding = rows.filterNot { it.paid }

        assertEquals(6, rows.size)
        assertEquals(4, outstanding.size)
        assertEquals(1_550_000L, outstanding.sumOf { it.amount })
        assertEquals(
            listOf(MonthRepaymentSource.LOAN_PLAN, MonthRepaymentSource.CREDIT_STATEMENT, MonthRepaymentSource.CREDIT_INSTALLMENT),
            rows.map { it.source }.distinct()
        )
        assertEquals(false, rows.any { it.sourceId == clearedCard.id })
        assertEquals(2, rows.count { it.paid })
        assertEquals(listOf(100_000L, 342_000L), rows.filter { it.paid }.map { it.amount }.sorted())
        assertEquals(false, rows.any { it.dueDay > LocalDate.of(2026, 8, 31).toEpochDay() })
    }

    @Test
    fun currentCardStatementOwnsItsInstallmentComponentWithoutDoubleCounting() {
        val today = LocalDate.of(2026, 8, 10)
        val card = AccountEntity(
            id = "cgb",
            name = "广发信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = 150_000L,
            statementDay = 5,
            dueDay = 15,
            statementOriginalDueCents = 60_000L
        )
        val plan = CreditCardInstallmentEntity(
            id = "cgb-plan",
            cardAccountId = card.id,
            label = "12期账单分期",
            originalPrincipalCents = 100_000L,
            remainingPrincipalCents = 90_000L,
            monthlyPaymentCents = 12_000L,
            periodsRemaining = 9,
            startDateEpochDay = today.minusMonths(3).toEpochDay(),
            installmentType = "STATEMENT_INSTALLMENT",
            statementCycleStartEpochDay = LocalDate.of(2026, 7, 5).toEpochDay()
        )
        val current = CreditCardInstallmentScheduleEntity(
            id = "cgb-current",
            planId = plan.id,
            revision = plan.scheduleRevision,
            number = 4,
            dueDateEpochDay = LocalDate.of(2026, 8, 15).toEpochDay(),
            principalDueCents = 10_000L,
            expectedInterestCents = 1_500L,
            expectedFeeCents = 500L
        )
        val future = current.copy(
            id = "cgb-future",
            number = 5,
            dueDateEpochDay = LocalDate.of(2026, 9, 15).toEpochDay()
        )

        val projection = creditAccountRepaymentProjection(
            account = card,
            statementRemainingCents = 60_000L,
            cardInstallments = listOf(plan),
            cardSchedules = listOf(current, future),
            today = today
        )
        val rows = monthRepaymentItems(
            plans = emptyList(),
            accounts = listOf(card),
            cardInstallments = listOf(plan),
            cardSchedules = listOf(current, future),
            cardRemainingDueByCard = mapOf(card.id to 60_000L),
            today = today
        )

        assertEquals(60_000L, projection.nextTotalCents)
        assertEquals(60_000L, projection.statementDebtCents)
        assertEquals(12_000L, projection.unbilledOrdinaryDebtCents)
        assertEquals(10_000L, projection.nextInstallmentPrincipalCents)
        assertEquals(2_000L, projection.nextInstallmentChargeCents)
        assertEquals(48_000L, projection.nextOtherDebtCents)
        assertEquals(154_000L, projection.forecastTotalRepaymentCents)
        assertEquals(LocalDate.of(2026, 9, 15).toEpochDay(), projection.nextMonthDueDay)
        assertEquals(24_000L, projection.nextMonthTotalCents)
        assertEquals(12_000L, projection.nextMonthOtherDebtCents)
        assertEquals(10_000L, projection.nextMonthInstallmentPrincipalCents)
        assertEquals(2_000L, projection.nextMonthInstallmentChargeCents)
        assertEquals(false, projection.nextMonthNeedsReview)
        assertEquals(1, rows.size)
        assertEquals(MonthRepaymentSource.CREDIT_STATEMENT, rows.single().source)
        assertEquals(60_000L, rows.single().amount)
    }

    @Test
    fun currentCycleStatementConversionAddsItsSameDayFirstInstallmentExactlyOnce() {
        val today = LocalDate.of(2026, 8, 10)
        val dueDay = LocalDate.of(2026, 8, 15).toEpochDay()
        val card = AccountEntity(
            id = "card",
            name = "信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = 100_000L,
            statementDay = 5,
            dueDay = 15
        )
        val plan = CreditCardInstallmentEntity(
            id = "new-statement-plan",
            cardAccountId = card.id,
            label = "本期账单分期",
            originalPrincipalCents = 60_000L,
            remainingPrincipalCents = 60_000L,
            monthlyPaymentCents = 12_000L,
            periodsRemaining = 6,
            startDateEpochDay = today.toEpochDay(),
            installmentType = "STATEMENT_INSTALLMENT",
            statementCycleStartEpochDay = LocalDate.of(2026, 8, 5).toEpochDay()
        )
        val firstSchedule = CreditCardInstallmentScheduleEntity(
            id = "first",
            planId = plan.id,
            revision = plan.scheduleRevision,
            number = 1,
            dueDateEpochDay = dueDay,
            principalDueCents = 10_000L,
            expectedInterestCents = 1_500L,
            expectedFeeCents = 500L
        )

        val projection = creditAccountRepaymentProjection(
            account = card,
            statementRemainingCents = 40_000L,
            cardInstallments = listOf(plan),
            cardSchedules = listOf(firstSchedule),
            today = today
        )
        val rows = monthRepaymentItems(
            plans = emptyList(),
            accounts = listOf(card),
            cardInstallments = listOf(plan),
            cardSchedules = listOf(firstSchedule),
            cardRemainingDueByCard = mapOf(card.id to 40_000L),
            today = today
        )

        assertEquals(dueDay, projection.nextDueDay)
        assertEquals(52_000L, projection.nextTotalCents)
        assertEquals(40_000L, projection.nextOtherDebtCents)
        assertEquals(10_000L, projection.nextInstallmentPrincipalCents)
        assertEquals(2_000L, projection.nextInstallmentChargeCents)
        assertEquals(1, rows.size)
        assertEquals(MonthRepaymentSource.CREDIT_STATEMENT, rows.single().source)
        assertEquals(52_000L, rows.single().amount)
    }

    @Test
    fun currentCycleStatementConversionWithFirstInstallmentNextMonthDoesNotPullItIntoTheCurrentBill() {
        val today = LocalDate.of(2026, 8, 10)
        val card = AccountEntity(
            id = "card",
            name = "信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = 100_000L,
            statementDay = 5,
            dueDay = 15
        )
        val plan = CreditCardInstallmentEntity(
            id = "new-statement-plan",
            cardAccountId = card.id,
            label = "本期账单分期",
            originalPrincipalCents = 60_000L,
            remainingPrincipalCents = 60_000L,
            monthlyPaymentCents = 12_000L,
            periodsRemaining = 6,
            startDateEpochDay = today.toEpochDay(),
            installmentType = "STATEMENT_INSTALLMENT",
            statementCycleStartEpochDay = LocalDate.of(2026, 8, 5).toEpochDay()
        )
        val firstSchedule = CreditCardInstallmentScheduleEntity(
            id = "first",
            planId = plan.id,
            revision = plan.scheduleRevision,
            number = 1,
            dueDateEpochDay = LocalDate.of(2026, 9, 15).toEpochDay(),
            principalDueCents = 10_000L,
            expectedInterestCents = 1_500L,
            expectedFeeCents = 500L
        )

        val projection = creditAccountRepaymentProjection(
            account = card,
            statementRemainingCents = 40_000L,
            cardInstallments = listOf(plan),
            cardSchedules = listOf(firstSchedule),
            today = today
        )
        val rows = monthRepaymentItems(
            plans = emptyList(),
            accounts = listOf(card),
            cardInstallments = listOf(plan),
            cardSchedules = listOf(firstSchedule),
            cardRemainingDueByCard = mapOf(card.id to 40_000L),
            today = today
        )

        assertEquals(LocalDate.of(2026, 8, 15).toEpochDay(), projection.nextDueDay)
        assertEquals(40_000L, projection.nextTotalCents)
        assertEquals(40_000L, projection.nextOtherDebtCents)
        assertEquals(0L, projection.nextInstallmentPrincipalCents)
        assertEquals(0L, projection.nextInstallmentChargeCents)
        assertEquals(LocalDate.of(2026, 9, 15).toEpochDay(), projection.nextMonthDueDay)
        assertEquals(12_000L, projection.nextMonthTotalCents)
        assertEquals(0L, projection.nextMonthOtherDebtCents)
        assertEquals(10_000L, projection.nextMonthInstallmentPrincipalCents)
        assertEquals(2_000L, projection.nextMonthInstallmentChargeCents)
        assertEquals(40_000L, rows.single().amount)
    }

    @Test
    fun currentStatementBreakdownNeverExceedsItsAuthoritativeTotal() {
        val today = LocalDate.of(2026, 8, 10)
        val card = AccountEntity(
            id = "card",
            name = "信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = 100_000L,
            statementDay = 5,
            dueDay = 15
        )
        val plan = CreditCardInstallmentEntity(
            id = "plan",
            cardAccountId = card.id,
            label = "账单分期",
            originalPrincipalCents = 80_000L,
            remainingPrincipalCents = 80_000L,
            monthlyPaymentCents = 12_000L,
            periodsRemaining = 8,
            startDateEpochDay = today.toEpochDay()
        )
        val schedule = CreditCardInstallmentScheduleEntity(
            id = "current",
            planId = plan.id,
            revision = plan.scheduleRevision,
            number = 1,
            dueDateEpochDay = LocalDate.of(2026, 8, 15).toEpochDay(),
            principalDueCents = 9_000L,
            expectedInterestCents = 2_000L,
            expectedFeeCents = 1_000L
        )

        val projection = creditAccountRepaymentProjection(
            account = card,
            statementRemainingCents = 10_000L,
            cardInstallments = listOf(plan),
            cardSchedules = listOf(schedule),
            today = today
        )

        assertEquals(10_000L, projection.nextTotalCents)
        assertEquals(9_000L, projection.nextInstallmentPrincipalCents)
        assertEquals(1_000L, projection.nextInstallmentChargeCents)
        assertEquals(0L, projection.nextOtherDebtCents)
        assertEquals(true, projection.nextBreakdownNeedsReview)
        assertEquals(
            projection.nextTotalCents,
            projection.nextOtherDebtCents + projection.nextInstallmentPrincipalCents + projection.nextInstallmentChargeCents
        )
    }

    @Test
    fun statementAmountRemainsAuthoritativeWhenRepaymentDatesNeedConfiguration() {
        val today = LocalDate.of(2026, 8, 10)
        val card = AccountEntity("card", "信用卡", AccountType.CREDIT.name, 80_000L)

        val projection = creditAccountRepaymentProjection(card, 60_000L, emptyList(), emptyList(), today)

        assertEquals(true, projection.statementIsAuthority)
        assertEquals(60_000L, projection.statementDebtCents)
        assertEquals(60_000L, projection.nextTotalCents)
        assertEquals(null, projection.nextDueDay)
        assertEquals(null, projection.nextMonthDueDay)
        assertEquals(true, projection.nextMonthNeedsReview)
    }

    @Test
    fun unbilledOrdinaryDebtUsesTheNextFutureRepaymentDate() {
        val today = LocalDate.of(2026, 8, 23)
        val card = AccountEntity(
            "card", "信用卡", AccountType.CREDIT.name, 80_000L,
            statementDay = 5, dueDay = 15
        )

        val projection = creditAccountRepaymentProjection(card, 0L, emptyList(), emptyList(), today)

        assertEquals(80_000L, projection.unbilledOrdinaryDebtCents)
        assertEquals(80_000L, projection.nextTotalCents)
        assertEquals(LocalDate.of(2026, 9, 15).toEpochDay(), projection.nextDueDay)
    }

    @Test
    fun staleStatementLargerThanAccountBalanceIsClampedAndExposedForReview() {
        val today = LocalDate.of(2026, 8, 10)
        val card = AccountEntity(
            "card", "信用卡", AccountType.CREDIT.name, 100_000L,
            statementDay = 5, dueDay = 15
        )

        val projection = creditAccountRepaymentProjection(card, 120_000L, emptyList(), emptyList(), today)
        val row = monthRepaymentItems(
            plans = emptyList(),
            accounts = listOf(card),
            cardInstallments = emptyList(),
            cardSchedules = emptyList(),
            cardRemainingDueByCard = mapOf(card.id to 120_000L),
            today = today
        ).single()

        assertEquals(100_000L, projection.statementDebtCents)
        assertEquals(true, projection.accountDebtNeedsReview)
        assertEquals(true, projection.nextBreakdownNeedsReview)
        assertEquals(true, projection.nextMonthNeedsReview)
        assertEquals(100_000L, row.amount)
    }

    @Test
    fun accountDebtConflictMarksTheNextBreakdownForReviewEvenWithoutAStatement() {
        val today = LocalDate.of(2026, 8, 23)
        val card = AccountEntity(
            "card", "信用卡", AccountType.CREDIT.name, 50_000L,
            statementDay = 5, dueDay = 15
        )
        val plan = CreditCardInstallmentEntity(
            "plan", card.id, "消费分期", 80_000L, 80_000L, 12_000L,
            periodsRemaining = 8,
            startDateEpochDay = today.toEpochDay()
        )
        val schedule = CreditCardInstallmentScheduleEntity(
            "next", plan.id, plan.scheduleRevision, 1,
            LocalDate.of(2026, 9, 15).toEpochDay(),
            10_000L, 1_500L, 500L
        )

        val projection = creditAccountRepaymentProjection(
            card, 0L, listOf(plan), listOf(schedule), today
        )

        assertEquals(true, projection.accountDebtNeedsReview)
        assertEquals(true, projection.nextBreakdownNeedsReview)
        assertEquals(
            projection.nextTotalCents,
            projection.nextOtherDebtCents +
                projection.nextInstallmentPrincipalCents +
                projection.nextInstallmentChargeCents
        )
    }

    @Test
    fun unbilledCardProjectsNextPaymentFromOtherDebtAndTheNextInstallment() {
        val today = LocalDate.of(2026, 8, 10)
        val card = AccountEntity("cgb", "广发信用卡", AccountType.CREDIT.name, 100_000L, statementDay = 5, dueDay = 15)
        val plan = CreditCardInstallmentEntity(
            id = "plan",
            cardAccountId = card.id,
            label = "消费分期",
            originalPrincipalCents = 80_000L,
            remainingPrincipalCents = 80_000L,
            monthlyPaymentCents = 12_000L,
            periodsRemaining = 8,
            startDateEpochDay = today.toEpochDay()
        )
        val schedule = CreditCardInstallmentScheduleEntity(
            id = "next",
            planId = plan.id,
            revision = plan.scheduleRevision,
            number = 1,
            dueDateEpochDay = LocalDate.of(2026, 9, 15).toEpochDay(),
            principalDueCents = 10_000L,
            expectedInterestCents = 2_000L,
            expectedFeeCents = 0L
        )

        val projection = creditAccountRepaymentProjection(card, 0L, listOf(plan), listOf(schedule), today)

        assertEquals(20_000L, projection.otherDebtCents)
        assertEquals(0L, projection.statementDebtCents)
        assertEquals(20_000L, projection.unbilledOrdinaryDebtCents)
        assertEquals(32_000L, projection.nextTotalCents)
        assertEquals(20_000L, projection.nextOtherDebtCents)
        assertEquals(10_000L, projection.nextInstallmentPrincipalCents)
        assertEquals(2_000L, projection.nextInstallmentChargeCents)
        assertEquals(LocalDate.of(2026, 9, 15).toEpochDay(), projection.nextMonthDueDay)
        assertEquals(32_000L, projection.nextMonthTotalCents)
        assertEquals(20_000L, projection.nextMonthOtherDebtCents)
        assertEquals(10_000L, projection.nextMonthInstallmentPrincipalCents)
        assertEquals(2_000L, projection.nextMonthInstallmentChargeCents)
    }

    @Test
    fun ordinaryDebtDueFirstDoesNotPullInALaterInstallment() {
        val today = LocalDate.of(2026, 8, 23)
        val card = AccountEntity(
            "card", "信用卡", AccountType.CREDIT.name, 100_000L,
            statementDay = 5, dueDay = 15
        )
        val plan = CreditCardInstallmentEntity(
            "plan", card.id, "消费分期", 80_000L, 80_000L, 12_000L,
            periodsRemaining = 8,
            startDateEpochDay = today.toEpochDay()
        )
        val octoberSchedule = CreditCardInstallmentScheduleEntity(
            "october", plan.id, plan.scheduleRevision, 1,
            LocalDate.of(2026, 10, 15).toEpochDay(),
            10_000L, 1_500L, 500L
        )

        val projection = creditAccountRepaymentProjection(
            card, 0L, listOf(plan), listOf(octoberSchedule), today
        )

        assertEquals(LocalDate.of(2026, 9, 15).toEpochDay(), projection.nextDueDay)
        assertEquals(20_000L, projection.nextTotalCents)
        assertEquals(20_000L, projection.nextOtherDebtCents)
        assertEquals(0L, projection.nextInstallmentPrincipalCents)
        assertEquals(0L, projection.nextInstallmentChargeCents)
    }

    @Test
    fun installmentDueFirstOnlyCombinesSchedulesOnThatEarliestDate() {
        val today = LocalDate.of(2026, 8, 27)
        val card = AccountEntity(
            "card", "信用卡", AccountType.CREDIT.name, 100_000L,
            statementDay = 26, dueDay = 15
        )
        val firstPlan = CreditCardInstallmentEntity(
            "first-plan", card.id, "第一笔分期", 40_000L, 40_000L, 7_000L,
            periodsRemaining = 6,
            startDateEpochDay = today.toEpochDay()
        )
        val secondPlan = CreditCardInstallmentEntity(
            "second-plan", card.id, "第二笔分期", 40_000L, 40_000L, 6_000L,
            periodsRemaining = 7,
            startDateEpochDay = today.toEpochDay()
        )
        val septemberFirst = CreditCardInstallmentScheduleEntity(
            "september-first", firstPlan.id, firstPlan.scheduleRevision, 1,
            LocalDate.of(2026, 9, 15).toEpochDay(),
            5_000L, 1_000L, 0L
        )
        val septemberSecond = CreditCardInstallmentScheduleEntity(
            "september-second", secondPlan.id, secondPlan.scheduleRevision, 1,
            LocalDate.of(2026, 9, 15).toEpochDay(),
            4_000L, 500L, 500L
        )
        val october = septemberFirst.copy(
            id = "october",
            number = 2,
            dueDateEpochDay = LocalDate.of(2026, 10, 15).toEpochDay(),
            principalDueCents = 6_000L
        )

        val projection = creditAccountRepaymentProjection(
            card,
            0L,
            listOf(firstPlan, secondPlan),
            listOf(septemberFirst, septemberSecond, october),
            today
        )

        assertEquals(LocalDate.of(2026, 9, 15).toEpochDay(), projection.nextDueDay)
        assertEquals(11_000L, projection.nextTotalCents)
        assertEquals(9_000L, projection.nextInstallmentPrincipalCents)
        assertEquals(2_000L, projection.nextInstallmentChargeCents)
        assertEquals(0L, projection.nextOtherDebtCents)
    }

    @Test
    fun ordinaryLoanPlanOnlyOffersActiveLoanAccounts() {
        val accounts = listOf(
            AccountEntity("cash", "宁波银行", AccountType.ASSET.name, 10_000L),
            AccountEntity("card", "广发信用卡", AccountType.CREDIT.name, 20_000L),
            AccountEntity("loan", "招行消费贷", AccountType.LOAN.name, 30_000L),
            AccountEntity("archived", "旧贷款", AccountType.LOAN.name, 0L, archived = true)
        )

        assertEquals(listOf("招行消费贷"), eligibleLoanAccounts(accounts).map { it.name })
    }

    @Test
    fun legacyPlanWithCreditAccountFallsBackToAnEligibleLoanAccountForRepair() {
        val loan = AccountEntity("loan", "招行消费贷", AccountType.LOAN.name, 30_000L)

        assertEquals("loan", initialLoanPlanAccountId("huabei", listOf(loan)))
        assertEquals("loan", initialLoanPlanAccountId("loan", listOf(loan)))
        assertEquals("", initialLoanPlanAccountId("huabei", emptyList()))
    }

    @Test
    fun invalidLoanPlansAndCreditCardToCreditCardTransfersNeverEnterTheAuthority() {
        val today = LocalDate.of(2026, 8, 10)
        val cardA = AccountEntity("card-a", "卡A", AccountType.CREDIT.name, 200_000L, dueDay = 18)
        val cardB = AccountEntity("card-b", "卡B", AccountType.CREDIT.name, 300_000L, dueDay = 20)
        val legacyPlan = LoanPlanEntity(
            "legacy", cardA.id, 100_000L, today.minusMonths(1).toEpochDay(), "CUSTOM", "[]",
            remainingPrincipalCents = 100_000L
        )
        val cardToCard = TransferEntity(
            "card-transfer", cardA.id, cardB.id, 50_000L,
            today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

        val rows = monthRepaymentItems(
            plans = listOf(legacyPlan),
            accounts = listOf(cardA, cardB),
            cardInstallments = emptyList(),
            cardSchedules = emptyList(),
            cardRemainingDueByCard = emptyMap(),
            transfers = listOf(cardToCard),
            today = today,
            installmentsForPlan = {
                listOf(LoanInstallment(1, today.plusDays(5).toEpochDay(), Money(100_000L), Money(5_000L)))
            }
        )

        assertEquals(false, rows.any { it.source == MonthRepaymentSource.LOAN_PLAN })
        assertEquals(false, rows.any { it.paid })
    }

    @Test
    fun nearTermWindowAndMonthListUseTheSameRowsAcrossMonthEnd() {
        val today = LocalDate.of(2026, 8, 31)
        val loan = AccountEntity("loan", "消费贷", AccountType.LOAN.name, 100_000L)
        val plan = LoanPlanEntity("plan", loan.id, 100_000L, today.minusMonths(1).toEpochDay(), "CUSTOM", "[]")
        val septemberInstallment = LoanInstallment(1, LocalDate.of(2026, 9, 1).toEpochDay(), Money(90_000L), Money(10_000L))
        fun rows(through: LocalDate) = monthRepaymentItems(
            plans = listOf(plan),
            accounts = listOf(loan),
            cardInstallments = emptyList(),
            cardSchedules = emptyList(),
            cardRemainingDueByCard = emptyMap(),
            today = today,
            outstandingThrough = through,
            installmentsForPlan = { listOf(septemberInstallment) }
        )

        assertEquals(emptyList(), rows(LocalDate.of(2026, 8, 31)))
        assertEquals(100_000L, rows(LocalDate.of(2026, 9, 2)).single().amount)
    }

    @Test
    fun nearbyInstallmentsKeepTheLastPaidAndNextTwoUnpaid() {
        val installments = (1..6).map { number ->
            LoanInstallment(
                number = number,
                dueDateEpochDay = 20_000L + number,
                principal = Money(100_000L),
                interest = Money(10_000L),
                fee = Money(0L),
                status = if (number == 1) InstallmentStatus.PAID else InstallmentStatus.UPCOMING
            )
        }

        assertEquals(listOf(1, 2, 3), nearbyLoanInstallments(installments).map { it.number })
    }

    @Test
    fun nearbyCardSchedulesKeepTheLastPaidAndNextTwoUnpaid() {
        val schedules = (1..12).map { number ->
            CreditCardInstallmentScheduleEntity(
                id = "schedule-$number",
                planId = "plan",
                revision = 1,
                number = number,
                dueDateEpochDay = 20_000L + number,
                principalDueCents = 10_000L,
                expectedInterestCents = 0L,
                expectedFeeCents = 0L,
                status = if (number <= 4) "PAID" else "UPCOMING"
            )
        }

        assertEquals(listOf(4, 5, 6), nearbyCardInstallmentSchedules(schedules).map { it.number })
    }

    @Test
    fun nearbyCardSchedulesUseTheLastThreeWhenEverythingIsPaid() {
        val schedules = (1..12).map { number ->
            CreditCardInstallmentScheduleEntity(
                id = "schedule-$number",
                planId = "plan",
                revision = 1,
                number = number,
                dueDateEpochDay = 20_000L + number,
                principalDueCents = 10_000L,
                expectedInterestCents = 0L,
                expectedFeeCents = 0L,
                status = "PAID"
            )
        }

        assertEquals(listOf(10, 11, 12), nearbyCardInstallmentSchedules(schedules).map { it.number })
    }

    @Test
    fun partialLegacyScheduleExposesUnscheduledPrincipalInsteadOfPretendingToBeTheTotalRepayment() {
        val plan = LoanPlanEntity(
            id = "legacy-partial",
            accountId = "loan",
            principalCents = 8_000_000L,
            startDateEpochDay = 20_000L,
            repaymentMethod = "EQUAL_PAYMENT",
            installmentsJson = "[]",
            remainingPrincipalCents = 7_000_000L,
            earlyRepaidCents = 1_000_000L
        )
        val installments = listOf(
            LoanInstallment(1, 20_030L, Money(300_000L), Money(42_000L), status = InstallmentStatus.PAID),
            LoanInstallment(2, 20_061L, Money(305_000L), Money(37_000L)),
            LoanInstallment(3, 20_092L, Money(310_000L), Money(32_000L)),
            LoanInstallment(4, 20_122L, Money(315_000L), Money(27_000L)),
            LoanInstallment(5, 20_153L, Money(320_000L), Money(22_000L)),
            LoanInstallment(6, 20_183L, Money(325_000L), Money(17_000L))
        )

        assertEquals(5_425_000L, loanSchedulePrincipalGapCents(plan, installments))
    }

    @Test
    fun completeFutureScheduleHasNoPrincipalCoverageWarning() {
        val plan = LoanPlanEntity(
            id = "complete",
            accountId = "loan",
            principalCents = 1_000_000L,
            startDateEpochDay = 20_000L,
            repaymentMethod = "EQUAL_PAYMENT",
            installmentsJson = "[]",
            remainingPrincipalCents = 700_000L
        )
        val installments = listOf(
            LoanInstallment(1, 20_030L, Money(300_000L), Money(10_000L), status = InstallmentStatus.PAID),
            LoanInstallment(2, 20_061L, Money(350_000L), Money(8_000L)),
            LoanInstallment(3, 20_092L, Money(350_000L), Money(4_000L))
        )

        assertEquals(0L, loanSchedulePrincipalGapCents(plan, installments))
    }

    @Test
    fun debtCompositionExplainsTheWholeDebtWithoutCountingCardInstallmentsTwice() {
        val items = debtComposition(
            cardDebtCents = 3_000_000L,
            cardInstallmentRemainingCents = 1_200_000L,
            loanAccountDebtCents = 2_000_000L,
            loanPlanDebtCents = 7_000_000L,
            accruedInterestCents = 42_000L
        )

        assertEquals(listOf("贷款本金", "信用分期", "信用账户账款", "逾期息费"), items.map { it.label })
        assertEquals(listOf(9_000_000L, 1_200_000L, 1_800_000L, 42_000L), items.map { it.cents })
        assertEquals(12_042_000L, items.sumOf { it.cents })
    }

    @Test
    fun statementPrincipalIsAllocatedAcrossOldestSelectedPurchases() {
        val candidates = listOf(
            CardInstallmentCandidate("new", "card", "花呗", "后买", 300L, 2_000L, 0L, 0L, 2_000L),
            CardInstallmentCandidate("old", "card", "花呗", "先买", 100L, 1_200L, 0L, 0L, 1_200L),
            CardInstallmentCandidate("middle", "card", "花呗", "中间", 200L, 1_500L, 0L, 0L, 1_500L)
        )

        val allocations = allocateStatementPrincipal(candidates, 2_000L)

        assertEquals(listOf("old", "middle"), allocations.map { it.transactionId })
        assertEquals(listOf(1_200L, 800L), allocations.map { it.principalCents })
    }

    @Test
    fun threeFiveHundredPurchasesExposeTheExactStatementLimitedPrincipal() {
        val selected = (1..3).map { index ->
            CardInstallmentCandidate(
                "purchase-$index", "card", "广发信用卡", "消费$index", index.toLong(),
                50_000L, 0L, 0L, 50_000L, CardInstallmentBillingStatus.POSTED
            )
        }

        assertEquals(
            150_000L,
            cardInstallmentPrincipalLimit(CardInstallmentBillingStatus.POSTED, 150_000L, selected)
        )
        assertEquals(
            100_000L,
            cardInstallmentPrincipalLimit(CardInstallmentBillingStatus.POSTED, 100_000L, selected)
        )
    }

    @Test
    fun statementInstallmentOnlyOffersConfiguredCardsWithCurrentAmountAndCandidates() {
        val ready = AccountEntity(
            "ready",
            "广发信用卡",
            AccountType.CREDIT.name,
            300_000L,
            statementDay = 8,
            dueDay = 23
        )
        val missingDueDate = AccountEntity(
            "missing",
            "花呗",
            AccountType.CREDIT.name,
            100_000L,
            statementDay = 8
        )
        val candidates = listOf(
            CardInstallmentCandidate("ready-purchase", ready.id, ready.name, "待用户核对", 1L, 20_000L, 0L, 0L, 20_000L),
            CardInstallmentCandidate("missing-date", missingDueDate.id, missingDueDate.name, "日期不全", 2L, 10_000L, 0L, 0L, 10_000L)
        )

        val eligible = eligibleStatementInstallmentAccounts(
            accounts = listOf(ready, missingDueDate),
            cardRemainingDueByCard = mapOf(ready.id to 50_000L, missingDueDate.id to 10_000L),
            candidates = candidates
        )

        assertEquals(listOf(ready.id), eligible.map { it.id })
    }

    @Test
    fun creditDueDateFollowsTheMostRecentStatementCycle() {
        val sameMonth = AccountEntity(
            "same",
            "花呗",
            AccountType.CREDIT.name,
            30_000L,
            statementDay = 8,
            dueDay = 23
        )
        val crossMonth = AccountEntity(
            "cross",
            "信用卡",
            AccountType.CREDIT.name,
            30_000L,
            statementDay = 28,
            dueDay = 20
        )

        assertEquals(java.time.LocalDate.of(2026, 8, 23), currentCreditDueDate(sameMonth, java.time.LocalDate.of(2026, 8, 13)))
        assertEquals(java.time.LocalDate.of(2026, 8, 20), currentCreditDueDate(crossMonth, java.time.LocalDate.of(2026, 8, 23)))
        assertEquals(java.time.LocalDate.of(2026, 9, 20), currentCreditDueDate(crossMonth, java.time.LocalDate.of(2026, 8, 29)))
    }

    @Test
    fun nextCreditDueDateCoversSameAndCrossMonthCyclesBeforeAndAfterStatementDay() {
        val sameMonth = AccountEntity(
            "same", "同月还款卡", AccountType.CREDIT.name, 30_000L,
            statementDay = 5, dueDay = 15
        )
        val crossMonth = AccountEntity(
            "cross", "跨月还款卡", AccountType.CREDIT.name, 30_000L,
            statementDay = 26, dueDay = 15
        )

        assertEquals(LocalDate.of(2026, 8, 15), nextCreditDueDate(sameMonth, LocalDate.of(2026, 8, 1)))
        assertEquals(LocalDate.of(2026, 9, 15), nextCreditDueDate(sameMonth, LocalDate.of(2026, 8, 23)))
        assertEquals(LocalDate.of(2026, 9, 15), nextCreditDueDate(crossMonth, LocalDate.of(2026, 8, 23)))
        assertEquals(LocalDate.of(2026, 10, 15), nextCreditDueDate(crossMonth, LocalDate.of(2026, 8, 27)))
    }

    @Test
    fun repaymentProgressUsesPaidPlusRemainingAsTheMonthlyPlan() {
        assertEquals(0.25f, repaymentProgress(100_000L, 300_000L))
        assertEquals(1f, repaymentProgress(100_000L, 0L))
        assertEquals(0f, repaymentProgress(0L, 0L))
    }

    @Test
    fun installmentCandidatesOnlyExposeUnrefundedUnallocatedOutstandingCardExpense() {
        val card = AccountEntity(
            id = "card",
            name = "微信信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = 80_000L
        )
        val expense = TransactionEntity(
            id = "expense",
            accountId = card.id,
            amountCents = 100_000L,
            type = TransactionType.EXPENSE.name,
            category = "数码",
            merchant = "耳机",
            occurredAt = 2_000L
        )
        val refund = TransactionEntity(
            id = "refund",
            accountId = card.id,
            amountCents = 10_000L,
            type = TransactionType.REFUND.name,
            category = "数码",
            occurredAt = 3_000L,
            refundOfId = expense.id
        )
        val plan = CreditCardInstallmentEntity(
            id = "plan",
            cardAccountId = card.id,
            label = "旧分期",
            originalPrincipalCents = 30_000L,
            remainingPrincipalCents = 30_000L,
            monthlyPaymentCents = 10_000L,
            periodsRemaining = 3,
            startDateEpochDay = 20_000L
        )
        val allocation = CreditCardInstallmentAllocationEntity(
            planId = plan.id,
            transactionId = expense.id,
            allocatedPrincipalCents = 30_000L,
            createdAt = 1L
        )

        val candidates = cardInstallmentCandidates(
            transactions = listOf(expense, refund),
            accounts = listOf(card),
            plans = listOf(plan),
            allocations = listOf(allocation)
        )

        assertEquals(1, candidates.size)
        assertEquals("耳机", candidates.single().title)
        assertEquals(50_000L, candidates.single().availablePrincipalCents)
    }

    @Test
    fun installmentCandidatesUseRemainingPrincipalForCardCapacity() {
        val card = AccountEntity(
            id = "card",
            name = "微信信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = 80_000L
        )
        val expense = TransactionEntity(
            id = "expense",
            accountId = card.id,
            amountCents = 100_000L,
            type = TransactionType.EXPENSE.name,
            category = "数码",
            occurredAt = 2_000L
        )
        val plan = CreditCardInstallmentEntity(
            id = "plan",
            cardAccountId = card.id,
            label = "已还部分",
            originalPrincipalCents = 30_000L,
            remainingPrincipalCents = 10_000L,
            monthlyPaymentCents = 10_000L,
            periodsRemaining = 1,
            startDateEpochDay = 20_000L
        )
        val allocation = CreditCardInstallmentAllocationEntity(
            planId = plan.id,
            transactionId = expense.id,
            allocatedPrincipalCents = 30_000L,
            createdAt = 1L
        )

        val candidates = cardInstallmentCandidates(
            transactions = listOf(expense),
            accounts = listOf(card),
            plans = listOf(plan),
            allocations = listOf(allocation)
        )

        assertEquals(70_000L, candidates.single().availablePrincipalCents)
    }

    @Test
    fun installmentCandidatePickerFiltersByCardAndMerchantOrAmount() {
        val candidates = listOf(
            CardInstallmentCandidate("a", "gdb", "广发信用卡", "万达影城", 1L, 7_600L, 0L, 0L, 7_600L),
            CardInstallmentCandidate("b", "cmb", "招商信用卡", "京东", 2L, 12_420L, 0L, 0L, 12_420L),
            CardInstallmentCandidate("c", "gdb", "广发信用卡", "滴滴出行", 3L, 12_600L, 0L, 0L, 12_600L)
        )

        assertEquals(listOf("万达影城"), filterCardInstallmentCandidates(candidates, "gdb", "76.00").map { it.title })
        assertEquals(listOf("京东"), filterCardInstallmentCandidates(candidates, "", "京东").map { it.title })
        assertEquals(listOf("万达影城", "滴滴出行"), filterCardInstallmentCandidates(candidates, "gdb", "").map { it.title })
    }

    @Test
    fun installmentCandidatesAreClassifiedByTheCardsCurrentStatementWindow() {
        val today = LocalDate.of(2026, 8, 23)
        val zone = java.time.ZoneId.systemDefault()
        fun occurredAt(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val card = AccountEntity(
            "card", "广发信用卡", AccountType.CREDIT.name, 100_000L,
            statementDay = 8, dueDay = 23, statementOriginalDueCents = 60_000L
        )
        fun expense(id: String, date: LocalDate) = TransactionEntity(
            id, card.id, 20_000L, TransactionType.EXPENSE.name, "购物", occurredAt(date)
        )

        val candidates = cardInstallmentCandidates(
            transactions = listOf(
                expense("posted", LocalDate.of(2026, 7, 20)),
                expense("unbilled", LocalDate.of(2026, 8, 20))
            ),
            accounts = listOf(card),
            plans = emptyList(),
            allocations = emptyList(),
            today = today,
            zoneId = zone
        ).associateBy { it.transactionId }

        assertEquals(CardInstallmentBillingStatus.POSTED, candidates.getValue("posted").billingStatus)
        assertEquals(CardInstallmentBillingStatus.UNBILLED, candidates.getValue("unbilled").billingStatus)
    }

    @Test
    fun pendingPaymentConfirmationsOnlyExposeUnresolvedCandidates() {
        val card = AccountEntity("card", "广发信用卡", AccountType.CREDIT.name, 20_000L)
        val plans = listOf(
            CreditCardInstallmentEntity("plan-a", card.id, "手机分期", 10_000L, 10_000L, 10_000L, periodsRemaining = 1, startDateEpochDay = 21_000L),
            CreditCardInstallmentEntity("plan-b", card.id, "家电分期", 10_000L, 10_000L, 10_000L, periodsRemaining = 1, startDateEpochDay = 21_000L)
        )
        val schedules = plans.mapIndexed { index, plan ->
            CreditCardInstallmentScheduleEntity(
                id = "schedule-$index",
                planId = plan.id,
                revision = 1,
                number = 1,
                dueDateEpochDay = 21_000L,
                principalDueCents = 10_000L,
                expectedInterestCents = 0L,
                expectedFeeCents = 0L
            )
        }
        val transfer = TransferEntity("transfer", "cash", card.id, 10_000L, 123L)
        val matches = schedules.map { schedule ->
            CreditCardInstallmentPaymentMatchEntity(
                transferId = transfer.id,
                scheduleId = schedule.id,
                planId = schedule.planId,
                paymentCents = transfer.amountCents,
                principalCents = 10_000L,
                status = "PENDING",
                source = "AUTO",
                createdAt = 123L
            )
        } + CreditCardInstallmentPaymentMatchEntity(
            transferId = "resolved",
            scheduleId = schedules.first().id,
            planId = plans.first().id,
            paymentCents = 10_000L,
            principalCents = 10_000L,
            status = "AUTO_MATCHED",
            source = "AUTO",
            createdAt = 124L
        )

        val pending = pendingCardPaymentConfirmations(matches, listOf(transfer), schedules, plans, listOf(card))

        assertEquals(1, pending.size)
        assertEquals("广发信用卡", pending.single().cardLabel)
        assertEquals(listOf("家电分期", "手机分期"), pending.single().candidates.map { it.planLabel })
    }

    @Test
    fun fixedPaymentForecastExposesTheTwoHundredYuanChargeInsteadOfHidingIt() {
        val plan = CreditCardInstallmentEntity(
            id = "statement-plan",
            cardAccountId = "card",
            label = "账单分期",
            originalPrincipalCents = 100_000L,
            remainingPrincipalCents = 100_000L,
            monthlyPaymentCents = 10_000L,
            periodsRemaining = 12,
            startDateEpochDay = 20_000L,
            installmentType = "STATEMENT_INSTALLMENT",
            installmentCount = 12,
            status = "ACTIVE",
            scheduleRevision = 1
        )
        val schedules = (1..12).map { number ->
            CreditCardInstallmentScheduleEntity(
                id = "schedule-$number",
                planId = plan.id,
                revision = 1,
                number = number,
                dueDateEpochDay = 20_000L + number,
                principalDueCents = if (number <= 4) 8_334L else 8_333L,
                expectedInterestCents = 0L,
                expectedFeeCents = 0L,
                expectedUnclassifiedChargeCents = if (number <= 4) 1_666L else 1_667L,
                status = "UPCOMING"
            )
        }

        val forecast = creditInstallmentForecastUi(plan, schedules)

        assertEquals(100_000L, forecast.remainingPrincipalCents)
        assertEquals(20_000L, forecast.forecastChargeCents)
        assertEquals(20_000L, forecast.unclassifiedChargeCents)
        assertEquals(120_000L, forecast.totalRepaymentCents)
    }
}
