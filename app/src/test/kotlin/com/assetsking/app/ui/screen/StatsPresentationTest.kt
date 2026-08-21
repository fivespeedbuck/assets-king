package com.assetsking.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class StatsPresentationTest {
    @Test
    fun necessityLabelOnlyCallsOutNonNecessarySpending() {
        assertEquals("0%", categoryNecessityLabel(150_000L, 0L))
        assertEquals("25%", categoryNecessityLabel(100_000L, 25_000L))
    }
}
