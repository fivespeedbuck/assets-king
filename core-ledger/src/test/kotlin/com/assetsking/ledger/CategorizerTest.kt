package com.assetsking.ledger

import com.assetsking.model.TransactionCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class CategorizerTest {
    @Test
    fun `merchant keywords are classified explainably`() {
        val categorizer = RuleBasedCategorizer()
        assertEquals(TransactionCategory.DINING, categorizer.categorize("美团外卖-午餐"))
        assertEquals(TransactionCategory.TRANSPORT, categorizer.categorize("滴滴出行"))
        assertEquals(TransactionCategory.UNCATEGORIZED, categorizer.categorize("某个看不懂的商户"))
    }

    @Test
    fun `custom rule wins over default rule`() {
        val categorizer = RuleBasedCategorizer(
            listOf(ClassificationRule("my-rule", setOf("美团"), TransactionCategory.OTHER, priority = 100))
        )
        assertEquals(TransactionCategory.OTHER, categorizer.categorize("美团外卖"))
    }
}
