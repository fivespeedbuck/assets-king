package com.assetsking.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts", indices = [Index(value = ["cardTail"])])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val balanceCents: Long,
    val cardTail: String? = null,
    val balanceStatus: String = "UNCHECKED",
    val lastCheckedAt: Long? = null,
    val groupName: String? = null,      // "资产", "负债"
    val statementDay: Int? = null,      // 账单日 (1-28)
    val dueDay: Int? = null,            // 还款日 (1-31)
    val creditLimitCents: Long = 0,     // 信用额度，0=无
    val statementOriginalDueCents: Long = 0, // V5 本期待还【原始账单金额】：录账单上的数字后勿重录；已还部分由系统按账期扣减（方案A）
    val pendingCents: Long = 0          // V5 银行 pending 消费：单独展示，不计正式总负债
)

@Entity(tableName = "transactions", indices = [Index("accountId"), Index("occurredAt")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val amountCents: Long,
    val type: String,
    val category: String,
    val occurredAt: Long,
    val merchant: String? = null,
    val note: String? = null,
    val status: String = "CONFIRMED",
    val isReimbursable: Boolean = false,
    val recurringRuleId: String? = null, // 关联的周期账单ID
    val principalCents: Long = 0,        // LOAN_PAYMENT: 本金部分
    val interestCents: Long = 0,         // LOAN_PAYMENT: 利息部分
    val feeCents: Long = 0,              // LOAN_PAYMENT: 手续费部分
    val loanPlanId: String? = null       // LOAN_* 关联的贷款计划，删除回滚用
)

@Entity(tableName = "transfers", indices = [Index("fromAccountId"), Index("toAccountId"), Index("occurredAt")])
data class TransferEntity(
    @PrimaryKey val id: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amountCents: Long,
    val occurredAt: Long,
    val note: String? = null
)

@Entity(tableName = "raw_notifications", indices = [Index("packageName"), Index("postedAt"), Index("status")])
data class RawNotificationEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val sourceLabel: String?,
    val title: String?,
    val content: String,
    val postedAt: Long,
    val receivedAt: Long,
    val status: String = "NEW",
    val processingNote: String? = null  // 失败原因："no amount", "duplicate", "user rejected"
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: String,
    val category: String,          // TransactionCategory name, or "ALL" for total
    val monthlyLimitCents: Long,
    val month: String              // "2026-08"
)

@Entity(tableName = "loan_plans", indices = [Index("accountId")])
data class LoanPlanEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val principalCents: Long,
    val startDateEpochDay: Long,
    val repaymentMethod: String,   // RepaymentMethod name
    val installmentsJson: String,  // JSON-serialized list of LoanInstallment
    val annualRateBps: Int = 0,   // 年利率，基点（如 4.5% = 450）
    val remainingPrincipalCents: Long = 0,  // 剩余本金，0=未设置则默认等于本金
    val earlyRepaidCents: Long = 0,  // 已提前还款金额
    val repaymentDay: Int? = null,   // 每月还款日（几号）
    val status: String = "ACTIVE"    // ACTIVE / PAID_OFF（提前结清后取消未来计划）
)

@Entity(tableName = "snapshots", indices = [Index("dateEpochDay")])
data class SnapshotEntity(
    @PrimaryKey val id: String,
    val dateEpochDay: Long,     // days since epoch
    val totalAssets: Long,
    val totalDebts: Long,
    val netWorth: Long
)

@Entity(tableName = "custom_categories")
data class CustomCategoryEntity(
    @PrimaryKey val name: String     // e.g. "宠物", "旅行"
)

// V5 信用卡分期：只做展示与未来预测，绝不进 totalDebt（已在卡 balance 内）
@Entity(tableName = "credit_card_installments", indices = [Index("cardAccountId")])
data class CreditCardInstallmentEntity(
    @PrimaryKey val id: String,
    val cardAccountId: String,
    val label: String,                 // 分期名称，如 "iPhone 24期"
    val originalPrincipalCents: Long,
    val remainingPrincipalCents: Long,
    val monthlyPaymentCents: Long,     // 每期还款（含利息）
    val feeCentsPerPeriod: Long = 0,
    val periodsRemaining: Int,
    val startDateEpochDay: Long
)

// V5 年终奖 Windfall：EXPECTED 不算现金；RECEIVED 才入账
@Entity(tableName = "windfalls", indices = [Index("status")])
data class WindfallEntity(
    @PrimaryKey val id: String,
    val name: String,                  // "2026年终奖"
    val expectedAmountCents: Long,
    val expectedDateEpochDay: Long,
    val plannedDebtPaymentCents: Long = 0, // 计划用于降债的金额（指引，不自动执行）
    val status: String,                // WindfallStatus.name
    val receivedAmountCents: Long = 0, // 到账实际金额
    val receivedAtEpochDay: Long? = null
)

// V5 月度负债锚点：净降债 = 锚点 − 当前总负债
@Entity(tableName = "month_debt_anchors")
data class MonthDebtAnchorEntity(
    @PrimaryKey val yearMonth: String, // "2026-08"
    val totalDebtCents: Long           // 当月首个建档日的 V5 总负债
)

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val amountCents: Long,
    val type: String,              // TransactionType name
    val category: String,          // TransactionCategory name
    val merchant: String?,
    val note: String?,
    val interval: String,          // DAILY, WEEKLY, MONTHLY, YEARLY
    val nextRunAt: Long,           // epoch millis for next auto-create
    val isActive: Boolean = true,
    val isSubscription: Boolean = false  // 订阅制服务标记
)

// 余额检查点：银行短信/手动报告的带时间戳权威余额。账户当前余额 = 最新检查点 + 其后已确认事件增量。
@Entity(tableName = "balance_checkpoints", indices = [Index("accountId")])
data class BalanceCheckpointEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val balanceCents: Long,
    val checkedAt: Long,     // 检查点时刻；occurredAt <= checkedAt 的事件已含在 balanceCents 内
    val source: String       // OPENING / BANK_SMS / MANUAL
)
