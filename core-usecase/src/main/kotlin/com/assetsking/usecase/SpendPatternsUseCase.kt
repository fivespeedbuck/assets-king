package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.ledger.ChargeSample
import com.assetsking.ledger.DetectedRecurring
import com.assetsking.ledger.NecessaryLivingSuggestion
import com.assetsking.ledger.SpendSample
import com.assetsking.ledger.detectRecurringCharges
import com.assetsking.ledger.suggestNecessaryLiving
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 把两个「用户填不准的设置」从流水里反推出来。
 *
 * 只出建议，落不落库由用户点确认 —— V5 §40 把必要生活预算定义为用户设置项，
 * App 可以帮他算，但不能替他改。
 */
class SpendPatternsUseCase(private val repository: LedgerRepository) {

    /** 必要生活预算建议：近 [lookbackMonths] 个完整月的实际必要支出水平 */
    suspend fun necessaryLiving(lookbackMonths: Int = 3): NecessaryLivingSuggestion {
        val optional = repository.optionalCategories.first()
        // 未分类的钱不知道算哪边，不能拿它当"必要"去顶预算
        val excluded = optional + "UNCATEGORIZED"
        val spends = repository.allTransactions()
            .filter { it.type == "EXPENSE" && !it.isReimbursable }   // 报销的钱会回来，不是生活成本
            .map { SpendSample(yearMonthOf(it.occurredAt), it.category, it.amountCents) }
        return suggestNecessaryLiving(spends, excluded, YearMonth.now().toString(), lookbackMonths)
    }

    /** 本月还没分类的支出笔数与金额：未分类越多，上面的建议值越不准 */
    suspend fun uncategorizedThisMonth(): Pair<Int, Long> {
        val ym = YearMonth.now().toString()
        val rows = repository.allTransactions().filter {
            it.type == "EXPENSE" && it.category == "UNCATEGORIZED" && yearMonthOf(it.occurredAt) == ym
        }
        return rows.size to rows.sumOf { it.amountCents }
    }

    /** 固定扣款识别：同商户 + 金额稳定 + 约一个月一次 + 至少 3 次，且还没建过规则 */
    suspend fun recurringCharges(lookbackMonths: Long = 6): List<DetectedRecurring> {
        val today = LocalDate.now()
        val since = today.minusMonths(lookbackMonths)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val known = repository.recurringRules.first()
            .mapNotNull { it.merchant?.trim()?.takeIf(String::isNotBlank) }
            .toSet()

        val samples = repository.allTransactions()
            .filter { it.type == "EXPENSE" && it.occurredAt >= since && it.recurringRuleId == null }
            .mapNotNull { tx ->
                val merchant = tx.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (merchant in known) return@mapNotNull null   // 已经建过规则的不重复推荐
                ChargeSample(
                    merchant = merchant,
                    accountId = tx.accountId,
                    category = tx.category,
                    amountCents = tx.amountCents,
                    occurredEpochDay = epochDayOf(tx.occurredAt)
                )
            }
        return detectRecurringCharges(samples, today.toEpochDay())
    }

    private fun yearMonthOf(epochMillis: Long): String =
        YearMonth.from(localDateOf(epochMillis)).toString()

    private fun epochDayOf(epochMillis: Long): Long = localDateOf(epochMillis).toEpochDay()

    private fun localDateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}
