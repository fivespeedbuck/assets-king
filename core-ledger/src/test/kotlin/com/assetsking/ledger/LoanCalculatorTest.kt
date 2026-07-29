package com.assetsking.ledger

import com.assetsking.model.LoanInstallment
import com.assetsking.model.LoanPlan
import com.assetsking.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoanCalculatorTest {
    @Test
    fun `custom plan sums interest fees and repayment`() {
        val plan = LoanPlan(
            id = "loan-1",
            accountId = "huabei",
            principal = Money.yuan(1000),
            startDateEpochDay = 0,
            installments = listOf(
                LoanInstallment(1, 30, Money.yuan(500), Money.yuan(20), Money.yuan(3)),
                LoanInstallment(2, 60, Money.yuan(500), Money.yuan(10), Money.yuan(2))
            )
        )
        val summary = LoanCalculator.summarize(plan)
        assertEquals(Money.yuan(1000), summary.totalPrincipal)
        assertEquals(Money.yuan(30), summary.totalInterest)
        assertEquals(Money.yuan(5), summary.totalFees)
        assertEquals(Money.yuan(1035), summary.totalRepayment)
        assertNotNull(summary.annualizedRate)
        assertTrue(summary.annualizedRate!! > 0)
    }
}
