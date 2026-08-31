package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import com.assetsking.database.LendingPlanEntity
import com.assetsking.database.LendingPlanStatus
import com.assetsking.database.LendingOriginType
import com.assetsking.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionEditorSuggestionsTest {
    @Test
    fun oneCharacterQueryPrioritizesPrefixThenContainsAndDeduplicates() {
        assertEquals(
            listOf("美团外卖", "美团买菜", "周末美团券"),
            historyTextSuggestions(
                query = "美",
                candidates = listOf("美团外卖", "美团买菜", "美团外卖", "周末美团券", "盒马")
            )
        )
    }

    @Test
    fun exactCurrentValueAndBlankCandidatesAreNotSuggested() {
        assertEquals(
            listOf("出差打车"),
            historyTextSuggestions("出差", listOf("", "出差", "出差打车"))
        )
    }

    @Test
    fun loanPaymentSplitMustExactlyEqualTotal() {
        assertEquals(0L, loanPaymentSplitDifferenceCents(10_000L, 8_000L, 1_500L, 500L))
        assertEquals(200L, loanPaymentSplitDifferenceCents(10_000L, 7_800L, 1_500L, 500L))
        assertEquals(-300L, loanPaymentSplitDifferenceCents(10_000L, 8_300L, 1_500L, 500L))
    }

    @Test
    fun invalidLoanPaymentSplitCannotPassValidation() {
        assertNull(loanPaymentSplitDifferenceCents(10_000L, null, 0L, 0L))
        assertNull(loanPaymentSplitDifferenceCents(10_000L, -1L, 0L, 0L))
        assertNull(loanPaymentSplitDifferenceCents(10_000L, Long.MAX_VALUE, Long.MAX_VALUE, 0L))
    }

    @Test
    fun lendingDisbursementValidationRequiresExactPendingPlanAmount() {
        val plan = lendingPlan(status = LendingPlanStatus.PENDING_DISBURSEMENT)

        assertEquals(
            emptyList(),
            lendingValidationErrors(
                amountCents = 30_000L,
                selectedPlan = plan,
                isAssetAccount = true,
                sub = LendingSub.DISBURSEMENT,
                principalCents = null,
                splitDifferenceCents = null
            )
        )
        assertTrue(
            lendingValidationErrors(
                amountCents = 29_999L,
                selectedPlan = plan,
                isAssetAccount = true,
                sub = LendingSub.DISBURSEMENT,
                principalCents = null,
                splitDifferenceCents = null
            ).contains("借出金额须等于计划本金")
        )
    }

    @Test
    fun lendingRepaymentValidationRequiresExactSplitAndRemainingPrincipal() {
        val plan = lendingPlan(status = LendingPlanStatus.ACTIVE, remainingPrincipalCents = 10_000L)

        assertEquals(
            emptyList(),
            lendingValidationErrors(
                amountCents = 11_000L,
                selectedPlan = plan,
                isAssetAccount = true,
                sub = LendingSub.REPAYMENT,
                principalCents = 10_000L,
                splitDifferenceCents = 0L
            )
        )
        val errors = lendingValidationErrors(
            amountCents = 11_000L,
            selectedPlan = plan,
            isAssetAccount = true,
            sub = LendingSub.REPAYMENT,
            principalCents = 10_001L,
            splitDifferenceCents = 1L
        )
        assertTrue(errors.contains("本金与利息合计"))
        assertTrue(errors.contains("收回本金不能超过剩余应收"))
    }

    private fun lendingPlan(
        status: String,
        remainingPrincipalCents: Long = 30_000L
    ) = LendingPlanEntity(
        id = "lending-plan",
        receivableAccountId = "receivable",
        label = "测试出借",
        borrowerName = "测试借款人",
        principalCents = 30_000L,
        remainingPrincipalCents = remainingPrincipalCents,
        startDateEpochDay = 20_000L,
        status = status,
        originType = if (status == LendingPlanStatus.PENDING_DISBURSEMENT) {
            LendingOriginType.PENDING_DISBURSEMENT
        } else {
            LendingOriginType.DISBURSEMENT_TRANSFER
        },
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun refundSourceCandidatesPreferExactUnrefundedExpenseAndStayOptional() {
        fun transaction(
            id: String,
            amount: Long,
            occurredAt: Long,
            type: TransactionType = TransactionType.EXPENSE,
            refundOfId: String? = null,
            accountId: String = "card",
            channel: String? = null
        ) = TransactionEntity(
            id = id,
            accountId = accountId,
            amountCents = amount,
            type = type.name,
            category = "数码产品",
            occurredAt = occurredAt,
            merchant = id,
            refundOfId = refundOfId,
            channel = channel
        )

        val candidates = refundSourceCandidates(
            transactions = listOf(
                transaction("older-exact", 2_200L, 10L),
                transaction("newer-large", 5_000L, 20L),
                transaction("other-account", 2_200L, 30L, accountId = "cash"),
                transaction("partial-refund", 2_000L, 40L, TransactionType.REFUND, refundOfId = "newer-large")
            ),
            accountId = "card",
            refundAmountCents = 2_200L,
            refundOccurredAt = 50L,
            merchantName = "older-exact",
            refundChannel = "微信"
        )

        assertEquals(listOf("older-exact"), candidates.map { it.transaction.id })
        assertEquals(listOf(2_200L), candidates.map { it.remainingCents })
    }

    @Test
    fun editingRefundDoesNotCountItselfAgainstItsSource() {
        val expense = TransactionEntity(
            id = "expense",
            accountId = "card",
            amountCents = 3_000L,
            type = TransactionType.EXPENSE.name,
            category = "餐饮",
            occurredAt = 10L,
            merchant = "原消费"
        )
        val refund = TransactionEntity(
            id = "refund",
            accountId = "card",
            amountCents = 3_000L,
            type = TransactionType.REFUND.name,
            category = "餐饮",
            occurredAt = 20L,
            refundOfId = expense.id
        )

        assertEquals(
            listOf("expense"),
            refundSourceCandidates(
                transactions = listOf(expense, refund),
                accountId = "card",
                refundAmountCents = 3_000L,
                refundOccurredAt = 20L,
                editingRefundId = refund.id,
                merchantName = "原消费",
                refundChannel = "微信"
            )
                .map { it.transaction.id }
        )
    }

    @Test
    fun refundCandidatesOnlyShowExactMerchantAfterMerchantIsEntered() {
        fun transaction(id: String, merchant: String, channel: String) = TransactionEntity(
            id = id,
            accountId = "card",
            amountCents = 3_000L,
            type = TransactionType.EXPENSE.name,
            category = "餐饮",
            occurredAt = 10L,
            merchant = merchant,
            channel = channel
        )

        val transactions = listOf(
            transaction("same", "星巴克", "微信"),
            transaction("other-merchant", "瑞幸咖啡", "微信"),
            transaction("other-channel", "星巴克", "支付宝")
        )

        assertTrue(refundSourceCandidates(transactions, "card", 1_000L, 20L, merchantName = "").isEmpty())
        assertEquals(
            listOf("same"),
            refundSourceCandidates(
                transactions,
                "card",
                1_000L,
                20L,
                merchantName = "星巴克",
                refundChannel = "微信"
            )
                .map { it.transaction.id }
        )
    }

    @Test
    fun refundCandidateAllowsPartialRefundForSameMerchantChannelAndAccount() {
        val expense = TransactionEntity(
            id = "expense",
            accountId = "card",
            amountCents = 5_000L,
            type = TransactionType.EXPENSE.name,
            category = "餐饮",
            occurredAt = 10L,
            merchant = "外卖平台",
            channel = "微信"
        )

        assertEquals(
            listOf(5_000L),
            refundSourceCandidates(
                transactions = listOf(expense),
                accountId = "card",
                refundAmountCents = 1_200L,
                refundOccurredAt = 20L,
                merchantName = "外卖平台",
                refundChannel = "微信"
            ).map { it.remainingCents }
        )
    }

    @Test
    fun refundCandidateTreatsWechatAndWechatPayAsTheSameChannel() {
        val expense = TransactionEntity(
            id = "meituan-expense",
            accountId = "cmb",
            amountCents = 3_621L,
            type = TransactionType.EXPENSE.name,
            category = "餐饮",
            occurredAt = 10L,
            merchant = "美团-美团宁波象鲜科技有限公司",
            channel = "微信支付"
        )

        assertEquals(
            listOf("meituan-expense"),
            refundSourceCandidates(
                transactions = listOf(expense),
                accountId = "cmb",
                refundAmountCents = 17L,
                refundOccurredAt = 20L,
                merchantName = "美团-美团宁波象鲜科技有限公司",
                refundChannel = "微信"
            ).map { it.transaction.id }
        )
    }

    @Test
    fun refundCandidateAllowsExplicitLinkToLegacyExpenseWithMissingChannel() {
        val expense = TransactionEntity(
            id = "legacy-meituan-expense",
            accountId = "cmb",
            amountCents = 3_621L,
            type = TransactionType.EXPENSE.name,
            category = "餐饮",
            occurredAt = 10L,
            merchant = "美团-美团宁波象鲜科技有限公司",
            channel = null
        )

        assertEquals(
            listOf("legacy-meituan-expense"),
            refundSourceCandidates(
                transactions = listOf(expense),
                accountId = "cmb",
                refundAmountCents = 17L,
                refundOccurredAt = 20L,
                merchantName = "美团-美团宁波象鲜科技有限公司",
                refundChannel = "微信"
            ).map { it.transaction.id }
        )
    }

    @Test
    fun refundCandidateRequiresSameOrderPlatformWhenSourceHasOne() {
        fun expense(id: String, platform: String?) = TransactionEntity(
            id = id,
            accountId = "cmb",
            amountCents = 3_621L,
            type = TransactionType.EXPENSE.name,
            category = "餐饮",
            occurredAt = 10L,
            merchant = "象鲜科技有限公司",
            channel = "微信",
            orderPlatform = platform
        )

        val transactions = listOf(expense("meituan", "美团"), expense("taobao", "淘宝"))
        assertEquals(
            listOf("meituan"),
            refundSourceCandidates(
                transactions = transactions,
                accountId = "cmb",
                refundAmountCents = 17L,
                refundOccurredAt = 20L,
                merchantName = "象鲜科技有限公司",
                refundChannel = "微信",
                refundOrderPlatform = "美团"
            ).map { it.transaction.id }
        )
    }
}
