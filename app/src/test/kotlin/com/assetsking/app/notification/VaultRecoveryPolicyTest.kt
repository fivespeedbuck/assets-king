package com.assetsking.app.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultRecoveryPolicyTest {
    @Test
    fun staleOrDisconnectedRecoveryCannotOverwriteCurrentStatus() {
        assertNull(resolveVaultRecoveryStatus(1L, 2L, connected = true, completed = true))
        assertNull(resolveVaultRecoveryStatus(2L, 2L, connected = false, completed = true))
    }

    @Test
    fun currentRecoveryReportsSuccessAndFailureExactly() {
        assertEquals(
            VaultRuntimeStatus.IDLE,
            resolveVaultRecoveryStatus(2L, 2L, connected = true, completed = true)
        )
        assertEquals(
            VaultRuntimeStatus.ERROR,
            resolveVaultRecoveryStatus(2L, 2L, connected = true, completed = false)
        )
    }

    @Test
    fun staleIngestionFailureCannotOverwriteNewerHealthyState() {
        assertNull(resolveIngestionFailureStatus(7L, 8L))
        assertEquals(VaultRuntimeStatus.ERROR, resolveIngestionFailureStatus(8L, 8L))
    }
}
