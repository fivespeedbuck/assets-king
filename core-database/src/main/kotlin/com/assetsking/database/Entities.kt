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
    val pendingCents: Long = 0,         // V5 银行 pending 消费：单独展示，不计正式总负债
    val archived: Boolean = false,      // 归档（REQ 账户对账 §14-15）：不计入总资产/总欠款，历史可查
    val startDateEpochDay: Long? = null // 启用日期（REQ 账户对账§17）：新增账户当日
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
    val loanPlanId: String? = null,      // LOAN_* 关联的贷款计划，删除回滚用
    val refundOfId: String? = null,      // REFUND: 关联的原消费流水（REQ 待确认交易类型 §6-8），冲减原分类/必要性
    val reimbursedCents: Long = 0,       // 已报销覆盖金额（REQ 报销 §3-4）：到账前仍计入支出，到账后从分类/预算冲减
    val necessity: Boolean? = null,      // 本笔最终必要性（REQ 分类§2）：true=必要 false=非必要 null=按场景默认
    val channel: String? = null,         // 支付渠道（REQ 流水§5）：微信/支付宝/银行短信…与资金账户分开
    val notificationId: String? = null   // 由通知确认生成的流水：删除时原通知回待确认箱（REQ 流水§9）
)

// 一级/二级分类（REQ 初始分类库）：稳定 ID + 显示名可改 + 归档不物理删除
@Entity(tableName = "categories", indices = [Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,                    // 全名
    val shortName: String,               // 两字简称（宫格）
    val parentId: String?,               // null = 一级
    val iconKey: String,                 // 线性图标库 key
    val defaultNecessary: Boolean? = null, // 二级默认必要性：true/false/null=按场景
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val isCustom: Boolean = false,       // 用户新增（预置分类改名也不改 ID）
    val kind: String = "EXPENSE"         // 分类库种类（REQ 预期收入§4）：EXPENSE=消费分类，INCOME=独立小型收入分类库
)

// 交易对象库（REQ 商户库§4-8）：标准商户 + 原名别名 + 学习规则；消费商户与收入来源共用
@Entity(tableName = "merchants")
data class MerchantEntity(
    @PrimaryKey val id: String,          // 标准名
    val aliasesJson: String = "[]",      // 原始名称别名（合并对象时保留）
    val learnedType: String? = null,     // 学习规则：交易类型
    val learnedAccountId: String? = null,// 学习规则：账户
    val learnedCategory: String? = null  // 学习规则：二级分类名
)

// 报销到账 ↔ 垫付消费的关联（REQ 报销 §3）：一笔报销款可覆盖多笔垫付，可部分覆盖
@Entity(tableName = "reimbursement_links", primaryKeys = ["reimbursementTxId", "expenseTxId"])
data class ReimbursementLinkEntity(
    val reimbursementTxId: String,
    val expenseTxId: String,
    val coveredCents: Long
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
    val processingNote: String? = null,  // 失败原因："no amount", "duplicate", "user rejected"
    val contentFingerprint: String = ""  // 规范化内容指纹（REQ 通知监听 §12）：补扫/重推以不同 id 重生时判重用
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

// 余额调整：无法解释的余额差额的可追溯修正记录（REQ 账户对账 §7/§9）。不计收入/支出/预算/消费统计。
@Entity(tableName = "balance_adjustments", indices = [Index("accountId")])
data class BalanceAdjustmentEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val beforeCents: Long,
    val afterCents: Long,
    val diffCents: Long,      // after - before
    val reason: String,
    val occurredAt: Long
)
