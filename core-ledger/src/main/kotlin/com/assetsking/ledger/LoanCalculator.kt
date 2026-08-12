package com.assetsking.ledger

import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.LoanPlan
import com.assetsking.model.Money
import kotlin.math.pow

data class LoanCostSummary(
    val totalPrincipal: Money,
    val totalInterest: Money,
    val totalFees: Money,
    val totalRepayment: Money,
    val annualizedRate: Double?
)

data class EarlyRepaymentResult(
    val savedInterest: Long,
    val newRemainingMonths: Int,
    val newTotalRepayment: Long
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

    /** 等额本息：每期固定月供 */
    fun equalPaymentSchedule(
        principalCents: Long,
        annualRateBps: Int,
        months: Int,
        startEpochDay: Long
    ): List<LoanInstallment> {
        if (principalCents <= 0 || months <= 0) return emptyList()
        val monthlyRate = annualRateBps / 100.0 / 100.0 / 12.0
        val monthlyPayment = if (monthlyRate == 0.0) {
            principalCents.toDouble() / months
        } else {
            principalCents * monthlyRate * (1 + monthlyRate).pow(months) / ((1 + monthlyRate).pow(months) - 1)
        }
        val result = mutableListOf<LoanInstallment>()
        var remaining = principalCents.toDouble()
        for (i in 1..months) {
            val interest = (remaining * monthlyRate).toLong()
            val principal = (monthlyPayment - interest).toLong().coerceAtMost(remaining.toLong())
            remaining -= principal
            result.add(LoanInstallment(
                number = i,
                dueDateEpochDay = startEpochDay + i * 30L,
                principal = Money(principal),
                interest = Money(interest)
            ))
        }
        return result
    }

    /** 等额本金：每期还等额本金，利息递减 */
    fun equalPrincipalSchedule(
        principalCents: Long,
        annualRateBps: Int,
        months: Int,
        startEpochDay: Long
    ): List<LoanInstallment> {
        if (principalCents <= 0 || months <= 0) return emptyList()
        val monthlyRate = annualRateBps / 100.0 / 100.0 / 12.0
        val monthlyPrincipal = principalCents / months
        val result = mutableListOf<LoanInstallment>()
        var remaining = principalCents
        for (i in 1..months) {
            val interest = (remaining * monthlyRate).toLong()
            val principal = if (i == months) remaining else monthlyPrincipal
            remaining -= principal
            result.add(LoanInstallment(
                number = i,
                dueDateEpochDay = startEpochDay + i * 30L,
                principal = Money(principal),
                interest = Money(interest)
            ))
        }
        return result
    }

    /** 先息后本：前N-1期只还利息，最后一期还本金+利息 */
    fun interestOnlySchedule(
        principalCents: Long,
        annualRateBps: Int,
        months: Int,
        startEpochDay: Long
    ): List<LoanInstallment> {
        if (principalCents <= 0 || months <= 0) return emptyList()
        val monthlyRate = annualRateBps / 100.0 / 100.0 / 12.0
        val monthlyInterest = (principalCents * monthlyRate).toLong()
        val result = mutableListOf<LoanInstallment>()
        for (i in 1..months) {
            result.add(
                if (i < months) LoanInstallment(
                    number = i,
                    dueDateEpochDay = startEpochDay + i * 30L,
                    principal = Money(0),
                    interest = Money(monthlyInterest)
                ) else LoanInstallment(
                    number = i,
                    dueDateEpochDay = startEpochDay + i * 30L,
                    principal = Money(principalCents),
                    interest = Money(monthlyInterest)
                )
            )
        }
        return result
    }

    /** 提前还款节省计算 */
    fun earlyRepaymentSavings(
        remainingPrincipalCents: Long,
        annualRateBps: Int,
        remainingMonths: Int,
        extraPaymentCents: Long
    ): EarlyRepaymentResult {
        val monthlyRate = annualRateBps / 100.0 / 100.0 / 12.0
        val oldSchedule = equalPaymentSchedule(remainingPrincipalCents, annualRateBps, remainingMonths, 0)
        val oldTotal = oldSchedule.sumOf { it.total.cents }
        val newPrincipal = remainingPrincipalCents - extraPaymentCents
        if (newPrincipal <= 0) return EarlyRepaymentResult(oldTotal, 0, extraPaymentCents)
        val newMonths = remainingMonths.coerceAtMost(
            if (monthlyRate == 0.0) remainingMonths
            else kotlin.math.ceil(
                kotlin.math.ln(1 - newPrincipal * monthlyRate / (oldSchedule.firstOrNull()?.total?.cents?.toDouble() ?: 1.0))
                / kotlin.math.ln(1 + monthlyRate)
            ).toInt().coerceAtLeast(1)
        )
        val newSchedule = equalPaymentSchedule(newPrincipal, annualRateBps, newMonths, 0)
        val newTotal = newSchedule.sumOf { it.total.cents }
        return EarlyRepaymentResult(
            savedInterest = oldTotal - newTotal - extraPaymentCents,
            newRemainingMonths = newMonths,
            newTotalRepayment = newTotal
        )
    }

    private fun annualizedRate(principal: Long, startDay: Long, installments: List<LoanInstallment>): Double? {
        if (principal <= 0 || installments.isEmpty()) return null
        fun npv(rate: Double): Double = installments.sumOf { installment ->
            val years = (installment.dueDateEpochDay - startDay).toDouble() / 365.0
            installment.total.cents.toDouble() / (1.0 + rate).pow(years)
        } - principal.toDouble()
        var low = -0.9999; var high = 100.0
        if (npv(low) * npv(high) > 0) return null
        repeat(120) { val mid = (low + high) / 2.0; if (npv(mid) > 0) low = mid else high = mid }
        return (low + high) / 2.0
    }
}
