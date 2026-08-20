package com.assetsking.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyFormatTest {
    @Test
    fun `compact money omits zero decimals but preserves cents`() {
        assertEquals("¥128", formatMoneyCompact(12_800L))
        assertEquals("¥12.50", formatMoneyCompact(1_250L))
        assertEquals("¥2,000", formatMoneyCompact(200_000L))
    }

    @Test
    fun `signed money contains exactly one currency symbol`() {
        assertEquals("−¥12.50", formatSignedMoney(1_250L, positive = false))
        assertEquals("+¥12.50", formatSignedMoney(1_250L, positive = true))
        assertEquals("¥12.50", formatSignedMoney(1_250L, positive = null))
    }
}
