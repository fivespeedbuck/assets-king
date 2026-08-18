package com.assetsking.usecase

import com.assetsking.database.AccountEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.ledger.V5AccountInput
import com.assetsking.ledger.V5CardInstallmentInput
import com.assetsking.ledger.V5CardTransferInput
import com.assetsking.ledger.V5Metrics
import com.assetsking.ledger.V5MonthFlow
import com.assetsking.ledger.V5SettingsInput
import com.assetsking.ledger.V5WindfallInput
import com.assetsking.ledger.computeV5Metrics
import com.assetsking.model.WindfallStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * V5 指标唯一出口：所有 Flow 组合 → 实体映射 DTO → 纯函数 computeV5Metrics。
 * 铁律 1（借款≠收入）/铁律 3（还本金≠消费）由类型过滤天然保证：
 * 收入口径只含 INCOME/REFUND，借款与还款类型不在其中。
 */
class GetV5MetricsUseCase(private val repository: LedgerRepository) {

    operator fun invoke(): Flow<V5Metrics> = combine(
        repository.accounts,
        repository.transactions,
        repository.transfers,
        repository.loanPlans,
        repository.cardInstallments
    ) { accounts, txs, transfers, plans, installments ->
        Base(accounts, txs, transfers, plans, installments)
    }.flatMapLatest { base ->
        combine(
            repository.windfalls,
            repository.monthDebtAnchors,
            repository.monthlyIncomeCents,
            repository.necessaryLivingCents
        ) { windfalls, anchors, income, necessary ->
            Config(windfalls, anchors, income, necessary)
        }.flatMapLatest { cfg ->
            combine(repository.optionalCategories, repository.budgets, dateTick()) { optionalCats, budgets, todayEpochDay ->
                // 必要生活 = 当月分项预算之和（自动）；没设预算才回退手填值
                val ym = YearMonth.from(LocalDate.ofEpochDay(todayEpochDay)).toString()
                val budgetSum = budgets.filter { it.month == ym }.sumOf { it.monthlyLimitCents }
                build(base, cfg.copy(necessary = if (budgetSum > 0) budgetSum else cfg.necessary), optionalCats, todayEpochDay)
            }
        }
    }

    private data class Base(
        val accounts: List<AccountEntity>,
        val transactions: List<TransactionEntity>,
        val transfers: List<TransferEntity>,
        val plans: List<com.assetsking.database.LoanPlanEntity>,
        val cardInstallments: List<com.assetsking.database.CreditCardInstallmentEntity>
    )

    private data class Config(
        val windfalls: List<com.assetsking.database.WindfallEntity>,
        val anchors: List<com.assetsking.database.MonthDebtAnchorEntity>,
        val income: Long,
        val necessary: Long
    )

    private fun build(
        base: Base,
        cfg: Config,
        optionalCategories: Set<String>,
        todayEpochDay: Long
    ): V5Metrics {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val ym = YearMonth.from(today)
        val (monthStart, monthEnd) = monthRange(ym)

        val monthTxs = base.transactions.filter { it.occurredAt in monthStart..monthEnd }
        // 本月实际收入只统计已确认真实收入（REQ 收入§2/§5）：退款/报销到账/转账不进普通收入。
        // 退款冲减原消费分类由退款关联（M1.3）实现，不是把退款当收入。
        val incomeActual = monthTxs
            .filter { it.type == "INCOME" }
            .sumOf { it.amountCents }
        val feeMonth = monthTxs.filter { it.type == "FEE" }.sumOf { it.amountCents }
        val newBorrowing = monthTxs
            .filter { it.type == "LOAN_DISBURSEMENT" }
            .sumOf { it.amountCents }
        // 已发生的非必要消费（按设置勾选的分类）→ 占用自由消费、恶化实际缺口。
        // 已关联退款冲减原消费的必要性额度（REQ 待确认§8）：非必要消费退款 → 释放自由开销。
        val rawOptionalSpent = monthTxs
            .filter { it.type == "EXPENSE" && it.category in optionalCategories }
            .sumOf { it.amountCents }
        val optionalRefundOffset = monthTxs
            .filter { it.type == "REFUND" && it.refundOfId != null }
            .sumOf { refund ->
                monthTxs.firstOrNull {
                    it.id == refund.refundOfId && it.type == "EXPENSE" && it.category in optionalCategories
                }?.let { refund.amountCents } ?: 0L
            }
        // 报销到账也冲减自由开销（REQ 报销 §5）
        val optionalReimbursementOffset = monthTxs
            .filter { it.type == "EXPENSE" && it.category in optionalCategories }
            .sumOf { it.reimbursedCents }
        val optionalSpent = (rawOptionalSpent - optionalRefundOffset - optionalReimbursementOffset).coerceAtLeast(0L)
        // 今日已花（非必要）：对应首页「今日上限」，让用户一眼看到今天还差多少额度
        val todayStart = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayOptionalSpent = monthTxs
            .filter { it.type == "EXPENSE" && it.category in optionalCategories && it.occurredAt >= todayStart }
            .sumOf { it.amountCents }

        // 近 3 个月借款均值（清债模拟用；无借款则 0）
        val borrowAvg = (1L..3L).map { n ->
            val (s, e) = monthRange(ym.minusMonths(n))
            base.transactions
                .filter { it.type == "LOAN_DISBURSEMENT" && it.occurredAt in s..e }
                .sumOf { it.amountCents }
        }.let { if (it.sum() == 0L) 0L else (it.sum().toDouble() / 3.0).toLong() }

        // 方案A：录原始账单金额，已还按【账期窗口】扣减（statementDay 推导，无出账日回退自然月）
        val cardTransfers = base.accounts
            .filter { it.type == "CREDIT" && !it.archived }
            .mapNotNull { card ->
                val (s, e) = statementCycle(card, today)
                val transferred = base.transfers
                    .filter { it.toAccountId == card.id && it.occurredAt in s..e }
                    .sumOf { it.amountCents }
                if (transferred > 0) V5CardTransferInput(card.id, transferred) else null
            }

        val anchor = cfg.anchors.firstOrNull { it.yearMonth == ym.toString() }?.totalDebtCents

        return computeV5Metrics(
            todayEpochDay = todayEpochDay,
            currentYearMonth = ym.toString(),
            accounts = base.accounts.filter { !it.archived }.map {
                V5AccountInput(it.id, it.type, it.balanceCents, it.statementOriginalDueCents, it.pendingCents, it.statementDay, it.dueDay)
            },
            plans = base.plans.map { repository.v5PlanInput(it) },
            cardInstallments = base.cardInstallments.map {
                V5CardInstallmentInput(it.cardAccountId, it.remainingPrincipalCents, it.monthlyPaymentCents, it.periodsRemaining)
            },
            windfalls = cfg.windfalls.map {
                V5WindfallInput(
                    expectedAmountCents = it.expectedAmountCents,
                    expectedDateEpochDay = it.expectedDateEpochDay,
                    plannedDebtPaymentCents = it.plannedDebtPaymentCents,
                    status = runCatching { WindfallStatus.valueOf(it.status) }.getOrDefault(WindfallStatus.EXPECTED)
                )
            },
            cardTransfers = cardTransfers,
            anchorTotalDebtCents = anchor,
            recentMonthlyBorrowAvgCents = borrowAvg,
            settings = V5SettingsInput(monthlyIncomeCents = cfg.income, necessaryLivingCents = cfg.necessary),
            month = V5MonthFlow(
                incomeActualCents = incomeActual,
                feeMonthCents = feeMonth,
                newBorrowingCents = newBorrowing,
                optionalSpentCents = optionalSpent,
                todayOptionalSpentCents = todayOptionalSpent
            )
        )
    }

    /** 信用卡当前账单的还款窗口 [本出账日, 下次出账日)；无出账日回退自然月 */
    private fun statementCycle(card: AccountEntity, today: LocalDate): Pair<Long, Long> {
        val stmtDay = card.statementDay ?: return monthRange(YearMonth.from(today))
        val zone = ZoneId.systemDefault()
        fun at(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val ym = YearMonth.from(today)
        // 当前账单的出账日：本月出账日未到则属于上月的账单
        val thisStmt = ym.atDay(stmtDay.coerceAtMost(ym.lengthOfMonth())).let { d ->
            if (d.isAfter(today)) d.minusMonths(1) else d
        }
        return at(thisStmt) to at(thisStmt.plusMonths(1))
    }

    /** 每分钟发射一次当天日期：跨天后 dailySafeSpend 等自然刷新 */
    private fun dateTick(): Flow<Long> = flow {
        while (true) {
            emit(LocalDate.now().toEpochDay())
            delay(60_000)
        }
    }

    private fun monthRange(ym: YearMonth): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }
}
