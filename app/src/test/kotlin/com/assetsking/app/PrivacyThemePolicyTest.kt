package com.assetsking.app

import com.assetsking.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyThemePolicyTest {
    @Test
    fun normalModeIsLightGreenAndPrivacyModeIsLongNest() {
        assertEquals(AppTheme.LIGHT_GREEN, themeForPrivacy(privacyEnabled = false))
        assertEquals(AppTheme.LONG_NEST, themeForPrivacy(privacyEnabled = true))
    }

    @Test
    fun privacyAutoLockWaitsTenMinutesInBackground() {
        assertFalse(privacyAutoLockTriggered(1_000L, 1_000L + 9 * 60 * 1000L))
        assertTrue(privacyAutoLockTriggered(1_000L, 1_000L + 10 * 60 * 1000L))
    }
}
