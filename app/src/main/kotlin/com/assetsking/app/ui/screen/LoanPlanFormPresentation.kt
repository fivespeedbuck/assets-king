package com.assetsking.app.ui.screen

import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

internal val CommonLoanInstallmentCounts = listOf(6, 12, 24, 36, 60, 120, 240, 360)

internal fun loanInstallmentCountLabel(count: Int): String = when (count) {
    60 -> "60期·5年"
    120 -> "120期·10年"
    240 -> "240期·20年"
    360 -> "360期·30年"
    else -> "${count}期"
}

internal fun loanInstallmentCountError(count: Int?, paidCount: Int?): String? = when {
    count == null || count !in 1..600 -> "总期数须为 1—600 期"
    paidCount == null || paidCount !in 0..count -> "已还期数须为 0—${count} 期"
    else -> null
}

internal const val MaxDirectCustomLoanInstallments = 60

internal fun parseAnnualRateBps(value: String): Int = runCatching {
    BigDecimal(value.trim())
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact()
}.getOrDefault(0)

internal fun applyUniformPaymentToUpcoming(
    installments: List<LoanInstallment>,
    totalCents: Long
): List<LoanInstallment>? {
    if (totalCents <= 0L || installments.any {
            it.status != InstallmentStatus.PAID && totalCents < it.principal.cents + it.fee.cents
        }) return null
    return installments.map { installment ->
        if (installment.status == InstallmentStatus.PAID) installment
        else installment.copy(interest = Money(totalCents - installment.principal.cents - installment.fee.cents))
    }
}

internal data class CustomLoanInstallmentDraft(
    val number: Int,
    val dueDate: String,
    val principal: String,
    val interest: String = "0.00",
    val fee: String = "0.00",
    val status: InstallmentStatus = InstallmentStatus.UPCOMING
)

internal data class CustomLoanScheduleValidation(
    val installments: List<LoanInstallment> = emptyList(),
    val error: String? = null
)

internal fun loanInstallmentsToDrafts(installments: List<LoanInstallment>): List<CustomLoanInstallmentDraft> =
    installments.sortedBy { it.number }.mapIndexed { index, installment ->
        CustomLoanInstallmentDraft(
            number = index + 1,
            dueDate = LocalDate.ofEpochDay(installment.dueDateEpochDay).toString(),
            principal = installment.principal.cents.toMoneyInput(),
            interest = installment.interest.cents.toMoneyInput(),
            fee = installment.fee.cents.toMoneyInput(),
            status = installment.status
        )
    }

internal fun generateCustomLoanInstallmentDrafts(
    count: Int,
    principalCents: Long,
    firstDueDate: LocalDate,
    repaymentDay: Int = firstDueDate.dayOfMonth,
    paidCount: Int = 0
): List<CustomLoanInstallmentDraft> {
    require(count in 1..MaxDirectCustomLoanInstallments)
    require(principalCents > 0L)
    val base = principalCents / count
    val remainder = principalCents % count
    return List(count) { index ->
        CustomLoanInstallmentDraft(
            number = index + 1,
            dueDate = loanDueDate(firstDueDate, repaymentDay, index).toString(),
            principal = (base + if (index < remainder) 1L else 0L).toMoneyInput(),
            status = if (index < paidCount) InstallmentStatus.PAID else InstallmentStatus.UPCOMING
        )
    }
}

internal fun loanDueDate(firstDueDate: LocalDate, repaymentDay: Int, monthOffset: Int): LocalDate {
    require(repaymentDay in 1..31)
    require(monthOffset >= 0)
    val month = YearMonth.from(firstDueDate).plusMonths(monthOffset.toLong())
    return month.atDay(repaymentDay.coerceAtMost(month.lengthOfMonth()))
}

internal fun validateCustomLoanSchedule(
    drafts: List<CustomLoanInstallmentDraft>,
    expectedCount: Int,
    expectedPrincipalCents: Long
): CustomLoanScheduleValidation {
    if (expectedCount !in 1..MaxDirectCustomLoanInstallments) {
        return CustomLoanScheduleValidation(error = "逐期自填支持 1—${MaxDirectCustomLoanInstallments} 期；更长期限请使用自动计算方式")
    }
    if (drafts.size != expectedCount) {
        return CustomLoanScheduleValidation(error = "请先生成 ${expectedCount} 期逐期填写表")
    }
    val parsed = mutableListOf<LoanInstallment>()
    var previousDueDate: LocalDate? = null
    drafts.forEachIndexed { index, draft ->
        val dueDate = runCatching { LocalDate.parse(draft.dueDate) }.getOrNull()
            ?: return CustomLoanScheduleValidation(error = "第${index + 1}期还款日期格式不正确")
        if (previousDueDate != null && !dueDate.isAfter(previousDueDate)) {
            return CustomLoanScheduleValidation(error = "第${index + 1}期还款日期必须晚于上一期")
        }
        previousDueDate = dueDate
        val principal = draft.principal.toNonNegativeCents()
            ?: return CustomLoanScheduleValidation(error = "第${index + 1}期本金格式不正确")
        val interest = draft.interest.toNonNegativeCents()
            ?: return CustomLoanScheduleValidation(error = "第${index + 1}期利息格式不正确")
        val fee = draft.fee.toNonNegativeCents()
            ?: return CustomLoanScheduleValidation(error = "第${index + 1}期手续费格式不正确")
        if (principal + interest + fee <= 0L) {
            return CustomLoanScheduleValidation(error = "第${index + 1}期应还金额不能全部为 0")
        }
        parsed += LoanInstallment(
            number = index + 1,
            dueDateEpochDay = dueDate.toEpochDay(),
            principal = Money(principal),
            interest = Money(interest),
            fee = Money(fee),
            status = draft.status
        )
    }
    val principalTotal = parsed.sumOf { it.principal.cents }
    if (principalTotal != expectedPrincipalCents) {
        return CustomLoanScheduleValidation(
            error = "逐期本金合计 ${principalTotal.toMoneyInput()} 元，必须等于贷款本金 ${expectedPrincipalCents.toMoneyInput()} 元"
        )
    }
    return CustomLoanScheduleValidation(installments = parsed)
}

internal fun customLoanDraftTotal(draft: CustomLoanInstallmentDraft): String {
    val total = listOf(draft.principal, draft.interest, draft.fee)
        .map { it.toNonNegativeCents() ?: 0L }
        .sum()
    return total.toMoneyInput()
}

/** 默认编辑只暴露本期待还总额；本金保持不变，差额归入预计利息。 */
internal fun customLoanDraftWithTotal(
    draft: CustomLoanInstallmentDraft,
    totalInput: String
): CustomLoanInstallmentDraft? {
    val total = totalInput.toNonNegativeCents() ?: return null
    val principal = draft.principal.toNonNegativeCents() ?: return null
    val fee = draft.fee.toNonNegativeCents() ?: return null
    if (total < principal + fee) return null
    return draft.copy(interest = (total - principal - fee).toMoneyInput())
}

private fun Long.toMoneyInput(): String = BigDecimal.valueOf(this, 2).setScale(2).toPlainString()

private fun String.toNonNegativeCents(): Long? = runCatching {
    BigDecimal(ifBlank { "0" }.trim())
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()?.takeIf { it >= 0L }
