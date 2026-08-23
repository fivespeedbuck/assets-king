package com.assetsking.app

import com.assetsking.ui.privacy.PrivacyMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyWriteGateTest {
    @AfterTest
    fun restoreDefault() {
        PrivacyMode.setEnabled(false)
    }

    @Test
    fun privacyModeMakesUserDataWritesReadOnly() {
        PrivacyMode.setEnabled(false)
        assertTrue(privacyDataWritesAllowed())

        PrivacyMode.setEnabled(true)
        assertFalse(privacyDataWritesAllowed())
    }
}
