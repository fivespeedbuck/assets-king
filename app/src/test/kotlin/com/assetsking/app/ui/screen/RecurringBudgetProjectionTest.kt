package com.assetsking.app.ui.screen

import com.assetsking.database.BudgetEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.model.TransactionType
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurringBudgetProjectionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun sameSecondaryCategoryAddsRecurringAmountsWithoutOverwritingManualBudget() {
        val month = YearMonth.of(2026, 9)
        val firstRun = month.atDay(5).atStartOfDay(zone).toInstant().toEpochMilli()
        val rules = listOf(
            rule("cat-a", 3_475L, firstRun),
            rule("cat-a", 4_275L, firstRun),
            rule("cat-a", 5_175L, firstRun)
        )

        val result = effectiveBudgets(
            manualBudgets = listOf(BudgetEntity("manual", "cat-a", 10_000L, month.toString())),
            rules = rules,
            months = setOf(month),
            zone = zone
        )

        assertEquals(22_925L, result.single().monthlyLimitCents)
    }

    @Test
    fun disabledOrExcludedRulesDoNotContributeAndWeeklyRuleCountsActualRuns() {
        val month = YearMonth.of(2026, 9)
        val firstRun = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekly = rule("cat-a", 1_000L, firstRun).copy(interval = "WEEKLY")
        val disabled = rule("cat-a", 9_999L, firstRun).copy(isActive = false)
        val excluded = rule("cat-a", 9_999L, firstRun).copy(includeInBudget = false)

        assertEquals(5_000L, recurringBudgetContribution(weekly, month, zone))
        assertEquals(0L, recurringBudgetContribution(disabled, month, zone))
        assertEquals(9_999L, recurringBudgetContribution(excluded, month, zone))
    }

    @Test
    fun newMonthlyRuleDoesNotBackfillMonthBeforeItsFirstRun() {
        val september = YearMonth.of(2026, 9)
        val firstRun = september.atDay(30).atStartOfDay(zone).toInstant().toEpochMilli()
        val rule = rule("cat-a", 3_475L, firstRun)

        assertEquals(0L, recurringBudgetContribution(rule, september.minusMonths(1), zone))
        assertEquals(3_475L, recurringBudgetContribution(rule, september, zone))
        assertEquals(3_475L, recurringBudgetContribution(rule, september.plusMonths(1), zone))
    }

    @Test
    fun monthlyAnchorDayReturnsToThirtyFirstAfterShortMonth() {
        val august = YearMonth.of(2026, 8)
        val firstRun = august.atDay(31).atStartOfDay(zone).toInstant().toEpochMilli()
        val rule = rule("cat-a", 3_475L, firstRun)

        assertEquals(3_475L, recurringBudgetContribution(rule, august.plusMonths(1), zone))
        assertEquals(3_475L, recurringBudgetContribution(rule, august.plusMonths(2), zone))
    }

    @Test
    fun monthlyPlanIsVisibleBeforeItsFirstOccurrence() {
        val august = YearMonth.of(2026, 8)
        val firstRun = august.plusMonths(1).atDay(30).atStartOfDay(zone).toInstant().toEpochMilli()
        val rule = rule("cat-a", 2_217L, firstRun)

        assertEquals(2_217L, recurringMonthlyPlanAmount(rule, august, zone))
        assertEquals(0L, recurringBudgetContribution(rule, august, zone))
    }

    @Test
    fun allActiveExpensePlansAreNecessaryIncludingUnclassifiedAndExcludedPlans() {
        val august = YearMonth.of(2026, 8)
        val firstRun = august.plusMonths(1).atDay(30).atStartOfDay(zone).toInstant().toEpochMilli()
        val unclassified = rule("", 2_000L, firstRun).copy(includeInBudget = false)
        val optionalCategory = rule("games", 3_000L, firstRun)

        val result = effectiveBudgets(
            manualBudgets = emptyList(),
            rules = listOf(unclassified, optionalCategory),
            months = setOf(august),
            zone = zone
        ).associate { it.category to it.monthlyLimitCents }

        assertEquals(2_000L, result[UNCLASSIFIED_RECURRING_BUDGET_CATEGORY])
        assertEquals(3_000L, result["games"])
    }

    private fun rule(category: String, amountCents: Long, firstRunAt: Long) = RecurringRuleEntity(
        id = "$category-$amountCents-$firstRunAt",
        accountId = "account",
        amountCents = amountCents,
        type = TransactionType.EXPENSE.name,
        category = category,
        merchant = "帕帕保险",
        note = null,
        interval = "MONTHLY",
        nextRunAt = firstRunAt,
        isActive = true,
        includeInBudget = true,
        createdAt = firstRunAt - 1,
        firstRunAt = firstRunAt
    )
}
