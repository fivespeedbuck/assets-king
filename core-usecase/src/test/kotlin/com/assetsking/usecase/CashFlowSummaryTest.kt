package com.assetsking.usecase

import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.model.AccountType
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CashFlowSummaryTest {
    @Test
    fun actualRepaymentsReduceCashBalanceWithoutInflatingOrdinaryExpense() {
        val transactions = listOf(
            tx("income", 698_983L, "INCOME"),
            tx("expense", 320_350L, "EXPENSE", reimbursedCents = 20_000L),
            tx("fee", 5_000L, "FEE"),
            tx("repayment", 300_000L, "LOAN_PAYMENT"),
            tx("prepayment", 42_000L, "LOAN_PREPAYMENT"),
            tx("disbursement", 900_000L, "LOAN_DISBURSEMENT")
        )

        assertEquals(
            CashFlowSummary(
                incomeCents = 698_983L,
                expenseCents = 305_350L,
                repaymentCents = 342_000L
            ),
            cashFlowSummary(transactions)
        )
        assertEquals(51_633L, cashFlowSummary(transactions).balanceCents)
    }

    @Test
    fun linkedRefundOnlyOffsetsExpenseInsideTheSameRange() {
        val transactions = listOf(
            tx("expense", 100_000L, "EXPENSE"),
            tx("linked-refund", 30_000L, "REFUND", refundOfId = "expense"),
            tx("cross-range-refund", 50_000L, "REFUND", refundOfId = "missing")
        )

        assertEquals(70_000L, cashFlowSummary(transactions).expenseCents)
    }

    @Test
    fun selectedMonthSwitchesBetweenRepaymentAndNoRepaymentCashFlow() {
        val zone = ZoneId.of("Asia/Shanghai")
        val july = YearMonth.of(2026, 7)
        val august = YearMonth.of(2026, 8)
        val transactions = listOf(
            tx("july-income", 500_000L, "INCOME", occurredAt = july.atDay(8).atStartOfDay(zone).toInstant().toEpochMilli()),
            tx("july-expense", 120_000L, "EXPENSE", occurredAt = july.atDay(9).atStartOfDay(zone).toInstant().toEpochMilli()),
            tx("august-income", 300_000L, "INCOME", occurredAt = august.atDay(8).atStartOfDay(zone).toInstant().toEpochMilli()),
            tx("august-expense", 200_000L, "EXPENSE", occurredAt = august.atDay(9).atStartOfDay(zone).toInstant().toEpochMilli()),
            tx("august-repayment", 150_000L, "LOAN_PAYMENT", occurredAt = august.atDay(10).atStartOfDay(zone).toInstant().toEpochMilli())
        )

        assertEquals(CashFlowSummary(500_000L, 120_000L, 0L), cashFlowSummaryForMonth(transactions, july, zone))
        assertEquals(CashFlowSummary(300_000L, 200_000L, 150_000L), cashFlowSummaryForMonth(transactions, august, zone))
        assertEquals(-50_000L, cashFlowSummaryForMonth(transactions, august, zone).balanceCents)
    }

    @Test
    fun creditCardRepaymentUsesOnlyAssetToCreditTransfersAndCountsOnce() {
        val accounts = listOf(
            AccountEntity("cash", "工资卡", AccountType.ASSET.name, 500_000L),
            AccountEntity("card-a", "信用卡A", AccountType.CREDIT.name, 200_000L),
            AccountEntity("card-b", "信用卡B", AccountType.CREDIT.name, 100_000L)
        )
        val transfers = listOf(
            TransferEntity("real-payment", "cash", "card-a", 100_000L, 1L),
            TransferEntity("card-to-card", "card-a", "card-b", 50_000L, 1L)
        )

        assertEquals(
            CashFlowSummary(0L, 0L, 100_000L),
            cashFlowSummary(emptyList(), transfers, accounts)
        )
    }

    private fun tx(
        id: String,
        amountCents: Long,
        type: String,
        refundOfId: String? = null,
        reimbursedCents: Long = 0L,
        occurredAt: Long = 1L
    ) = TransactionEntity(
        id = id,
        accountId = "cash",
        amountCents = amountCents,
        type = type,
        category = "测试",
        occurredAt = occurredAt,
        refundOfId = refundOfId,
        reimbursedCents = reimbursedCents
    )
}
