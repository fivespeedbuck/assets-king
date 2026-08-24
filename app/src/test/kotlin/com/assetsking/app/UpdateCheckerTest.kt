package com.assetsking.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun `patch release is newer than installed version`() {
        assertEquals("0.1.2", BuildConfig.VERSION_NAME)
        assertTrue(UpdateChecker.isNewer("v0.1.2", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("v0.1.2", "0.1.2"))
    }
}
