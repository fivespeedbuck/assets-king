package com.assetsking.app.ui.screen

import com.assetsking.database.BudgetEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.recurringRunAtForDate
import com.assetsking.model.TransactionType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** 未选择二级分类的周期待扣在预算详情中的展示键，不伪造具体分类。 */
internal const val UNCLASSIFIED_RECURRING_BUDGET_CATEGORY = "__recurring_unclassified__"

/** 手填预算 + 当前有效周期规则的计划支出；只做展示聚合，不回写或覆盖手填预算。 */
internal fun effectiveBudgets(
    manualBudgets: List<BudgetEntity>,
    rules: List<RecurringRuleEntity>,
    months: Set<YearMonth>,
    categories: List<CategoryEntity> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault()
): List<BudgetEntity> {
    val categoryIds = buildMap {
        categories.forEach {
            put(it.id, it.id)
            put(it.name, it.id)
        }
    }
    fun canonicalCategory(value: String) = categoryIds[value] ?: value
    val totals = linkedMapOf<Pair<String, String>, Long>()
    manualBudgets.forEach { budget ->
        val key = budget.month to canonicalCategory(budget.category)
        totals[key] = totals.getOrDefault(key, 0L) + budget.monthlyLimitCents
    }
    months.forEach { month ->
        rules.asSequence()
            .filter {
                // 周期性待扣的产品口径是必要消费；预算开关只保留为历史编辑字段，
                // 不再让待扣从必要生活汇总中消失。未分类计划单独展示。
                it.isActive && it.type == TransactionType.EXPENSE.name && it.amountCents > 0L
            }
            .forEach { rule ->
                // 月度预算展示按月均计划金额，避免季度/年度计划只在发生月出现，
                // 也避免首扣在下月时当前月显示为空。
                val contribution = recurringMonthlyPlanAmount(rule, month, zone)
                if (contribution > 0L) {
                    val category = if (rule.category.isBlank()) {
                        UNCLASSIFIED_RECURRING_BUDGET_CATEGORY
                    } else {
                        canonicalCategory(rule.category)
                    }
                    val key = month.toString() to category
                    totals[key] = totals.getOrDefault(key, 0L) + contribution
                }
            }
    }
    return totals.map { (key, cents) ->
        BudgetEntity(
            id = "effective:${key.first}:${key.second}",
            category = key.second,
            monthlyLimitCents = cents,
            month = key.first
        )
    }
}

internal fun recurringBudgetContribution(
    rule: RecurringRuleEntity,
    month: YearMonth,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    if (!rule.isActive || rule.type != TransactionType.EXPENSE.name) return 0L
    val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone)
    val createdAt = rule.createdAt.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).atZone(zone) }
    if (createdAt != null && !createdAt.isBefore(monthEnd)) return 0L

    var date = month.atDay(1)
    var count = 0L
    while (!date.isAfter(month.atEndOfMonth())) {
        val scheduledAt = recurringRunAtForDate(rule, date, zone)
        if (scheduledAt != null && (createdAt == null || !Instant.ofEpochMilli(scheduledAt).atZone(zone).isBefore(createdAt))) {
            count++
        }
        date = date.plusDays(1)
    }
    return Math.multiplyExact(rule.amountCents, count)
}

/**
 * 规划页展示的周期计划月均金额。它不是本月实际扣款：用未来 12 个自然月的
 * 计划发生额求平均，避免刚建立、下一次发生在下月的月度规则在规划页消失。
 */
internal fun recurringMonthlyPlanAmount(
    rule: RecurringRuleEntity,
    fromMonth: YearMonth,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    if (!rule.isActive || rule.type != TransactionType.EXPENSE.name || rule.amountCents <= 0L) return 0L
    val firstRunMonth = Instant.ofEpochMilli(rule.firstRunAt.takeIf { it > 0L } ?: rule.nextRunAt)
        .atZone(zone)
        .let { YearMonth.from(it) }
    val windowStart = maxOf(fromMonth, firstRunMonth)
    val total = (0L..11L).sumOf { offset ->
        recurringBudgetContribution(rule, windowStart.plusMonths(offset), zone)
    }
    return total / 12L
}
