package com.assetsking.ledger

import com.assetsking.model.WindfallStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// ── 输入 DTO（金额一律 Long 分；core-ledger 不依赖 core-database，实体映射在 usecase）──

data class V5AccountInput(
    val id: String,
    val type: String,               // AccountType.name
    val balanceCents: Long,
    val statementOriginalDueCents: Long, // 本期待还【原始账单金额】：还款后不重录，系统按账期扣已还
    val pendingCents: Long,         // 仅展示，不计总负债
    val statementDay: Int?,
    val dueDay: Int?
)

data class V5InstallmentInput(
    val dueDateEpochDay: Long,
    val principalCents: Long,
    val interestCents: Long,
    val feeCents: Long,
    val isPaid: Boolean
) {
    val totalCents: Long get() = principalCents + interestCents + feeCents
}

data class V5PlanInput(
    val accountId: String,
    val remainingPrincipalCents: Long,
    val fallbackPrincipalCents: Long,   // principalCents - earlyRepaidCents，remaining=0 时回退
    val annualRateBps: Int,
    val repaymentDay: Int?,
    val status: String = "ACTIVE",      // ACTIVE / PAID_OFF；结清后剩余本金必须为 0，不回退
    val installments: List<V5InstallmentInput>
) {
    val remainingEffectiveCents: Long
        get() = effectiveRemainingPrincipalCents(remainingPrincipalCents, fallbackPrincipalCents, status)
}

/** 剩余有效本金唯一计算点——LedgerRepository.remainingEffective() 复用同一份，禁止另起公式。
 *  PAID_OFF 结清后直接为 0：不能因为 earlyRepaidCents 未同步而回退出原始本金（曾导致结清后负债复活）。 */
fun effectiveRemainingPrincipalCents(remainingPrincipalCents: Long, fallbackPrincipalCents: Long, status: String): Long =
    when {
        status == "PAID_OFF" -> 0
        remainingPrincipalCents > 0 -> remainingPrincipalCents
        else -> fallbackPrincipalCents
    }

// 信用卡分期：仅展示与预测，绝不进 totalDebt（已在卡 balance 内）
data class V5CardInstallmentInput(
    val cardAccountId: String,
    val remainingPrincipalCents: Long,
    val monthlyPaymentCents: Long,
    val periodsRemaining: Int
)

data class V5WindfallInput(
    val expectedAmountCents: Long,
    val expectedDateEpochDay: Long,
    val plannedDebtPaymentCents: Long = 0, // 计划用于降债的金额（指引，不自动执行）
    val status: WindfallStatus
)

data class V5CardTransferInput(
    val cardAccountId: String,
    val transferredCents: Long      // 本月转入该卡的金额（还款，用于 mustRepay 扣减）
)

data class V5SettingsInput(
    val monthlyIncomeCents: Long,   // 稳定月收入
    val necessaryLivingCents: Long  // 必要生活预算
)

// 预聚合的本月流水数字（usecase 算好传入，保持公式层纯函数）
data class V5MonthFlow(
    val incomeActualCents: Long,    // 本月 INCOME 流水之和；REFUND 不计入普通收入（REQ 收入§5），冲减走退款关联
    val feeMonthCents: Long,        // 本月 FEE 流水之和
    val newBorrowingCents: Long,    // 本月 LOAN_DISBURSEMENT 之和
    val optionalSpentCents: Long = 0, // 本月已发生非必要消费（按设置的非必要分类聚合）
    val todayOptionalSpentCents: Long = 0 // 今日已发生非必要消费（对应「今日上限」）
)

// ── 输出 ──

enum class DebtTrend { REDUCING, FLAT, GROWING, NO_ANCHOR }

enum class DebtStage { DEBT_FREE, CASH_SURVIVAL, LIVE_TO_BONUS, BONUS_PAYDOWN, STOP_ROLLOVER, STABLE_REDUCTION }

data class V5Metrics(
    val totalDebtCents: Long,
    val availableCashCents: Long,   // 手上真能动的钱 = ASSET 账户余额之和；未到账年终奖不算（铁律 8）
    val cardDebtCents: Long,
    val loanAccountDebtCents: Long,
    val loanPlanDebtCents: Long,
    val accruedInterestCents: Long,
    val cardInstallmentRemainingCents: Long,
    val mustRepayCents: Long,
    val cardRepayPartCents: Long,
    val loanRepayPartCents: Long,
    val feeRepayPartCents: Long,
    val monthlySurvivalGapCents: Long,
    val incomeActualCents: Long,
    val newBorrowingCents: Long,
    val optionalSpentCents: Long,
    val todayOptionalSpentCents: Long,
    val netDebtReductionCents: Long,    // anchor − totalDebt；无锚点=0
    val anchorTotalDebtCents: Long?,    // null = 本月未建档
    val freeSpendingCents: Long,
    val dailySafeSpendCents: Long,
    val stableDebtCoverageCents: Long,
    val trend: DebtTrend,
    val stage: DebtStage,
    val needsAttention: Boolean,
    val due7DaysCents: Long,
    val due30DaysCents: Long,
    val cardRemainingDueByCard: Map<String, Long>,  // 卡详情/扣款日历用：每张卡剩余应还（统一口径）
    val runwayToWindfallDays: Long?,
    val projectedDebtAtWindfallCents: Long?,
    val projectedDebtAfterWindfallCents: Long?,
    val projectedPayoffMonths: Int?     // 从下月算起第几个月清债；null = 24 月内清不完或无法预测
)

fun computeV5Metrics(
    todayEpochDay: Long,
    currentYearMonth: String,           // "2026-08"
    accounts: List<V5AccountInput>,
    plans: List<V5PlanInput>,
    cardInstallments: List<V5CardInstallmentInput>,
    windfalls: List<V5WindfallInput>,
    cardTransfers: List<V5CardTransferInput>,
    anchorTotalDebtCents: Long?,
    recentMonthlyBorrowAvgCents: Long,  // 近 3 个月 LOAN_DISBURSEMENT 均值，模拟用
    settings: V5SettingsInput,
    month: V5MonthFlow
): V5Metrics {
    val today = LocalDate.ofEpochDay(todayEpochDay)
    val ym = YearMonth.parse(currentYearMonth)
    val monthStart = ym.atDay(1)
    val monthEnd = ym.atEndOfMonth()

    // ── ① 总负债：卡余额 + 无计划覆盖的 LOAN 账户 + 计划剩余本金 + 已到期未付利息 ──
    val plannedAccountIds = plans.map { it.accountId }.toSet()
    val cardDebt = accounts.filter { it.type == "CREDIT" }.sumOf { it.balanceCents }
    val loanAccountDebt = accounts
        .filter { it.type == "LOAN" && it.id !in plannedAccountIds }
        .sumOf { it.balanceCents }
    val loanPlanDebt = plans.sumOf { it.remainingEffectiveCents }
    val accruedInterest = plans.sumOf { plan ->
        plan.installments
            .filter { !it.isPaid && LocalDate.ofEpochDay(it.dueDateEpochDay) <= today }
            .sumOf { it.interestCents + it.feeCents }
    }
    val totalDebt = cardDebt + loanAccountDebt + loanPlanDebt + accruedInterest

    // 可用现金：只认真实到账的资产账户余额。EXPECTED 年终奖不在任何账户余额里，天然不计入（铁律 8）
    val availableCash = accounts.filter { it.type == "ASSET" }.sumOf { it.balanceCents }

    // ── ② 本月必须还款（方案A：录原始账单，系统扣已还）──
    val transferredByCard = cardTransfers.associate { it.cardAccountId to it.transferredCents }

    /** 统一口径：卡的当前剩余应还 = 原始账单 − 本账期已转入该卡。
     *  mustRepay / 未来7天 / 未来30天 / 卡详情 全部消费这一个结果，禁止另起公式 */
    fun remainingStatementDue(a: V5AccountInput): Long =
        maxOf(0, a.statementOriginalDueCents - (transferredByCard[a.id] ?: 0))

    val cardRepayPart = accounts
        .filter { it.type == "CREDIT" }
        .sumOf { remainingStatementDue(it) }
    val loanRepayPart = plans.sumOf { plan ->
        plan.installments
            .filter { !it.isPaid }
            .filter { LocalDate.ofEpochDay(it.dueDateEpochDay) <= monthEnd }
            .sumOf { it.totalCents }
    }
    val feeRepayPart = month.feeMonthCents
    val mustRepay = cardRepayPart + loanRepayPart + feeRepayPart

    // ── ③④ 资金缺口 / 新增借款（单独统计，不进收入）──
    // 缺口把"已发生的非必要消费"算进去：原本 -3000 又花 80 非必要 → -3080（V5 §44）
    val gap = month.incomeActualCents - settings.necessaryLivingCents - mustRepay - month.optionalSpentCents
    val newBorrowing = month.newBorrowingCents

    // ── ⑤ 净降债：月初锚点 − 当前总负债；无锚点（本月刚建档）按 0 处理 ──
    val netDebtReduction = anchorTotalDebtCents?.let { it - totalDebt } ?: 0

    // ── ⑥ 自由消费 / 每日安全额度 ──
    val freeSpending = maxOf(0, gap)
    val daysLeft = (ym.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
    val dailySafeSpend = freeSpending / daysLeft

    // ── ⑦ 稳定覆盖 ──
    val stableCoverage = settings.monthlyIncomeCents - settings.necessaryLivingCents - mustRepay

    // ── ⑧ 趋势 / ⑨ 阶段 ──
    val trend = when {
        anchorTotalDebtCents == null -> DebtTrend.NO_ANCHOR
        totalDebt < anchorTotalDebtCents -> DebtTrend.REDUCING
        totalDebt == anchorTotalDebtCents -> DebtTrend.FLAT
        else -> DebtTrend.GROWING
    }
    val stage = when {
        totalDebt <= 0 -> DebtStage.DEBT_FREE
        gap < 0 -> DebtStage.CASH_SURVIVAL
        windfalls.any { it.status == WindfallStatus.EXPECTED } -> DebtStage.LIVE_TO_BONUS
        windfalls.any { it.status == WindfallStatus.RECEIVED } -> DebtStage.BONUS_PAYDOWN
        newBorrowing > 0 -> DebtStage.STOP_ROLLOVER
        else -> DebtStage.STABLE_REDUCTION
    }
    val needsAttention = stage == DebtStage.STABLE_REDUCTION && trend != DebtTrend.REDUCING

    // ── ⑩ 未来 7/30 天应还 ──
    fun dueInWindow(days: Int): Long {
        val end = today.plusDays(days.toLong())
        val loanPart = plans.sumOf { plan ->
            plan.installments
                .filter { !it.isPaid }
                .filter { LocalDate.ofEpochDay(it.dueDateEpochDay) in today.plusDays(1)..end }
                .sumOf { it.totalCents }
        }
        val cardPart = accounts.filter { it.type == "CREDIT" && it.dueDay != null }.sumOf { a ->
            val next = nextCardDueDate(today, a.dueDay!!)
            if (!next.isAfter(end)) remainingStatementDue(a) else 0
        }
        return loanPart + cardPart
    }
    val due7 = dueInWindow(7)
    val due30 = dueInWindow(30)

    // ── ⑪ 到年终奖前还能撑多久 ──
    val expectedWindfall = windfalls
        .filter { it.status == WindfallStatus.EXPECTED }
        .minByOrNull { it.expectedDateEpochDay }
    val runwayDays = expectedWindfall?.let { it.expectedDateEpochDay - todayEpochDay }

    // ── ⑫ 24 个月清债模拟（方向正确即可，允许不精确）──
    val weightedRate = plans.let { ps ->
        val sumRemaining = ps.sumOf { it.remainingEffectiveCents }
        if (sumRemaining <= 0) 0.0
        else ps.sumOf { it.remainingEffectiveCents.toDouble() * it.annualRateBps } / sumRemaining / 10000.0
    }
    // 月度还债额 = 收入 − 必要生活（稳定覆盖 + 必须还）：V5 方针是结余全部往债上砸
    val monthlyDebtService = stableCoverage + mustRepay
    val payoffMonths = simulatePayoff(
        totalDebt, monthlyDebtService, recentMonthlyBorrowAvgCents, weightedRate, todayEpochDay, windfalls
    )

    // 年终奖前后负债预测：按当前月度净变化外推到到账日
    val projectedAtWindfall = expectedWindfall?.let { wf ->
        var debt = totalDebt
        val monthsUntil = ((wf.expectedDateEpochDay - todayEpochDay + 29) / 30).coerceAtLeast(0)
        repeat(monthsUntil.toInt()) {
            val interest = (debt.toDouble() * weightedRate / 12.0).toLong()
            debt = maxOf(0, debt + recentMonthlyBorrowAvgCents + interest - mustRepay)
        }
        debt
    }
    val projectedAfterWindfall = expectedWindfall?.let { wf ->
        projectedAtWindfall?.let { d -> maxOf(0, d - wf.plannedDebtPaymentCents) }
    }

    return V5Metrics(
        totalDebtCents = totalDebt,
        availableCashCents = availableCash,
        cardDebtCents = cardDebt,
        loanAccountDebtCents = loanAccountDebt,
        loanPlanDebtCents = loanPlanDebt,
        accruedInterestCents = accruedInterest,
        cardInstallmentRemainingCents = cardInstallments.sumOf { it.remainingPrincipalCents },
        mustRepayCents = mustRepay,
        cardRepayPartCents = cardRepayPart,
        loanRepayPartCents = loanRepayPart,
        feeRepayPartCents = feeRepayPart,
        monthlySurvivalGapCents = gap,
        incomeActualCents = month.incomeActualCents,
        newBorrowingCents = newBorrowing,
        optionalSpentCents = month.optionalSpentCents,
        todayOptionalSpentCents = month.todayOptionalSpentCents,
        netDebtReductionCents = netDebtReduction,
        anchorTotalDebtCents = anchorTotalDebtCents,
        freeSpendingCents = freeSpending,
        dailySafeSpendCents = dailySafeSpend,
        stableDebtCoverageCents = stableCoverage,
        trend = trend,
        stage = stage,
        needsAttention = needsAttention,
        due7DaysCents = due7,
        due30DaysCents = due30,
        cardRemainingDueByCard = accounts
            .filter { it.type == "CREDIT" }
            .associate { it.id to remainingStatementDue(it) },
        runwayToWindfallDays = runwayDays,
        projectedDebtAtWindfallCents = projectedAtWindfall,
        projectedDebtAfterWindfallCents = projectedAfterWindfall,
        projectedPayoffMonths = payoffMonths
    )
}

/** 信用卡下一还款日（还款日已过则推下月；31 日在小月取月末） */
internal fun nextCardDueDate(today: LocalDate, dueDay: Int): LocalDate {
    val thisMonth = YearMonth.from(today)
    val thisDue = thisMonth.atDay(dueDay.coerceAtMost(thisMonth.lengthOfMonth()))
    if (!thisDue.isBefore(today)) return thisDue
    val nextMonth = thisMonth.plusMonths(1)
    return nextMonth.atDay(dueDay.coerceAtMost(nextMonth.lengthOfMonth()))
}

/** 逐月模拟：期初负债 + 新增借款 + 利息 − 本金偿还 − 到期的年终奖（EXPECTED 按计划还债额注入）；
 *  ≤0 记清债月，不收敛（且后续无年终奖兜底）或超 24 月返回 null */
internal fun simulatePayoff(
    totalDebtCents: Long,
    mustRepayCents: Long,
    monthlyBorrowAvgCents: Long,
    weightedAnnualRate: Double,
    todayEpochDay: Long = 0,
    windfalls: List<V5WindfallInput> = emptyList()
): Int? {
    if (totalDebtCents <= 0) return 0
    if (mustRepayCents <= 0 && monthlyBorrowAvgCents == 0L && weightedAnnualRate == 0.0 && windfalls.isEmpty()) return null
    val todayYm = YearMonth.from(LocalDate.ofEpochDay(todayEpochDay))
    val injections = mutableMapOf<Int, Long>()
    for (wf in windfalls) {
        if (wf.status != WindfallStatus.EXPECTED) continue
        val wfYm = YearMonth.from(LocalDate.ofEpochDay(wf.expectedDateEpochDay))
        // 月份差 = 模拟月序号（本月=0→夹成 1：当月到账下月首期还款就能用）
        val m = ChronoUnit.MONTHS.between(todayYm, wfYm).toInt().coerceAtLeast(1)
        if (m in 1..24) injections[m] = (injections[m] ?: 0) + wf.plannedDebtPaymentCents
    }
    var debt = totalDebtCents
    for (m in 1..24) {
        val interest = (debt.toDouble() * weightedAnnualRate / 12.0).toLong()
        val next = debt + monthlyBorrowAvgCents + interest - mustRepayCents - (injections[m] ?: 0)
        // 月供盖不住利息才不收敛；后面还有年终奖到期的，允许暂时走平
        if (next >= debt && injections.none { it.key > m }) return null
        if (next <= 0) return m
        debt = next
    }
    return null
}
