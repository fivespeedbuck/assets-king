package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionsFilterTest {
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
    fun ordinaryEditingExcludesBusinessGeneratedTransactions() {
        assertTrue(isOrdinaryEditableTransaction(TransactionType.EXPENSE))
        assertTrue(isOrdinaryEditableTransaction(TransactionType.INCOME))
        assertTrue(isOrdinaryEditableTransaction(TransactionType.REFUND))
        assertFalse(isOrdinaryEditableTransaction(TransactionType.LOAN_PAYMENT))
        assertFalse(isOrdinaryEditableTransaction(TransactionType.REIMBURSEMENT))
    }
}
