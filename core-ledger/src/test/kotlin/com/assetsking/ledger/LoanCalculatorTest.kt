package com.assetsking.ledger

import com.assetsking.model.LoanInstallment
import com.assetsking.model.LoanPlan
import com.assetsking.model.Money
import kotlin.math.roundToLong
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

    @Test
    fun `generated schedules always preserve every cent of principal`() {
        val principal = 5_000_000L

        listOf(
            LoanCalculator.equalPaymentSchedule(principal, 803, 36, 0),
            LoanCalculator.equalPrincipalSchedule(principal, 803, 360, 0),
            LoanCalculator.interestOnlySchedule(principal, 803, 12, 0)
        ).forEach { schedule ->
            assertEquals(principal, schedule.sumOf { it.principal.cents })
            assertTrue(schedule.all { it.principal.cents >= 0L && it.interest.cents >= 0L })
        }
    }

    @Test
    fun `real bank custom schedule can be verified without importing user data`() {
        val principalPartsYuan = listOf(
            1194.74, 1246.63, 1244.40, 1263.07, 1261.50, 1270.10,
            1306.82, 1287.67, 1305.24, 1305.36, 1322.47, 1323.28,
            1332.30, 1348.72, 1350.59, 1366.54, 1369.12, 1378.46,
            1399.53, 1397.40, 1412.15, 1416.56, 1430.82, 1435.98,
            1445.78, 1459.29, 1465.59, 1478.59, 1485.67, 1495.80,
            1512.07, 1516.31, 1528.01, 1537.08, 1548.24, 1558.12
        )
        val interestPartsYuan = listOf(
            374.00, 322.11, 324.34, 305.67, 307.24, 298.64,
            261.92, 281.07, 263.50, 263.38, 246.27, 245.46,
            236.44, 220.02, 218.15, 202.20, 199.62, 190.28,
            169.21, 171.34, 156.59, 152.18, 137.92, 132.76,
            122.96, 109.45, 103.15, 90.15, 83.07, 72.94,
            56.67, 52.43, 40.73, 31.66, 20.50, 10.62
        )
        fun cents(yuan: Double) = (yuan * 100).roundToLong()
        val schedule = principalPartsYuan.indices.map { index ->
            LoanInstallment(
                number = index + 1,
                dueDateEpochDay = 30L * (index + 1),
                principal = Money(cents(principalPartsYuan[index])),
                interest = Money(cents(interestPartsYuan[index]))
            )
        }
        val summary = LoanCalculator.summarize(
            LoanPlan("reference-only", "loan", Money(5_000_000L), 0L, installments = schedule)
        )

        assertEquals(36, schedule.size)
        assertTrue(schedule.all { it.total.cents == 156_874L })
        assertEquals(5_000_000L, summary.totalPrincipal.cents)
        assertEquals(647_464L, summary.totalInterest.cents)
        assertEquals(5_647_464L, summary.totalRepayment.cents)
    }
}
