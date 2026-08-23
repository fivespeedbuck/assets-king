package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountDetailPresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun tx(id: String, accountId: String, date: LocalDate): TransactionEntity = TransactionEntity(
        id = id,
        accountId = accountId,
        amountCents = 1_000L,
        type = TransactionType.EXPENSE.name,
        category = "购物",
        occurredAt = date.atStartOfDay(zone).toInstant().toEpochMilli()
    )

    @Test
    fun huabeiFifthStatementSplitsCurrentStatementAndUnbilledFlows() {
        val today = LocalDate.of(2026, 8, 23)
        val transactions = listOf(
            tx("before", "huabei", LocalDate.of(2026, 7, 5)),
            tx("statement-start", "huabei", LocalDate.of(2026, 7, 6)),
            tx("statement-end", "huabei", LocalDate.of(2026, 8, 5)),
            tx("unbilled-start", "huabei", LocalDate.of(2026, 8, 6)),
            tx("today", "huabei", today),
            tx("other-card", "gdb", LocalDate.of(2026, 8, 1))
        )

        assertEquals(
            listOf("statement-end", "statement-start"),
            creditFlowTransactions("huabei", transactions, CreditFlowScope.STATEMENT, 5, today, zone).map { it.id }
        )
        assertEquals(
            listOf("today", "unbilled-start"),
            creditFlowTransactions("huabei", transactions, CreditFlowScope.UNBILLED, 5, today, zone).map { it.id }
        )
    }

    @Test
    fun guangfaTwentySixthUsesThePreviousMonthBeforeAugustStatementDay() {
        val window = creditCycleWindow(26, LocalDate.of(2026, 8, 23))!!

        assertEquals(LocalDate.of(2026, 6, 27), window.statementStart)
        assertEquals(LocalDate.of(2026, 7, 26), window.statementEnd)
        assertEquals(LocalDate.of(2026, 7, 27), window.unbilledStart)
        assertEquals(LocalDate.of(2026, 8, 23), window.unbilledEnd)
    }

    @Test
    fun missingStatementDayOnlyAllowsTheAccountWideFlow() {
        val transactions = listOf(tx("one", "huabei", LocalDate.of(2026, 8, 10)))

        assertEquals(emptyList(), creditFlowTransactions("huabei", transactions, CreditFlowScope.STATEMENT, null, LocalDate.of(2026, 8, 23), zone))
        assertEquals(listOf("one"), creditFlowTransactions("huabei", transactions, CreditFlowScope.ALL, null, LocalDate.of(2026, 8, 23), zone).map { it.id })
    }

    @Test
    fun allScopeOnlyCombinesCurrentStatementAndUnbilledInsteadOfPermanentHistory() {
        val today = LocalDate.of(2026, 8, 23)
        val transactions = listOf(
            tx("old-paid-cycle", "gdb", LocalDate.of(2026, 6, 26)),
            tx("current-statement", "gdb", LocalDate.of(2026, 7, 2)),
            tx("current-unbilled", "gdb", LocalDate.of(2026, 8, 20))
        )

        assertEquals(
            listOf("current-unbilled", "current-statement"),
            creditFlowTransactions("gdb", transactions, CreditFlowScope.ALL, 26, today, zone, 10_000L).map { it.id }
        )
    }

    @Test
    fun fullyPaidStatementDisappearsButUnbilledPurchasesRemain() {
        val today = LocalDate.of(2026, 8, 23)
        val transactions = listOf(
            tx("settled-statement", "huabei", LocalDate.of(2026, 8, 1)),
            tx("unbilled", "huabei", LocalDate.of(2026, 8, 20))
        )

        assertEquals(
            emptyList(),
            creditFlowTransactions("huabei", transactions, CreditFlowScope.STATEMENT, 5, today, zone, 0L)
        )
        assertEquals(
            listOf("unbilled"),
            creditFlowTransactions("huabei", transactions, CreditFlowScope.ALL, 5, today, zone, 0L).map { it.id }
        )
    }
}
