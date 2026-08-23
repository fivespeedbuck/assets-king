package com.assetsking.app

import com.assetsking.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivacyThemePolicyTest {
    @Test
    fun normalModeIsLightGreenAndPrivacyModeIsLongNest() {
        assertEquals(AppTheme.LIGHT_GREEN, themeForPrivacy(privacyEnabled = false))
        assertEquals(AppTheme.LONG_NEST, themeForPrivacy(privacyEnabled = true))
    }
}
