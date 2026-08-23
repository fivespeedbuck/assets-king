package com.assetsking.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DebtSemanticColorsTest {
    @Test
    fun debtCompositionNeverReusesBalanceOrRepaymentColors() {
        val colors = listOf("CARD", "CARD_INSTALLMENT", "LOAN", "ACCRUED").map { type ->
            val color = debtCompositionSemanticColor(type)
            assertNotEquals(BalanceBlue, color)
            assertNotEquals(RepaymentPurple, color)
            color
        }
        assertEquals(4, colors.distinct().size)
        assertEquals(LoanPrincipalDebtColor, debtCompositionSemanticColor("LOAN"))
        assertEquals(AccruedChargeDebtColor, debtCompositionSemanticColor("ACCRUED"))
    }

    @Test
    fun outstandingDebtTurnsGreenOnlyAfterItIsCleared() {
        assertEquals(OutstandingDebtRed, debtAmountColor(1L))
        assertEquals(IncomeGreen, debtAmountColor(0L))
    }

    @Test
    fun futureChargesUseTheirOwnYellowInsteadOfARepaymentOrReimbursementColor() {
        assertNotEquals(RepaymentPurple, ForecastChargeYellow)
        assertNotEquals(ReimbursementYellow, ForecastChargeYellow)
        assertNotEquals(PendingOrange, ForecastChargeYellow)
    }
}
