package com.assetsking.usecase

import kotlin.test.Test
import kotlin.test.assertEquals

class UpcomingRepaymentsUseCaseTest {
    @Test
    fun overdueItemsComeBeforeUpcomingItemsAndKeepDueDateOrder() {
        val sorted = sortUpcomingRepayments(
            listOf(
                UpcomingRepayment("future", 100, 12, overdue = false),
                UpcomingRepayment("overdue-late", 100, 9, overdue = true),
                UpcomingRepayment("overdue-early", 100, 7, overdue = true),
                UpcomingRepayment("today", 100, 10, overdue = false)
            )
        )

        assertEquals(
            listOf("overdue-early", "overdue-late", "today", "future"),
            sorted.map { it.planId }
        )
    }
}
