package com.assetsking.app.notification

import kotlin.test.Test
import kotlin.test.assertEquals

class PendingNotifierTest {
    @Test
    fun `unprocessed evidence is included in the actionable notification count`() {
        assertEquals(5, actionableEvidenceCount(pendingCount = 3, unprocessedCount = 2))
    }

    @Test
    fun `negative transient counts cannot reduce actionable evidence`() {
        assertEquals(2, actionableEvidenceCount(pendingCount = 2, unprocessedCount = -1))
    }
}
