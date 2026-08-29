package com.assetsking.database

import kotlin.test.Test
import kotlin.test.assertFailsWith

class LendingPlanDeletionPolicyTest {
    @Test
    fun openingPlanWithoutFlowsCanBeDeletedWhenHistoricalReceivableDiffers() {
        requireLendingPlanDeletable(
            originType = LendingOriginType.OPENING_BALANCE,
            hasActiveFlows = false,
            receivableBalanceStatus = "CONFIRMED",
            receivableBalanceCents = 0L,
            remainingPrincipalCents = 100L
        )
    }

    @Test
    fun actualDisbursementStillRequiresFlowsRemovedAndReceivableCleared() {
        assertFailsWith<IllegalArgumentException> {
            requireLendingPlanDeletable(
                originType = LendingOriginType.DISBURSEMENT_TRANSFER,
                hasActiveFlows = true,
                receivableBalanceStatus = "CONFIRMED",
                receivableBalanceCents = 100L,
                remainingPrincipalCents = 100L
            )
        }
    }
}
