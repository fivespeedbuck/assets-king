package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.RecordStatus
import com.assetsking.model.TransactionType
import kotlin.math.floor

/** 流水连续列表与月历共用的单日资金移动和消费结构口径。 */
internal data class TransactionsDayPresentation(
    val inflowCents: Long,
    val outflowCents: Long,
    val necessaryCents: Long,
    val optionalCents: Long,
    val unclassifiedCents: Long
) {
    val netCents: Long get() = inflowCents - outflowCents
    val spendingCents: Long get() = necessaryCents + optionalCents + unclassifiedCents

    val necessaryPercent: Int?
        get() = spendingPercentages()?.get(0)

    val optionalPercent: Int?
        get() = spendingPercentages()?.get(1)

    val unclassifiedPercent: Int?
        get() = spendingPercentages()?.get(2)

    /** 最大余数法分配整数百分比，三段始终精确合计 100。 */
    private fun spendingPercentages(): IntArray? {
        val total = spendingCents.takeIf { it > 0L } ?: return null
        val exact = doubleArrayOf(necessaryCents.toDouble(), optionalCents.toDouble(), unclassifiedCents.toDouble())
            .map { it * 100.0 / total.toDouble() }
        val allocated = IntArray(exact.size) { floor(exact[it]).toInt() }
        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it] - allocated[it] }.thenBy { it }
        )
        repeat(100 - allocated.sum()) { offset ->
            allocated[order[offset % order.size]] += 1
        }
        return allocated
    }
}

internal fun transactionsDayPresentation(
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    refundTransactions: List<TransactionEntity> = transactions
): TransactionsDayPresentation {
    val confirmed = transactions.filter { it.status == RecordStatus.CONFIRMED.name }
    val refundOffsets = refundTransactions.asSequence()
        .filter {
            it.status == RecordStatus.CONFIRMED.name &&
                it.type == TransactionType.REFUND.name &&
                it.refundOfId != null
        }
        .groupBy { it.refundOfId!! }
        .mapValues { (_, refunds) -> refunds.sumOf { it.amountCents } }

    var necessaryCents = 0L
    var optionalCents = 0L
    var unclassifiedCents = 0L
    confirmed.asSequence()
        .filter { it.type == TransactionType.EXPENSE.name || it.type == TransactionType.FEE.name }
        .forEach { expense ->
            val netCents = (
                expense.amountCents -
                    expense.reimbursedCents -
                    (refundOffsets[expense.id] ?: 0L)
                ).coerceAtLeast(0L)
            when (effectiveNecessity(expense, categories)) {
                true -> necessaryCents += netCents
                false -> optionalCents += netCents
                null -> unclassifiedCents += netCents
            }
        }

    return TransactionsDayPresentation(
        inflowCents = confirmed.asSequence()
            .filter {
                it.type == TransactionType.INCOME.name ||
                    it.type == TransactionType.REFUND.name ||
                    it.type == TransactionType.REIMBURSEMENT.name ||
                    it.type == TransactionType.LOAN_DISBURSEMENT.name
            }
            .sumOf { it.amountCents },
        outflowCents = confirmed.asSequence()
            .filter {
                it.type == TransactionType.EXPENSE.name ||
                    it.type == TransactionType.FEE.name ||
                    it.type == TransactionType.LOAN_PAYMENT.name ||
                    it.type == TransactionType.LOAN_PREPAYMENT.name
            }
            .sumOf { it.amountCents },
        necessaryCents = necessaryCents,
        optionalCents = optionalCents,
        unclassifiedCents = unclassifiedCents
    )
}

private fun effectiveNecessity(
    transaction: TransactionEntity,
    categories: List<CategoryEntity>
): Boolean? {
    transaction.necessity?.let { return it }
    val activeCategories = categories.filterNot { it.isArchived }
    activeCategories.firstOrNull { it.id == transaction.category }?.let { return it.defaultNecessary }

    // 旧流水只保存分类名称。同名分类默认值不一致时没有足够证据判断，必须保留为“未判定”，
    // 不能依赖数据库返回顺序静默抢第一个。
    val matchingDefaults = activeCategories.asSequence()
        .filter { it.name == transaction.category }
        .map { it.defaultNecessary }
        .distinct()
        .toList()
    return matchingDefaults.singleOrNull()
}

internal fun transactionSpendingNecessity(
    transaction: TransactionEntity,
    category: CategoryEntity?
): Boolean? = if (
    transaction.type == TransactionType.EXPENSE.name ||
    transaction.type == TransactionType.FEE.name
) {
    transaction.necessity ?: category?.takeUnless { it.isArchived }?.defaultNecessary
} else {
    null
}
