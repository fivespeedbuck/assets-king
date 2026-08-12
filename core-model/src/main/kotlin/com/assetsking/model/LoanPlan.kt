package com.assetsking.model

enum class RepaymentMethod { CUSTOM, EQUAL_PAYMENT, EQUAL_PRINCIPAL, INTEREST_ONLY }

enum class InstallmentStatus { UPCOMING, PAID, OVERDUE }

data class LoanPlan(
    val id: String,
    val accountId: String,
    val principal: Money,
    val startDateEpochDay: Long,
    val repaymentMethod: RepaymentMethod = RepaymentMethod.CUSTOM,
    val installments: List<LoanInstallment> = emptyList()
)

data class LoanInstallment(
    val number: Int,
    val dueDateEpochDay: Long,
    val principal: Money,
    val interest: Money,
    val fee: Money = Money.ZERO,
    val status: InstallmentStatus = InstallmentStatus.UPCOMING
) {
    val total: Money get() = Money(principal.cents + interest.cents + fee.cents)
}
