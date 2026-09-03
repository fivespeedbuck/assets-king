package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsCategoryPresentationTest {
    private val categories = listOf(
        CategoryEntity("food", "餐饮", "餐饮", null, "restaurant"),
        CategoryEntity("groceries", "买菜", "买菜", "food", "shopping-cart", defaultNecessary = true),
        CategoryEntity("snacks", "零食/嘴馋加餐", "零食", "food", "cookie", defaultNecessary = false)
    )

    private fun expense(
        id: String,
        amountCents: Long,
        category: String,
        necessity: Boolean? = null
    ) = TransactionEntity(
        id = id,
        accountId = "cash",
        amountCents = amountCents,
        type = "EXPENSE",
        category = category,
        occurredAt = 1L,
        necessity = necessity
    )

    @Test
    fun missingTransactionValueInheritsNecessaryCategoryDefault() {
        assertEquals(
            StatsNecessityAmounts(3_479L, 0L),
            statsNecessityAmounts(listOf(expense("groceries", 3_479L, "买菜")), categories)
        )
    }

    @Test
    fun missingTransactionValueInheritsNonNecessaryCategoryDefault() {
        assertEquals(
            StatsNecessityAmounts(0L, 3_819L),
            statsNecessityAmounts(listOf(expense("snacks", 3_819L, "零食/嘴馋加餐")), categories)
        )
    }

    @Test
    fun explicitTransactionValueOverridesCategoryDefault() {
        assertEquals(
            StatsNecessityAmounts(1_000L, 2_000L),
            statsNecessityAmounts(
                listOf(
                    expense("necessary-snack", 1_000L, "零食/嘴馋加餐", necessity = true),
                    expense("optional-groceries", 2_000L, "买菜", necessity = false)
                ),
                categories
            )
        )
    }

    @Test
    fun netAmountsRemainSplitByEffectiveNecessity() {
        val transactions = listOf(
            expense("groceries", 3_479L, "买菜"),
            expense("snacks", 3_819L, "零食/嘴馋加餐")
        )

        assertEquals(
            StatsNecessityAmounts(3_000L, 3_500L),
            statsNecessityAmounts(transactions, categories) {
                when (it.id) {
                    "groceries" -> 3_000L
                    "snacks" -> 3_500L
                    else -> it.amountCents
                }
            }
        )
    }
}
