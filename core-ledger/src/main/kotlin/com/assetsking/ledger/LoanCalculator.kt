package com.assetsking.ledger

import com.assetsking.model.LoanInstallment
import com.assetsking.model.LoanPlan
import com.assetsking.model.Money
import kotlin.math.abs
import kotlin.math.pow

data class LoanCostSummary(
    val totalPrincipal: Money,
    val totalInterest: Money,
    val totalFees: Money,
    val totalRepayment: Money,
    /** Effective annualized rate derived from dated cash flows, expressed as a decimal. */
    val annualizedRate: Double?
)

object LoanCalculator {
    fun summarize(plan: LoanPlan): LoanCostSummary {
        val principal = plan.installments.sumOf { it.principal.cents }
        val interest = plan.installments.sumOf { it.interest.cents }
        val fees = plan.installments.sumOf { it.fee.cents }
        val repayment = principal + interest + fees
        return LoanCostSummary(
            totalPrincipal = Money(principal),
            totalInterest = Money(interest),
            totalFees = Money(fees),
            totalRepayment = Money(repayment),
            annualizedRate = annualizedRate(plan.principal.cents, plan.startDateEpochDay, plan.installments)
        )
    }

    /**
     * Solves the annual effective rate r for dated cash flows:
     * principal = Σ payment / (1 + r)^(days / 365).
     * Binary search is stable for user-entered custom schedules.
     */
    private fun annualizedRate(principal: Long, startDay: Long, installments: List<LoanInstallment>): Double? {
        if (principal <= 0 || installments.isEmpty()) return null
        fun npv(rate: Double): Double = installments.sumOf { installment ->
            val years = (installment.dueDateEpochDay - startDay).toDouble() / 365.0
            installment.total.cents.toDouble() / (1.0 + rate).pow(years)
        } - principal.toDouble()

        var low = -0.9999
        var high = 100.0
        if (npv(low) * npv(high) > 0) return null
        repeat(120) {
            val mid = (low + high) / 2.0
            if (npv(mid) > 0) low = mid else high = mid
        }
        return (low + high) / 2.0
    }
}
