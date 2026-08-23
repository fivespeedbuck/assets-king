package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.model.InstallmentStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class UpcomingRepayment(
    val planId: String,
    val totalCents: Long,
    val dueDateEpochDay: Long,
    val overdue: Boolean
)

internal fun sortUpcomingRepayments(items: List<UpcomingRepayment>): List<UpcomingRepayment> =
    items.sortedWith(compareByDescending<UpcomingRepayment> { it.overdue }.thenBy { it.dueDateEpochDay })

/**
 * 最近还款提醒（REQ 首页信息优先级§8/首页UI§6-7）：到期前 3 天进入窗口，逾期红显。
 * 只统计进行中计划里未还的期次；已还（PAID）不参与。
 */
class UpcomingRepaymentsUseCase(private val repository: LedgerRepository) {

    fun invoke(): Flow<List<UpcomingRepayment>> = repository.loanPlans.map { plans ->
        val today = LocalDate.now().toEpochDay()
        plans.filter { it.status == "ACTIVE" }.flatMap { plan ->
            repository.v5PlanInput(plan).installments.mapNotNull { inst ->
                if (inst.isPaid) return@mapNotNull null
                val due = inst.dueDateEpochDay
                if (due > today + 2) return@mapNotNull null // 3 天窗口外不显示（REQ 首页UI§6）
                UpcomingRepayment(
                    planId = plan.id,
                    totalCents = inst.principalCents + inst.interestCents + inst.feeCents,
                    dueDateEpochDay = due,
                    overdue = due < today
                )
            }
        }.let(::sortUpcomingRepayments)
    }
}
