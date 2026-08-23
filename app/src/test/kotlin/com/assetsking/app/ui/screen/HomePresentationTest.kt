package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.app.notification.VaultRuntimeStatus
import com.assetsking.model.AccountType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomePresentationTest {
    @Test
    fun zeroDebtCreditAccountsStayOutOfTheSharedDebtAccountList() {
        val accounts = listOf(
            AccountEntity("cash", "现金", AccountType.ASSET.name, 100_000L),
            AccountEntity("zero-card", "已还清广发", AccountType.CREDIT.name, 0L),
            AccountEntity("debt-card", "花呗", AccountType.CREDIT.name, 20_000L),
            AccountEntity("loan", "消费贷", AccountType.LOAN.name, 0L)
        )

        assertEquals(listOf("花呗", "消费贷"), visibleDebtAccounts(accounts).map { it.name })
    }
    @Test
    fun repaymentCarouselAlwaysTargetsACompletePage() {
        assertEquals(1, nextHomeRepaymentPage(currentPage = 0, pageCount = 2))
        assertEquals(0, nextHomeRepaymentPage(currentPage = 1, pageCount = 2))
        assertEquals(0, nextHomeRepaymentPage(currentPage = 0, pageCount = 1))
    }

    @Test
    fun assetOverviewRepaymentPagerShowsPendingAndPaidCounts() {
        assertEquals(
            listOf(
                HomeRepaymentPage(label = "本月待还 3笔", amountCents = 342_000L),
                HomeRepaymentPage(label = "本月已还 2笔", amountCents = 120_000L)
            ),
            homeRepaymentPages(
                totalDueCount = 3,
                totalDueCents = 342_000L,
                paidCount = 2,
                paidCents = 120_000L
            )
        )
    }

    @Test
    fun assetOverviewRepaymentPagerReportsPaidOffWithoutHidingMonthlyPaid() {
        assertEquals(
            listOf(
                HomeRepaymentPage(label = "全部还清", amountCents = 0L),
                HomeRepaymentPage(label = "本月已还 2笔", amountCents = 342_000L)
            ),
            homeRepaymentPages(
                totalDueCount = 0,
                totalDueCents = 0L,
                paidCount = 2,
                paidCents = 342_000L
            )
        )
    }

    @Test
    fun assetOverviewNeverCallsUnscheduledDebtPaidOff() {
        assertEquals(
            HomeRepaymentPage(label = "待完善还款信息", amountCents = 700_000L),
            homeRepaymentPages(
                totalDueCount = 0,
                totalDueCents = 0L,
                paidCount = 0,
                paidCents = 0L,
                totalDebtCents = 700_000L
            ).first()
        )
    }

    @Test
    fun moduleLibraryHasOneCompletePreviewForEverySupportedModule() {
        assertEquals(
            setOf("budget", "reimbursement", "recurring", "accounts"),
            homeModulePreviewSpecs.map { it.key }.toSet()
        )
        assertEquals(listOf("reimbursement", "recurring", "budget", "accounts"), homeModulePreviewSpecs.map { it.key })
        assertEquals(4, homeModulePreviewSpecs.size)
        assertTrue(homeModulePreviewSpecs.all { listOf(it.title, it.hint, it.primary, it.secondary).all(String::isNotBlank) })
    }

    @Test
    fun homePendingDeductionExcludesRepaymentAndRecurringIncome() {
        fun rule(id: String, type: String, at: Long) = RecurringRuleEntity(
            id = id,
            accountId = "cash",
            amountCents = 1_000L,
            type = type,
            category = "订阅",
            merchant = id,
            note = null,
            interval = "MONTHLY",
            nextRunAt = at
        )
        val rules = listOf(
            rule("subscription", "EXPENSE", 200L),
            rule("already-paid", "EXPENSE", 300L),
            rule("salary", "INCOME", 400L),
            rule("loan", "LOAN_PAYMENT", 500L)
        )
        val paid = TransactionEntity(
            id = "paid",
            accountId = "cash",
            amountCents = 1_000L,
            type = "EXPENSE",
            category = "订阅",
            occurredAt = 300L,
            recurringRuleId = "already-paid"
        )

        assertEquals(
            listOf("subscription"),
            pendingDeductionRules(rules, listOf(paid), 100L, 600L).map { it.id }
        )
    }

    @Test
    fun recurringCenterAndHomeSharePendingAndActuallyDeductedTotals() {
        fun rule(id: String, amount: Long, at: Long) = RecurringRuleEntity(
            id = id,
            accountId = "cash",
            amountCents = amount,
            type = "EXPENSE",
            category = "订阅",
            merchant = id,
            note = null,
            interval = "MONTHLY",
            nextRunAt = at
        )
        fun debit(id: String, amount: Long, at: Long, ruleId: String) = TransactionEntity(
            id = id,
            accountId = "cash",
            amountCents = amount,
            type = "EXPENSE",
            category = "订阅",
            occurredAt = at,
            recurringRuleId = ruleId
        )
        val pending = rule("pending", 2_000L, 200L)
        val paid = rule("paid", 3_000L, 300L)
        val transactions = listOf(
            debit("paid-tx", 3_100L, 310L, paid.id),
            debit("deleted-rule-audit", 900L, 320L, "deleted-rule")
        )

        val summary = recurringDebitMonthSummary(
            rules = listOf(pending, paid),
            transactions = transactions,
            start = 100L,
            end = 400L
        )

        assertEquals(listOf("pending"), summary.pendingRules.map { it.id })
        assertEquals(2_000L, summary.pendingCents)
        assertEquals(listOf("paid-tx", "deleted-rule-audit"), summary.claimedTransactions.map { it.id })
        assertEquals(4_000L, summary.deductedCents)
    }

    @Test
    fun smsFallbackMissingDoesNotTurnAHealthyVaultIntoAnOutage() {
        assertEquals(
            HomeVaultPresentation(
                title = "金库正常",
                badge = "待完善",
                severity = HomeVaultSeverity.WARNING,
                gapHint = "短信补收未开启，不影响当前入库"
            ),
            homeVaultPresentation(ListenerStatus.OK, VaultRuntimeStatus.IDLE, smsGranted = false)
        )
    }

    @Test
    fun healthyVaultDoesNotRepeatNormalInASeparateBadge() {
        assertEquals(
            HomeVaultPresentation(
                title = "金库正常",
                badge = "",
                severity = HomeVaultSeverity.NORMAL
            ),
            homeVaultPresentation(ListenerStatus.OK, VaultRuntimeStatus.IDLE, smsGranted = true)
        )
    }

    @Test
    fun disconnectedVaultReportsARealIntakeGap() {
        assertEquals(
            HomeVaultPresentation(
                title = "入库暂时中断",
                badge = "中断",
                severity = HomeVaultSeverity.ERROR,
                gapHint = "有一段时间未能入库，请检查"
            ),
            homeVaultPresentation(ListenerStatus.DISCONNECTED, VaultRuntimeStatus.IDLE, smsGranted = false)
        )
    }

    @Test
    fun `recovery failure is never presented as healthy`() {
        val presentation = homeVaultPresentation(
            ListenerStatus.OK,
            VaultRuntimeStatus.ERROR,
            smsGranted = true
        )

        assertEquals(HomeVaultSeverity.ERROR, presentation.severity)
        assertEquals("补收入库失败", presentation.title)
    }

    @Test
    fun `active recovery uses fixed warning state`() {
        val presentation = homeVaultPresentation(
            ListenerStatus.OK,
            VaultRuntimeStatus.RECOVERING,
            smsGranted = true
        )

        assertEquals(HomeVaultSeverity.RECOVERING, presentation.severity)
        assertEquals("补收中", presentation.badge)
    }

    @Test
    fun sevenDaySpendingSplitsNecessaryAndOptionalAfterRefundsAndReimbursement() {
        val now = 1_000_000_000L
        val start = now - 7L * 24 * 60 * 60 * 1000
        val categories = listOf(
            CategoryEntity("necessary", "必要类", "必要", null, "home", defaultNecessary = true),
            CategoryEntity("optional", "非必要类", "非必要", null, "game", defaultNecessary = false)
        )
        fun tx(
            id: String,
            amount: Long,
            type: String = "EXPENSE",
            category: String = "necessary",
            occurredAt: Long = now,
            necessity: Boolean? = null,
            refundOfId: String? = null,
            reimbursed: Long = 0L
        ) = TransactionEntity(
            id = id,
            accountId = "cash",
            amountCents = amount,
            type = type,
            category = category,
            occurredAt = occurredAt,
            necessity = necessity,
            refundOfId = refundOfId,
            reimbursedCents = reimbursed
        )
        val transactions = listOf(
            tx("necessary-expense", 10_000L),
            tx("necessary-refund", 2_000L, type = "REFUND", refundOfId = "necessary-expense"),
            tx("explicit-optional", 5_000L, necessity = false, reimbursed = 1_000L),
            tx("default-optional", 3_000L, category = "optional"),
            tx("fee", 500L, type = "FEE", category = ""),
            tx("inclusive-boundary", 100L, occurredAt = start),
            tx("excluded-before-boundary", 9_999L, occurredAt = start - 1L)
        )

        assertEquals(
            HomeSpendingBreakdown(totalCents = 15_600L, necessaryCents = 8_100L, optionalCents = 7_500L),
            monthSpendingBreakdown(transactions, categories, start, now)
        )
    }

    @Test
    fun necessaryBudgetDoesNotMixInFreeSpendingCategories() {
        val categories = listOf(
            CategoryEntity("rent", "房租", "房租", null, "home", defaultNecessary = true),
            CategoryEntity("games", "游戏", "游戏", null, "game", defaultNecessary = false)
        )
        val budgets = listOf(
            BudgetEntity("rent-budget", "房租", 150_000L, "2026-08"),
            BudgetEntity("game-budget", "games", 50_000L, "2026-08"),
            BudgetEntity("old-budget", "房租", 999_000L, "2026-07")
        )

        assertEquals(150_000L, necessaryBudgetCents(budgets, categories, "2026-08"))
    }
}
