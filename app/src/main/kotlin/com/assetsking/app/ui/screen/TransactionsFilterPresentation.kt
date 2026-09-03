package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity

internal const val TRANSFER_FILTER_TYPE = "TRANSFER"

internal data class TransactionFilterOption(val value: String, val label: String)

internal fun transactionCategoryFilterOptions(
    categories: List<CategoryEntity>
): List<TransactionFilterOption> {
    val categoryById = categories.associateBy { it.id }
    return categories
        .filter { !it.isArchived && it.kind == "EXPENSE" }
        .sortedWith(compareBy<CategoryEntity> { it.parentId != null }.thenBy { it.sortOrder }.thenBy { it.name })
        .map { category ->
            val parentName = category.parentId?.let(categoryById::get)?.name
            TransactionFilterOption(
                value = category.id,
                label = if (parentName == null) category.name else "$parentName / ${category.name}"
            )
        }
        .distinctBy { it.value }
}

internal fun matchesCategoryFilters(
    transactionCategory: String,
    filterCategories: Set<String>,
    categories: List<CategoryEntity>
): Boolean = filterCategories.isEmpty() || filterCategories.any { filterCategory ->
    matchesCategoryFilter(transactionCategory, filterCategory, categories)
}

internal fun matchesNecessityFilters(
    transaction: TransactionEntity,
    filters: Set<Boolean>,
    category: CategoryEntity?
): Boolean = filters.isEmpty() || transactionSpendingNecessity(transaction, category) in filters

internal fun matchesReimbursementFilters(
    transaction: TransactionEntity,
    filters: Set<ReimbursementBadge>
): Boolean = filters.isEmpty() || reimbursementBadge(transaction) in filters

internal fun matchesNamedFilters(value: String?, filters: Set<String>): Boolean {
    if (filters.isEmpty()) return true
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return filters.any { it.trim().equals(normalized, ignoreCase = true) }
}

internal fun matchesAccountFilters(
    filters: Set<String>,
    vararg accountIds: String
): Boolean = filters.isEmpty() || accountIds.any(filters::contains)

internal fun transferMatchesTransactionOnlyFilters(
    selectedTypes: Set<String>,
    selectedCategories: Set<String>,
    selectedNecessities: Set<Boolean>,
    selectedReimbursements: Set<ReimbursementBadge>,
    recurringDebitOnly: Boolean,
    selectedChannels: Set<String>,
    selectedMerchants: Set<String>
): Boolean =
    (selectedTypes.isEmpty() || TRANSFER_FILTER_TYPE in selectedTypes) &&
        selectedCategories.isEmpty() &&
        selectedNecessities.isEmpty() &&
        selectedReimbursements.isEmpty() &&
        !recurringDebitOnly &&
        selectedChannels.isEmpty() &&
        selectedMerchants.isEmpty()
