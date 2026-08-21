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

    @Test
    fun `daily net keeps small values exact and compacts long values`() {
        assertEquals("+98", formatDailyNetChange(9_800L))
        assertEquals("−98", formatDailyNetChange(-9_800L))
        assertEquals("+0.75", formatDailyNetChange(75L))
        assertEquals("−0.75", formatDailyNetChange(-75L))
        assertEquals("+999.99", formatDailyNetChange(99_999L))
        assertEquals("−999.99", formatDailyNetChange(-99_999L))
        assertEquals("+1k", formatDailyNetChange(100_000L))
        assertEquals("−1.5k", formatDailyNetChange(-150_000L))
        assertEquals("+6.95k", formatDailyNetChange(695_095L))
        assertEquals("+1万", formatDailyNetChange(1_000_000L))
        assertEquals("−12.35万", formatDailyNetChange(-12_345_678L))
        assertEquals("", formatDailyNetChange(0L))
    }
}
