package com.assetsking.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionEditorDraftStoreTest {
    @Test
    fun jsonRoundTripPreservesClearedTextAndNullableSelections() {
        val original = TransactionEditorDraft(
            submissionId = "submission-1",
            kind = "EXPENSE",
            directionChosen = true,
            amountExpr = "",
            accountId = "",
            channel = "",
            orderPlatform = "",
            merchantText = "",
            categoryId = null,
            necessity = null,
            refundOfId = null,
            note = "",
            loanPlanId = null,
            principalExpr = "",
            interestExpr = "",
            feeExpr = "",
            lendingPlanId = null,
            transferFeeExpr = "",
            expenseIds = emptyList(),
            reimbursementSelectionTouched = false
        )

        val restored = TransactionEditorDraft.fromJson(original.toJson())

        assertEquals("submission-1", restored.submissionId)
        assertEquals("", restored.amountExpr)
        assertEquals("", restored.accountId)
        assertEquals("", restored.channel)
        assertEquals("", restored.orderPlatform)
        assertEquals("", restored.merchantText)
        assertEquals("", restored.note)
        assertEquals("", restored.principalExpr)
        assertEquals("", restored.interestExpr)
        assertEquals("", restored.feeExpr)
        assertEquals("", restored.transferFeeExpr)
        assertNull(restored.categoryId)
        assertNull(restored.necessity)
        assertNull(restored.refundOfId)
        assertNull(restored.loanPlanId)
        assertNull(restored.lendingPlanId)
        assertEquals(emptyList(), restored.expenseIds)
    }

    @Test
    fun businessObjectKeysDoNotShareDrafts() {
        assertEquals("pending:n-1", transactionEditorDraftKey("n-1", null, null))
        assertEquals("transaction:t-1", transactionEditorDraftKey(null, "t-1", null))
        assertEquals("loan-payment:l-1", transactionEditorDraftKey(null, null, "l-1"))
        assertEquals("manual", transactionEditorDraftKey(null, null, null))
    }
}
