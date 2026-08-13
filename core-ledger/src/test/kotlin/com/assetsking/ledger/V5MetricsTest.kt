package com.assetsking.ledger

import com.assetsking.model.WindfallStatus
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V5MetricsTest {

    private val today = LocalDate.of(2026, 8, 13)

    private fun card(
        id: String = "cgb",
        balance: Long = 4_000_000,
        statementOriginalDue: Long = 600_000,
        dueDay: Int? = 20,
        type: String = "CREDIT"
    ) = V5AccountInput(id, type, balance, statementOriginalDue, pendingCents = 0, statementDay = 8, dueDay = dueDay)

    private fun installment(
        due: LocalDate,
        principal: Long = 100_000,
        interest: Long = 10_000,
        fee: Long = 0,
        paid: Boolean = false
    ) = V5InstallmentInput(due.toEpochDay(), principal, interest, fee, paid)

    private fun plan(
        accountId: String = "huabei",
        remaining: Long = 1_000_000,
        fallback: Long = remaining,
        status: String = "ACTIVE",
        installments: List<V5InstallmentInput> = emptyList()
    ) = V5PlanInput(accountId, remaining, fallbackPrincipalCents = fallback, annualRateBps = 450,
        repaymentDay = 20, status = status, installments = installments)

    private fun metrics(
        accounts: List<V5AccountInput> = emptyList(),
        plans: List<V5PlanInput> = emptyList(),
        cardInstallments: List<V5CardInstallmentInput> = emptyList(),
        windfalls: List<V5WindfallInput> = emptyList(),
        cardTransfers: List<V5CardTransferInput> = emptyList(),
        anchor: Long? = null,
        borrowAvg: Long = 0,
        settings: V5SettingsInput = V5SettingsInput(monthlyIncomeCents = 700_000, necessaryLivingCents = 350_000),
        month: V5MonthFlow = V5MonthFlow(incomeActualCents = 0, feeMonthCents = 0, newBorrowingCents = 0)
    ) = computeV5Metrics(
        todayEpochDay = today.toEpochDay(),
        currentYearMonth = "2026-08",
        accounts = accounts, plans = plans, cardInstallments = cardInstallments,
        windfalls = windfalls, cardTransfers = cardTransfers,
        anchorTotalDebtCents = anchor, recentMonthlyBorrowAvgCents = borrowAvg,
        settings = settings, month = month
    )

    // ── V5 §63 Case 1/2：借款不是收入 ──

    @Test
    fun `new borrowing is tracked separately and never enters income`() {
        val m = metrics(
            month = V5MonthFlow(incomeActualCents = 700_000, feeMonthCents = 0, newBorrowingCents = 300_000)
        )
        assertEquals(700_000, m.incomeActualCents)
        assertEquals(300_000, m.newBorrowingCents)
        assertEquals(350_000, m.monthlySurvivalGapCents) // 7000-3500-0，与借款无关
    }

    // ── V5 §63 Case 4：信用卡还款不是消费，且 mustRepay 扣已还 ──

    @Test
    fun `card repayment reduces mustRepay and never becomes expense`() {
        val unpaid = metrics(accounts = listOf(card(statementOriginalDue = 200_000)))
        assertEquals(200_000, unpaid.cardRepayPartCents)

        val paid = metrics(
            accounts = listOf(card(statementOriginalDue = 200_000)),
            cardTransfers = listOf(V5CardTransferInput("cgb", 200_000))
        )
        assertEquals(0, paid.cardRepayPartCents)

        // 多还的部分不把必须还款扣成负数
        val overpaid = metrics(
            accounts = listOf(card(statementOriginalDue = 200_000)),
            cardTransfers = listOf(V5CardTransferInput("cgb", 300_000))
        )
        assertEquals(0, overpaid.cardRepayPartCents)
    }

    // ── V5 §63 Case 5：利息/手续费是真实成本，未来利息不算 ──

    @Test
    fun `accrued interest on overdue installments counts as debt, future interest does not`() {
        val m = metrics(
            plans = listOf(
                plan(remaining = 1_000_000, installments = listOf(
                    installment(LocalDate.of(2026, 8, 1), principal = 100_000, interest = 10_000),  // 已到期未还
                    installment(LocalDate.of(2026, 9, 1), principal = 100_000, interest = 8_000)    // 未来
                ))
            )
        )
        assertEquals(1_010_000, m.totalDebtCents)          // 剩余本金 + 已发生未付利息
        assertEquals(10_000, m.accruedInterestCents)
    }

    // ── V5 §63 Case 8：信用卡分期不重复计债、本期待还≠总负债 ──

    @Test
    fun `card installments never add to total debt, statement due is a subset`() {
        val m = metrics(
            accounts = listOf(card(balance = 1_700_000, statementOriginalDue = 600_000)),
            cardInstallments = listOf(V5CardInstallmentInput("cgb", 900_000, 75_000, 12))
        )
        assertEquals(1_700_000, m.totalDebtCents)          // 只用卡余额
        assertEquals(900_000, m.cardInstallmentRemainingCents)
        assertEquals(600_000, m.mustRepayCents)            // 账单已含分期当期，天然不重复
    }

    // ── V5 §63 Case 9/7：初始化不污染当月、净降债主口径 ──

    @Test
    fun `no anchor means no net reduction and trend unknown`() {
        val m = metrics(accounts = listOf(card()))
        assertEquals(0, m.netDebtReductionCents)
        assertEquals(DebtTrend.NO_ANCHOR, m.trend)
        assertNull(m.anchorTotalDebtCents)
    }

    @Test
    fun `net debt reduction equals anchor minus current total debt, negative when growing`() {
        val reduced = metrics(accounts = listOf(card(balance = 965_000)), anchor = 980_000)
        assertEquals(15_000, reduced.netDebtReductionCents)
        assertEquals(DebtTrend.REDUCING, reduced.trend)

        val grew = metrics(accounts = listOf(card(balance = 1_100_000)), anchor = 980_000)
        assertEquals(-120_000, grew.netDebtReductionCents)
        assertEquals(DebtTrend.GROWING, grew.trend)
    }

    // ── V5 §63 Case 10/11：年终奖未到账不是现金 ──

    @Test
    fun `expected windfall does not count as income, received does not double count`() {
        val wf = V5WindfallInput(5_000_000, LocalDate.of(2026, 12, 31).toEpochDay(),
            plannedDebtPaymentCents = 4_500_000, status = WindfallStatus.EXPECTED)
        val m = metrics(
            accounts = listOf(card(balance = 980_000, statementOriginalDue =0)),
            windfalls = listOf(wf),
            month = V5MonthFlow(incomeActualCents = 700_000, feeMonthCents = 0, newBorrowingCents = 0)
        )
        assertEquals(700_000, m.incomeActualCents)                    // 年终奖没进收入
        assertEquals(350_000, m.monthlySurvivalGapCents)              // 7000-3500-0
        assertEquals(140, m.runwayToWindfallDays)                     // 8-13 → 12-31
        assertEquals(980_000, m.projectedDebtAtWindfallCents)         // 无还款计划→无月度变化
        assertEquals(0, m.projectedDebtAfterWindfallCents)            // 9800 < 45000 计划还款 → 压到 0

        val received = metrics(
            accounts = listOf(card(balance = 980_000, statementOriginalDue =0)),
            windfalls = listOf(wf.copy(status = WindfallStatus.RECEIVED))
        )
        assertNull(received.runwayToWindfallDays)                     // 只有 EXPECTED 才有 runway
    }

    // ── V5 §63 Case 6/13：缺口为负时自由消费=0、阶段=现金流生存 ──

    @Test
    fun `negative gap forces zero free spending and cash survival stage`() {
        val m = metrics(
            accounts = listOf(card(statementOriginalDue =650_000)),
            month = V5MonthFlow(incomeActualCents = 700_000, feeMonthCents = 0, newBorrowingCents = 0)
        )
        assertEquals(-300_000, m.monthlySurvivalGapCents)
        assertEquals(0, m.freeSpendingCents)
        assertEquals(0, m.dailySafeSpendCents)
        assertEquals(DebtStage.CASH_SURVIVAL, m.stage)
    }

    @Test
    fun `daily safe spend divides remaining free spending by days left, last day does not divide by zero`() {
        val lastDay = LocalDate.of(2026, 8, 31)
        val m = computeV5Metrics(
            todayEpochDay = lastDay.toEpochDay(),
            currentYearMonth = "2026-08",
            accounts = emptyList(), plans = emptyList(), cardInstallments = emptyList(),
            windfalls = emptyList(), cardTransfers = emptyList(),
            anchorTotalDebtCents = null, recentMonthlyBorrowAvgCents = 0,
            settings = V5SettingsInput(700_000, 350_000),
            month = V5MonthFlow(incomeActualCents = 660_000, feeMonthCents = 0, newBorrowingCents = 0)
        )
        assertEquals(310_000, m.freeSpendingCents)
        assertEquals(310_000, m.dailySafeSpendCents)      // 剩余 1 天
    }

    // ── V5 §63 Case 14：稳定覆盖 ──

    @Test
    fun `stable debt coverage uses configured stable income`() {
        val m = metrics(
            accounts = listOf(card(statementOriginalDue =300_000)),
            month = V5MonthFlow(incomeActualCents = 700_000, feeMonthCents = 0, newBorrowingCents = 0)
        )
        assertEquals(50_000, m.stableDebtCoverageCents)   // 7000-3500-3000
    }

    // ── 清债模拟 ──

    @Test
    fun `payoff simulation converges within 24 months and returns null when it cannot`() {
        val converging = simulatePayoff(1_000_000, 200_000, 0, 0.0)
        assertEquals(5, converging)

        assertNull(simulatePayoff(1_000_000, 0, 100_000, 0.0))   // 只借不还，永不收敛
        assertNull(simulatePayoff(1_000_000, 1_000, 0, 0.0))     // 24 月还 24000 < 本金

        val withInterest = simulatePayoff(1_200_000, 200_000, 0, 0.12)
        assertTrue(withInterest != null && withInterest!! > 6)   // 利息拖慢但不发散
    }

    @Test
    fun `payoff months is zero when debt already cleared`() {
        assertEquals(0, simulatePayoff(0, 200_000, 0, 0.0))
        assertEquals(0, simulatePayoff(-1, 200_000, 0, 0.0))
    }

    // ── 回归：重复计债防线 ──

    @Test
    fun `loan account with a plan is counted exactly once`() {
        val doubleCounted = metrics(
            accounts = listOf(V5AccountInput("huabei", "LOAN", 1_000_000, 0, 0, null, null)),
            plans = listOf(plan(accountId = "huabei", remaining = 1_000_000))
        )
        assertEquals(1_000_000, doubleCounted.totalDebtCents)    // 不是 2_000_000
        assertEquals(0, doubleCounted.loanAccountDebtCents)
        assertEquals(1_000_000, doubleCounted.loanPlanDebtCents)

        val accountOnly = metrics(
            accounts = listOf(V5AccountInput("x", "LOAN", 500_000, 0, 0, null, null))
        )
        assertEquals(500_000, accountOnly.totalDebtCents)        // 无计划覆盖的 LOAN 账户计入
        assertEquals(500_000, accountOnly.loanAccountDebtCents)
    }

    // ── 回归：结清贷款不能让负债复活 ──
    // settleLoanPlan() 只清零 remainingPrincipalCents + 置 PAID_OFF，不会同步 earlyRepaidCents。
    // 若 fallback 分支不认 status，没提前还过款的贷款（earlyRepaid=0，最常见）结清后会回退成原始本金。
    @Test
    fun `paid off loan plan contributes zero debt even though fallback would imply full principal`() {
        val m = metrics(
            plans = listOf(plan(remaining = 0, fallback = 1_000_000, status = "PAID_OFF"))
        )
        assertEquals(0, m.loanPlanDebtCents)
        assertEquals(0, m.totalDebtCents)
    }

    // ── 6 阶段全分支 ──

    @Test
    fun `stage ladder covers all six stages deterministically`() {
        val settings = V5SettingsInput(700_000, 350_000)
        val income = V5MonthFlow(700_000, 0, 0)

        // 1 清债
        assertEquals(DebtStage.DEBT_FREE,
            metrics(accounts = listOf(card(balance = 0)), month = income).stage)

        // 2 现金流生存（gap<0 优先于一切）
        assertEquals(DebtStage.CASH_SURVIVAL,
            metrics(accounts = listOf(card(statementOriginalDue =650_000)), month = income).stage)

        // 3 活到年终奖（缺口>=0 且有 EXPECTED）
        val wfExpected = V5WindfallInput(5_000_000, LocalDate.of(2026, 12, 31).toEpochDay(), 0, WindfallStatus.EXPECTED)
        assertEquals(DebtStage.LIVE_TO_BONUS,
            metrics(accounts = listOf(card(statementOriginalDue =100_000)), windfalls = listOf(wfExpected), month = income).stage)

        // 4 年终奖集中降债（RECEIVED）
        assertEquals(DebtStage.BONUS_PAYDOWN,
            metrics(accounts = listOf(card(statementOriginalDue =100_000)),
                windfalls = listOf(wfExpected.copy(status = WindfallStatus.RECEIVED)), month = income).stage)

        // 5 停止以贷养贷（有新增借款）
        assertEquals(DebtStage.STOP_ROLLOVER,
            metrics(accounts = listOf(card(statementOriginalDue =100_000)),
                month = income.copy(newBorrowingCents = 50_000)).stage)

        // 6 稳定净降债（无借款且趋势 REDUCING）
        assertEquals(DebtStage.STABLE_REDUCTION,
            metrics(accounts = listOf(card(statementOriginalDue =100_000)), anchor = 5_000_000, month = income).stage)
        assertTrue(metrics(accounts = listOf(card(statementOriginalDue =100_000)), anchor = 5_000_000, month = income).needsAttention == false)
    }

    // ── 审核修正1：自由消费闭环（扣已发生非必要消费）──

    @Test
    fun `already-spent optional expenses reduce remaining free spending`() {
        val before = metrics(
            month = V5MonthFlow(incomeActualCents = 700_000, feeMonthCents = 0, newBorrowingCents = 0, optionalSpentCents = 0)
        )
        assertEquals(350_000, before.freeSpendingCents)

        val after = metrics(
            month = V5MonthFlow(incomeActualCents = 700_000, feeMonthCents = 0, newBorrowingCents = 0, optionalSpentCents = 300_000)
        )
        assertEquals(50_000, after.freeSpendingCents)     // 500 元余额 − 已花 300 = 剩 200
        assertEquals(300_000, after.optionalSpentCents)
    }

    @Test
    fun `optional spending worsens an existing gap`() {
        val m = metrics(
            accounts = listOf(card(statementOriginalDue = 650_000)),
            month = V5MonthFlow(incomeActualCents = 700_000, feeMonthCents = 0, newBorrowingCents = 0, optionalSpentCents = 8_000)
        )
        assertEquals(-308_000, m.monthlySurvivalGapCents) // -3000 缺口 + 80 非必要消费 → -3080
        assertEquals(0, m.freeSpendingCents)
    }

    // ── 审核修正（v3）：未来 7/30 天与 mustRepay 共用剩余应还口径 ──

    @Test
    fun `future due windows use remaining statement due, not original bill`() {
        // 原始账单 6000，已还 5000 → 剩余 1000；7 天内到期必须只显示 1000
        val m = metrics(
            accounts = listOf(card(statementOriginalDue = 600_000, dueDay = 20)),  // 8-20 在 7 天窗口内
            cardTransfers = listOf(V5CardTransferInput("cgb", 500_000))
        )
        assertEquals(100_000, m.cardRepayPartCents)          // 本月必须还 ✅
        assertEquals(100_000, m.due7DaysCents)               // 未来7天 ✅ 不是 6000
        assertEquals(100_000, m.due30DaysCents)
        assertEquals(100_000, m.cardRemainingDueByCard["cgb"]) // 卡详情同口径
    }

    @Test
    fun `card remaining due never goes negative when overpaid`() {
        val m = metrics(
            accounts = listOf(card(statementOriginalDue = 600_000, dueDay = 20)),
            cardTransfers = listOf(V5CardTransferInput("cgb", 900_000))
        )
        assertEquals(0, m.due7DaysCents)
        assertEquals(0, m.cardRemainingDueByCard["cgb"])
    }

    // ── 未来 7/30 天应还窗口 ──

    @Test
    fun `due windows include loan installments and card due days in range`() {
        val m = metrics(
            accounts = listOf(
                card(id = "cgb", statementOriginalDue =600_000, dueDay = 20),      // 8-20 在 7 天内
                card(id = "cmb", statementOriginalDue =400_000, dueDay = 10)       // 已过 → 9-10，在 30 天内
            ),
            plans = listOf(plan(installments = listOf(
                installment(LocalDate.of(2026, 8, 20), principal = 100_000, interest = 0),   // 7 天内
                installment(LocalDate.of(2026, 9, 5), principal = 100_000, interest = 0)     // 30 天内
            )))
        )
        assertEquals(700_000, m.due7DaysCents)     // 6000 卡 + 1000 分期
        assertEquals(1_200_000, m.due30DaysCents)  // 上面 + 4000 卡(9-10) + 1000 分期(9-5)
    }
}
