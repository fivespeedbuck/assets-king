package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LoanPlanEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class EditCategoryPresentationTest {
    @Test
    fun secondaryCategoryIconsAlwaysInheritFromTheirParent() {
        val food = category("food", "餐饮", "餐饮", null, "restaurant", sortOrder = 0)
        val grocery = category("grocery", "买菜", "买菜", "food", "shopping-cart")
        val travel = category("travel", "出行", "出行", null, "directions-bus", sortOrder = 1)
        assertEquals("restaurant", newCategoryIconKey("food", listOf(food, travel), "train"))
        assertEquals("train", newCategoryIconKey(null, listOf(food, travel), "train"))
        assertEquals("restaurant", categoryIconKeyForEdit(grocery, food.iconKey, "train"))
        assertEquals("train", categoryIconKeyForEdit(food, null, "train"))
    }

    @Test
    fun uncommonPaymentChannelUsesTheCustomInputPath() {
        assertEquals(false, isCustomPaymentChannel("支付宝"))
        assertEquals(false, isCustomPaymentChannel(""))
        assertEquals(true, isCustomPaymentChannel("数字人民币"))
        assertEquals(false, shouldUseCustomPaymentChannelEditor("数字人民币", setOf("数字人民币")))
        assertEquals(true, shouldUseCustomPaymentChannelEditor("抖音支付", emptySet()))
    }

    @Test
    fun loanAccountsNeverAppearAsFundingAccounts() {
        val cash = AccountEntity("cash", "招商银行", "ASSET", 1_000L)
        val card = AccountEntity("card", "广发信用卡", "CREDIT", 2_000L)
        val loan = AccountEntity("loan", "招行消费贷", "LOAN", 3_000L)

        assertEquals(listOf("cash", "card"), fundingAccounts(listOf(cash, card, loan)).map { it.id })
    }

    @Test
    fun calculatorEqualsKeepsIncompleteExpressionsStable() {
        assertEquals("15", calculatorEqualsExpression("12+3"))
        assertEquals(null, calculatorEqualsExpression("12+"))
    }

    @Test
    fun loanPlanUsesTheLinkedAccountNameInsteadOfItsInternalId() {
        val plan = LoanPlanEntity("visual-loan", "loan-account", 1_000_000, 0, "EQUAL_PAYMENT", "[]")
        val account = AccountEntity("loan-account", "招行消费贷", "LOAN", 700_000)

        assertEquals("招行消费贷", loanPlanDisplayName(plan, listOf(account)))
        assertEquals("贷款计划", loanPlanDisplayName(plan.copy(accountId = "missing"), listOf(account)))
    }

    private fun category(
        id: String,
        name: String,
        shortName: String,
        parentId: String?,
        iconKey: String,
        archived: Boolean = false,
        sortOrder: Int = 0
    ) = CategoryEntity(
        id = id,
        name = name,
        shortName = shortName,
        parentId = parentId,
        iconKey = iconKey,
        isArchived = archived,
        sortOrder = sortOrder
    )
}
