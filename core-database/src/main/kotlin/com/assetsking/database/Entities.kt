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
    val creditLimitCents: Long = 0      // 信用额度，0=无
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
    val recurringRuleId: String? = null  // 关联的周期账单ID
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
    val repaymentDay: Int? = null   // 每月还款日（几号）
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

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val targetCents: Long,
    val deadlineEpochDay: Long,  // days since epoch
    val label: String = "净资产目标",
    val createdAt: Long = System.currentTimeMillis()
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
