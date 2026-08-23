package com.assetsking.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.LocalDate

class LoanPlanFormPresentationTest {
    @Test
    fun commonCountsCoverShortLoansAndThirtyYearMortgagesWhileStillAllowingOtherCounts() {
        assertEquals(listOf(6, 12, 24, 36, 60, 120, 240, 360), CommonLoanInstallmentCounts)
        assertEquals("60期·5年", loanInstallmentCountLabel(60))
        assertEquals("360期·30年", loanInstallmentCountLabel(360))
        assertNull(loanInstallmentCountError(48, 3))
        assertEquals("总期数须为 1—600 期", loanInstallmentCountError(601, 0))
    }

    @Test
    fun directCustomScheduleIsGeneratedExactlyAndCanBeValidatedAfterEditing() {
        val drafts = generateCustomLoanInstallmentDrafts(
            count = 3,
            principalCents = 100_00,
            firstDueDate = LocalDate.of(2026, 9, 15)
        )
        assertEquals(listOf("33.34", "33.33", "33.33"), drafts.map { it.principal })
        val result = validateCustomLoanSchedule(drafts, expectedCount = 3, expectedPrincipalCents = 100_00)
        assertNull(result.error)
        assertEquals(100_00, result.installments.sumOf { it.principal.cents })
    }

    @Test
    fun directCustomScheduleRejectsMissingRowsAndPrincipalMismatch() {
        val drafts = generateCustomLoanInstallmentDrafts(2, 100_00, LocalDate.of(2026, 9, 15))
        assertEquals("请先生成 3 期逐期填写表", validateCustomLoanSchedule(drafts, 3, 100_00).error)
        val changed = drafts.toMutableList().also { it[0] = it[0].copy(principal = "40.00") }
        assertTrue(validateCustomLoanSchedule(changed, 2, 100_00).error.orEmpty().contains("必须等于贷款本金"))
    }


    @Test
    fun simpleTotalEditingKeepsPrincipalAndStoresTheDifferenceAsInterest() {
        val draft = generateCustomLoanInstallmentDrafts(2, 100_00, LocalDate.of(2026, 9, 15)).first()
        val changed = customLoanDraftWithTotal(draft, "58.88")!!
        assertEquals("50.00", changed.principal)
        assertEquals("8.88", changed.interest)
        assertEquals("58.88", customLoanDraftTotal(changed))
        assertNull(customLoanDraftWithTotal(draft, "49.99"))
    }

    @Test
    fun fixedRepaymentDayRecoversAfterShortMonths() {
        val first = LocalDate.of(2027, 2, 28)
        assertEquals(LocalDate.of(2027, 2, 28), loanDueDate(first, 31, 0))
        assertEquals(LocalDate.of(2027, 3, 31), loanDueDate(first, 31, 1))
        assertEquals(LocalDate.of(2027, 4, 30), loanDueDate(first, 31, 2))
    }
}
