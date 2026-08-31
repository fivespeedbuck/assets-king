package com.assetsking.app.ui.screen

import com.assetsking.database.AccountEntity
import com.assetsking.model.AccountType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconciliationPresentationTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun discrepancyTakesPriorityOverElapsedReconciliationCycle() {
        val account = AccountEntity(
            id = "credit",
            name = "广发信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = -1_000L,
            balanceStatus = "DISCREPANCY",
            lastCheckedAt = 0L
        )

        assertEquals("存在余额差额", reconciliationNeed(account, now = 10 * day)?.reason)
    }

    @Test
    fun staleAndCurrentAccountsAreDistinguishedByTheSameCycle() {
        val stale = AccountEntity("bank", "招商银行", AccountType.ASSET.name, 0L, lastCheckedAt = day)
        val current = AccountEntity("wallet", "微信零钱", AccountType.ASSET.name, 0L, lastCheckedAt = 5 * day)

        assertEquals("已超过 7 天未对账", reconciliationNeed(stale, now = 9 * day)?.reason)
        assertNull(reconciliationNeed(current, now = 9 * day))
    }
}
