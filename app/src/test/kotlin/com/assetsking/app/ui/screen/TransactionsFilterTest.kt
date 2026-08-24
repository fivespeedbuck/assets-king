package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.MerchantEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import com.assetsking.ui.theme.BalanceBlue
import com.assetsking.ui.theme.ExpenseRed
import com.assetsking.ui.theme.DeficitRed
import com.assetsking.ui.theme.IncomeGreen
import com.assetsking.ui.theme.RepaymentPurple
import com.assetsking.ui.theme.ReimbursementYellow
import com.assetsking.ui.theme.RecurringDebitOrange
import com.assetsking.ui.theme.PendingOrange
import com.assetsking.ui.theme.transactionCashFlowColor
import com.assetsking.usecase.CashFlowSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionsFilterTest {

    @Test
    fun uncategorizedInternalCodeNeverLeaksIntoTransactionList() {
        assertEquals("", transactionListCategoryLabel("REIMBURSEMENT", "UNCATEGORIZED", null))
        assertEquals("", transactionListCategoryLabel("REFUND", "UNCATEGORIZED", null))
        assertEquals("", transactionListCategoryLabel("LOAN_PAYMENT", "UNCATEGORIZED", null))
        assertEquals("未分类", transactionListCategoryLabel("EXPENSE", "UNCATEGORIZED", null))
        assertEquals("书籍资料", transactionListCategoryLabel("REIMBURSEMENT", "书籍资料", null))
    }
    private val housing = CategoryEntity("housing", "居住生活", "居住", null, "home")
    private val rent = CategoryEntity("rent", "房租", "房租", "housing", "home")
    private val food = CategoryEntity("food", "餐饮", "餐饮", null, "restaurant")

    @Test
    fun parentDrillMatchesItsSecondLevelTransactions() {
        val categories = listOf(housing, rent, food)

        assertTrue(matchesCategoryFilter("房租", "居住生活", categories))
        assertTrue(matchesCategoryFilter("房租", "房租", categories))
        assertFalse(matchesCategoryFilter("餐饮", "居住生活", categories))
        assertTrue(matchesCategoryFilter("房租", null, categories))
    }

    @Test
    fun editorAllowsReimbursementBusinessManagementButKeepsLoanFlowsReadOnly() {
        assertTrue(isOrdinaryEditableTransaction(TransactionType.EXPENSE))
        assertTrue(isOrdinaryEditableTransaction(TransactionType.INCOME))
        assertTrue(isOrdinaryEditableTransaction(TransactionType.REFUND))
        assertFalse(isOrdinaryEditableTransaction(TransactionType.LOAN_PAYMENT))
        assertTrue(isOrdinaryEditableTransaction(TransactionType.REIMBURSEMENT))
    }

    @Test
    fun librarySummaryCountsOnlyActiveCategoriesAndAllLearnedMerchants() {
        val archived = CategoryEntity(
            id = "archived",
            name = "已归档",
            shortName = "归档",
            parentId = null,
            iconKey = "more-horiz",
            isArchived = true
        )
        val incomeParent = CategoryEntity(
            id = "income",
            name = "工资收入",
            shortName = "工资",
            parentId = null,
            iconKey = "payments",
            kind = "INCOME"
        )
        val incomeChild = CategoryEntity(
            id = "salary",
            name = "工资",
            shortName = "工资",
            parentId = "income",
            iconKey = "payments",
            kind = "INCOME"
        )

        val summary = categoryLibrarySummary(
            categories = listOf(housing, rent, food, archived, incomeParent, incomeChild),
            merchants = listOf(MerchantEntity(id = "便利店"), MerchantEntity(id = "地铁"))
        )

        assertEquals(2, summary.expensePrimary)
        assertEquals(1, summary.expenseSecondary)
        assertEquals(1, summary.incomePrimary)
        assertEquals(1, summary.incomeSecondary)
        assertEquals(2, summary.merchantCount)
        assertEquals(listOf("流水记录", "商户与分类库"), TransactionsView.entries.map { it.label })
    }

    @Test
    fun monthlySummaryIsAlwaysTwoByTwoAndSeparatesRepaymentFromExpense() {
        val grid = cashFlowSummaryGrid(CashFlowSummary(698_983L, 320_350L, 342_000L))

        assertEquals(listOf(2, 2), grid.map { it.size })
        assertEquals(listOf("收入", "支出", "已还款", "结余"), grid.flatten().map { it.label })
        assertEquals(listOf(698_983L, 320_350L, 342_000L, 36_633L), grid.flatten().map { it.cents })
        assertEquals(listOf(IncomeGreen, ExpenseRed, RepaymentPurple, BalanceBlue), grid.flatten().map { it.color })
    }

    @Test
    fun repaymentColorNeverFallsBackToExpenseAndDeficitUsesErrorRed() {
        assertEquals(RepaymentPurple, transactionCashFlowColor("LOAN_PAYMENT"))
        assertEquals(RepaymentPurple, transactionCashFlowColor("LOAN_PREPAYMENT"))
        assertFalse(transactionCashFlowColor("LOAN_PAYMENT") == ExpenseRed)

        val noRepaymentGrid = cashFlowSummaryGrid(CashFlowSummary(100_000L, 80_000L, 0L))
        assertEquals(0L, noRepaymentGrid[1][0].cents)
        assertEquals(BalanceBlue, noRepaymentGrid[1][1].color)

        val grid = cashFlowSummaryGrid(CashFlowSummary(100_000L, 80_000L, 50_000L))
        assertEquals(DeficitRed, grid[1][1].color)
        assertEquals(-30_000L, grid[1][1].cents)
    }

    @Test
    fun actualRepaymentsUseCoinIconWithoutChangingOrdinaryExpenseIcons() {
        assertEquals("paid", transactionIconKey("LOAN_PAYMENT", "account-balance"))
        assertEquals("paid", transactionIconKey("LOAN_PREPAYMENT", "account-balance"))
        assertEquals("restaurant", transactionIconKey("EXPENSE", "restaurant"))
    }

    @Test
    fun reimbursementArrivalUsesTheExistingRequestQuoteIconInsteadOfTheFallbackEllipsis() {
        assertEquals("request-quote", transactionIconKey("REIMBURSEMENT", null))
        assertEquals("request-quote", transactionIconKey("REIMBURSEMENT", "more-horiz"))
    }

    @Test
    fun reimbursementBadgesAndFiltersShareTheSameThreeStates() {
        val pending = reimbursementTx("pending", amount = 10_000L, reimbursed = 4_000L)
        val settled = reimbursementTx("settled", amount = 10_000L, reimbursed = 10_000L)
        val arrival = reimbursementTx("arrival", amount = 10_000L, reimbursed = 0L, type = "REIMBURSEMENT", marked = false)

        assertEquals(ReimbursementBadge.PENDING, reimbursementBadge(pending))
        assertEquals(ReimbursementBadge.SETTLED, reimbursementBadge(settled))
        assertEquals(ReimbursementBadge.ARRIVAL, reimbursementBadge(arrival))
        assertTrue(matchesReimbursementFilter(pending, ReimbursementBadge.PENDING))
        assertFalse(matchesReimbursementFilter(pending, ReimbursementBadge.SETTLED))
        assertTrue(matchesReimbursementFilter(arrival, ReimbursementBadge.ARRIVAL))
        assertEquals(ReimbursementYellow, transactionCashFlowColor("REIMBURSEMENT"))
        assertEquals(null, reimbursementBadge(pending.copy(isReimbursable = false)))
    }

    @Test
    fun recurringDebitBadgeOnlyMarksActualRuleLinkedExpensesAndUsesItsOwnOrange() {
        val debit = reimbursementTx("debit", 10_000L, 0L, marked = false).copy(recurringRuleId = "rent-rule")

        assertTrue(isRecurringDebit(debit))
        assertFalse(isRecurringDebit(debit.copy(recurringRuleId = null)))
        assertFalse(isRecurringDebit(debit.copy(type = TransactionType.INCOME.name)))
        assertFalse(RecurringDebitOrange == PendingOrange)
        assertFalse(RecurringDebitOrange == ExpenseRed)
    }

    private fun reimbursementTx(
        id: String,
        amount: Long,
        reimbursed: Long,
        type: String = "EXPENSE",
        marked: Boolean = true
    ) = TransactionEntity(
        id = id,
        accountId = "cash",
        amountCents = amount,
        type = type,
        category = "work",
        occurredAt = 1L,
        isReimbursable = marked,
        reimbursedCents = reimbursed
    )
}
