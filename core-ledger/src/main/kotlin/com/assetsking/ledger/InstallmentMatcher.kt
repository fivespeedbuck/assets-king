package com.assetsking.ledger

import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import kotlin.math.abs

/**
 * 贷款扣款通知匹配期次（REQ 贷款页 §6-8）纯函数。
 *
 * 只在未还（UPCOMING/OVERDUE）期次中匹配：优先总金额完全一致且日期最接近的；
 * 没有完全一致时取总金额最接近的（允许手续费/四舍五入差异），供确认前核对修改。
 * 已还期次不会再收到扣款通知，不参与匹配。
 */
object InstallmentMatcher {
    fun match(installments: List<LoanInstallment>, amountCents: Long, atEpochDay: Long): LoanInstallment? {
        val open = installments.filter { it.status != InstallmentStatus.PAID }
        if (open.isEmpty()) return null
        val exact = open.filter { it.total.cents == amountCents }
        if (exact.isNotEmpty()) {
            return exact.minByOrNull { abs(it.dueDateEpochDay - atEpochDay) }
        }
        return open.minByOrNull { abs(it.total.cents - amountCents) }
    }
}
