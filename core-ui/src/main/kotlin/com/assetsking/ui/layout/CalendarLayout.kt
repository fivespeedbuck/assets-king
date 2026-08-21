package com.assetsking.ui.layout

import java.time.YearMonth

/** Monday-first calendar cell index for a day in [month]. */
fun calendarCellIndex(month: YearMonth, day: Int): Int =
    month.atDay(1).dayOfWeek.value - 1 + day - 1
