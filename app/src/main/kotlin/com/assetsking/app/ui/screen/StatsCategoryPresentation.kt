package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity

internal data class StatsNecessityAmounts(
    val necessaryCents: Long,
    val nonNecessaryCents: Long
)

internal fun statsNecessityAmounts(
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    netAmount: (TransactionEntity) -> Long = { it.amountCents }
): StatsNecessityAmounts {
    val (necessary, nonNecessary) = transactions.partition { effectiveNecessary(it, categories) }
    return StatsNecessityAmounts(
        necessaryCents = necessary.sumOf(netAmount),
        nonNecessaryCents = nonNecessary.sumOf(netAmount)
    )
}
