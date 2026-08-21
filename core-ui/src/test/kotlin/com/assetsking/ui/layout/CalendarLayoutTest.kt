package com.assetsking.ui.layout

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarLayoutTest {
    @Test
    fun `monday first calendar puts dates in the correct columns`() {
        assertEquals(0, calendarCellIndex(YearMonth.of(2026, 6), 1)) // Monday
        assertEquals(5, calendarCellIndex(YearMonth.of(2026, 8), 1)) // Saturday
        assertEquals(6, calendarCellIndex(YearMonth.of(2026, 3), 1)) // Sunday
        assertEquals(7, calendarCellIndex(YearMonth.of(2026, 6), 8)) // next Monday
    }
}
