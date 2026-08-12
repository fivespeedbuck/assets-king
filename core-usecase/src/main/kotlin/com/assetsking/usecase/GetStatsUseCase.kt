package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CategorySlice(
    val category: TransactionCategory,
    val totalCents: Long,
    val count: Int
)

data class MonthlyBar(
    val month: String,     // "2026-08"
    val incomeCents: Long,
    val expenseCents: Long
)

data class YearlyBar(
    val year: String,      // "2026"
    val incomeCents: Long,
    val expenseCents: Long
)

data class StatsData(
    val categorySlices: List<CategorySlice>,
    val monthlyBars: List<MonthlyBar>,
    val yearlyBars: List<YearlyBar>
)

class GetStatsUseCase(private val repository: LedgerRepository) {

    suspend fun invoke(): StatsData {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.time = Date(now)

        // 本月范围
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val monthEnd = cal.timeInMillis

        val monthTxs = repository.transactionsInRange(monthStart, monthEnd)
        val categorySlices = monthTxs
            .filter { it.type == TransactionType.EXPENSE.name }
            .groupBy { runCatching { TransactionCategory.valueOf(it.category) }.getOrDefault(TransactionCategory.UNCATEGORIZED) }
            .map { (cat, txs) -> CategorySlice(cat, txs.sumOf { it.amountCents }, txs.size) }
            .sortedByDescending { it.totalCents }

        // 最近12个月
        val monthlyBars = mutableListOf<MonthlyBar>()
        val fmt = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        cal.time = Date(now)
        for (i in 11 downTo 0) {
            cal.time = Date(now)
            cal.add(Calendar.MONTH, -i)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val end = cal.timeInMillis
            val monthLabel = fmt.format(Date(start))
            val txs = repository.transactionsInRange(start, end)
            monthlyBars.add(
                MonthlyBar(
                    month = monthLabel,
                    incomeCents = txs.filter { it.type in incomeTypes }.sumOf { it.amountCents },
                    expenseCents = txs.filter { it.type in expenseTypes }.sumOf { it.amountCents }
                )
            )
        }

        // 按年聚合
        val yearlyBars = monthlyBars.groupBy { it.month.substring(0, 4) }
            .map { (year, bars) ->
                YearlyBar(
                    year = year,
                    incomeCents = bars.sumOf { it.incomeCents },
                    expenseCents = bars.sumOf { it.expenseCents }
                )
            }
            .sortedBy { it.year }

        return StatsData(categorySlices, monthlyBars, yearlyBars)
    }

    companion object {
        private val incomeTypes = setOf(TransactionType.INCOME.name, TransactionType.REFUND.name)
        private val expenseTypes = setOf(TransactionType.EXPENSE.name, TransactionType.FEE.name)
    }
}
