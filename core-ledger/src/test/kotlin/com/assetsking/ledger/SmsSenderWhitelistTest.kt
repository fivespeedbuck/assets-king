package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsSenderWhitelistTest {
    @Test
    fun `sender must match configured prefix`() {
        val allowed = setOf("95555", "1069")

        assertTrue(SmsSenderWhitelist.isAllowed("95555", allowed))
        assertTrue(SmsSenderWhitelist.isAllowed("955551234", allowed))
        assertFalse(SmsSenderWhitelist.isAllowed("95556", allowed))
        assertFalse(SmsSenderWhitelist.isAllowed("", allowed))
    }

    @Test
    fun `rescan starts at last healthy time but never older than seven days`() {
        val now = 10 * SmsSenderWhitelist.MAX_RESCAN_AGE_MS

        assertEquals(now - 2 * 24 * 60 * 60 * 1000L, SmsSenderWhitelist.rescanSince(now, now - 2 * 24 * 60 * 60 * 1000L))
        assertEquals(now - SmsSenderWhitelist.MAX_RESCAN_AGE_MS, SmsSenderWhitelist.rescanSince(now, 0))
        assertEquals(now, SmsSenderWhitelist.rescanSince(now, now + 1))
    }
}
