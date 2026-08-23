package com.assetsking.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY type, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY type, name")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun find(id: String): AccountEntity?

    /** 按卡号尾 4 位找账户 —— 银行通知只给尾号，用来把余额对到正确的卡上 */
    @Query("SELECT * FROM accounts WHERE cardTail = :cardTail AND type = :type")
    suspend fun findByCardTail(cardTail: String, type: String): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: String): TransactionEntity?

    @Query("UPDATE transactions SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: String, category: String)

    @Query("UPDATE transactions SET amountCents = :amountCents, type = :type, category = :category, merchant = :merchant, note = :note, accountId = :accountId, occurredAt = :occurredAt, necessity = :necessity, channel = :channel WHERE id = :id")
    suspend fun update(id: String, amountCents: Long, type: String, category: String, merchant: String?, note: String?, accountId: String, occurredAt: Long, necessity: Boolean?, channel: String?)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countAll(): Int

    @Query("UPDATE transactions SET recurringRuleId = :ruleId WHERE id = :id")
    suspend fun updateRecurringRuleId(id: String, ruleId: String?)

    @Query("SELECT * FROM transactions WHERE recurringRuleId = :ruleId ORDER BY occurredAt DESC")
    suspend fun findByRecurringRule(ruleId: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE occurredAt BETWEEN :start AND :end ORDER BY occurredAt DESC")
    suspend fun findInRange(start: Long, end: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE merchant LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%' ORDER BY occurredAt DESC")
    suspend fun search(query: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("UPDATE transactions SET isReimbursable = :isReimbursable WHERE id = :id")
    suspend fun updateReimbursable(id: String, isReimbursable: Boolean)

    @Query("SELECT * FROM transactions WHERE isReimbursable = 1 ORDER BY occurredAt DESC")
    fun observeReimbursable(): Flow<List<TransactionEntity>>

    @Query("UPDATE transactions SET reimbursedCents = :cents WHERE id = :id")
    suspend fun updateReimbursed(id: String, cents: Long)

    @Query("UPDATE transactions SET necessity = :necessity WHERE id = :id")
    suspend fun updateNecessity(id: String, necessity: Boolean?)

    @Query("UPDATE transactions SET category = :newName WHERE category = :oldName")
    suspend fun updateCategoryName(oldName: String, newName: String)

    @Query("UPDATE transactions SET merchant = :newName WHERE merchant = :oldName")
    suspend fun updateMerchantName(oldName: String, newName: String)

    @Query("SELECT COUNT(*) FROM transactions WHERE category = :category")
    suspend fun countByCategory(category: String): Int
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers ORDER BY occurredAt DESC")
    suspend fun all(): List<TransferEntity>

    @Insert
    suspend fun insert(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun findById(id: String): TransferEntity?

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transfers")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM transfers")
    suspend fun countAll(): Int
}

@Dao
interface BalanceCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: BalanceCheckpointEntity)

    @Query("SELECT * FROM balance_checkpoints WHERE accountId = :accountId ORDER BY checkedAt DESC LIMIT 1")
    suspend fun latestFor(accountId: String): BalanceCheckpointEntity?

    @Query("SELECT * FROM balance_checkpoints WHERE accountId = :accountId ORDER BY checkedAt DESC")
    suspend fun allFor(accountId: String): List<BalanceCheckpointEntity>

    @Query("DELETE FROM balance_checkpoints")
    suspend fun deleteAll()
}

@Dao
interface ReimbursementLinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: ReimbursementLinkEntity)

    @Query("SELECT * FROM reimbursement_links WHERE reimbursementTxId = :txId")
    suspend fun findByReimbursement(txId: String): List<ReimbursementLinkEntity>

    @Query("SELECT * FROM reimbursement_links WHERE expenseTxId = :txId")
    suspend fun findByExpense(txId: String): List<ReimbursementLinkEntity>

    @Query("DELETE FROM reimbursement_links WHERE reimbursementTxId = :txId")
    suspend fun deleteByReimbursement(txId: String)

    @Query("DELETE FROM reimbursement_links")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    suspend fun all(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: String): CategoryEntity?

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MerchantDao {
    @Query("SELECT * FROM merchants ORDER BY id")
    fun observeAll(): Flow<List<MerchantEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(merchant: MerchantEntity)

    @Query("SELECT * FROM merchants WHERE id = :name")
    suspend fun findByName(name: String): MerchantEntity?

    @Query("SELECT * FROM merchants")
    suspend fun all(): List<MerchantEntity>

    @Query("DELETE FROM merchants WHERE id = :name")
    suspend fun deleteByName(name: String)
}

@Dao
interface BalanceAdjustmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(adjustment: BalanceAdjustmentEntity)

    @Query("SELECT * FROM balance_adjustments ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<BalanceAdjustmentEntity>>

    @Query("SELECT * FROM balance_adjustments WHERE accountId = :accountId ORDER BY occurredAt DESC")
    suspend fun allFor(accountId: String): List<BalanceAdjustmentEntity>
}

@Dao
interface RawNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: RawNotificationEntity): Long

    @Query("SELECT COUNT(*) FROM raw_notifications WHERE status = 'NEW'")
    fun observeUnprocessedCount(): Flow<Int>

    @Query("SELECT * FROM raw_notifications WHERE status = :status ORDER BY receivedAt DESC")
    fun observeByStatus(status: String): Flow<List<RawNotificationEntity>>

    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun findById(id: String): RawNotificationEntity?

    @Query("UPDATE raw_notifications SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /**
     * 原子认领一条仍可确认的通知。返回 1 才允许继续入账；重复点击或并发确认返回 0。
     * NEW 只保留给兼容旧数据；正常流程必须先进入 PENDING_CONFIRMATION 再由用户确认。
     */
    @Query("UPDATE raw_notifications SET status = 'LINKING' WHERE id = :id AND status IN ('NEW', 'PENDING_CONFIRMATION')")
    suspend fun claimForConfirmation(id: String): Int

    @Query("UPDATE raw_notifications SET processingNote = :note WHERE id = :id")
    suspend fun updateProcessingNote(id: String, note: String)

    @Query("SELECT COUNT(*) FROM raw_notifications WHERE status = 'PENDING_CONFIRMATION'")
    suspend fun countPendingConfirmation(): Int

    @Query("SELECT COUNT(*) FROM raw_notifications")
    suspend fun countAll(): Int

    @Query("DELETE FROM raw_notifications")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets ORDER BY month DESC, category")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface LoanPlanDao {
    @Query("SELECT * FROM loan_plans ORDER BY startDateEpochDay DESC")
    fun observeAll(): Flow<List<LoanPlanEntity>>

    @Query("SELECT * FROM loan_plans WHERE id = :id")
    suspend fun findById(id: String): LoanPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: LoanPlanEntity)

    @Query("DELETE FROM loan_plans WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rules WHERE isActive = 1 ORDER BY nextRunAt")
    fun observeActive(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY nextRunAt")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RecurringRuleEntity)

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshots ORDER BY dateEpochDay DESC")
    fun observeAll(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots WHERE dateEpochDay = :day LIMIT 1")
    suspend fun findByDay(day: Long): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: SnapshotEntity)
}

@Dao
interface CreditCardInstallmentDao {
    @Query("SELECT * FROM credit_card_installments ORDER BY startDateEpochDay DESC")
    fun observeAll(): Flow<List<CreditCardInstallmentEntity>>

    @Query("SELECT * FROM credit_card_installments ORDER BY startDateEpochDay DESC")
    suspend fun all(): List<CreditCardInstallmentEntity>

    @Query("SELECT * FROM credit_card_installments WHERE id = :id")
    suspend fun findById(id: String): CreditCardInstallmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(installment: CreditCardInstallmentEntity)

    @Query("UPDATE credit_card_installments SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("UPDATE credit_card_installments SET monthlyPaymentCents = :monthlyPaymentCents, feeCentsPerPeriod = :feeCentsPerPeriod, periodsRemaining = :periodsRemaining, nextDueDateEpochDay = :nextDueDateEpochDay, scheduleRevision = :scheduleRevision, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTerms(
        id: String,
        monthlyPaymentCents: Long,
        feeCentsPerPeriod: Long,
        periodsRemaining: Int,
        nextDueDateEpochDay: Long?,
        scheduleRevision: Int,
        updatedAt: Long
    )

    @Query("UPDATE credit_card_installments SET remainingPrincipalCents = :remainingPrincipalCents, periodsRemaining = :periodsRemaining, nextDueDateEpochDay = :nextDueDateEpochDay, status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePaymentProgress(
        id: String,
        remainingPrincipalCents: Long,
        periodsRemaining: Int,
        nextDueDateEpochDay: Long?,
        status: String,
        updatedAt: Long
    )
}

@Dao
interface CreditCardInstallmentAllocationDao {
    @Query("SELECT * FROM credit_card_installment_allocations ORDER BY createdAt")
    fun observeAll(): Flow<List<CreditCardInstallmentAllocationEntity>>

    @Query("SELECT * FROM credit_card_installment_allocations")
    suspend fun all(): List<CreditCardInstallmentAllocationEntity>

    @Query("SELECT * FROM credit_card_installment_allocations WHERE planId = :planId ORDER BY createdAt")
    suspend fun findByPlan(planId: String): List<CreditCardInstallmentAllocationEntity>

    @Query("SELECT COUNT(*) FROM credit_card_installment_allocations WHERE transactionId = :transactionId")
    suspend fun countByTransaction(transactionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(allocations: List<CreditCardInstallmentAllocationEntity>)
}

@Dao
interface CreditCardInstallmentScheduleDao {
    @Query("SELECT * FROM credit_card_installment_schedules ORDER BY dueDateEpochDay, number")
    fun observeAll(): Flow<List<CreditCardInstallmentScheduleEntity>>

    @Query("SELECT * FROM credit_card_installment_schedules WHERE planId = :planId ORDER BY revision, number")
    suspend fun findByPlan(planId: String): List<CreditCardInstallmentScheduleEntity>

    @Query("SELECT * FROM credit_card_installment_schedules WHERE id = :id")
    suspend fun findById(id: String): CreditCardInstallmentScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(schedules: List<CreditCardInstallmentScheduleEntity>)

    @Query("UPDATE credit_card_installment_schedules SET status = 'CANCELLED' WHERE planId = :planId AND status = 'UPCOMING'")
    suspend fun cancelUpcoming(planId: String)

    @Query("UPDATE credit_card_installment_schedules SET principalPaidCents = :principalPaidCents, status = :status WHERE id = :id")
    suspend fun updatePaymentProgress(id: String, principalPaidCents: Long, status: String)
}

@Dao
interface CreditCardInstallmentPaymentMatchDao {
    @Query("SELECT * FROM credit_card_installment_payment_matches ORDER BY createdAt, transferId, scheduleId")
    fun observeAll(): Flow<List<CreditCardInstallmentPaymentMatchEntity>>

    @Query("SELECT * FROM credit_card_installment_payment_matches ORDER BY createdAt, transferId, scheduleId")
    suspend fun all(): List<CreditCardInstallmentPaymentMatchEntity>

    @Query("SELECT * FROM credit_card_installment_payment_matches WHERE transferId = :transferId ORDER BY createdAt, scheduleId")
    suspend fun findByTransfer(transferId: String): List<CreditCardInstallmentPaymentMatchEntity>

    @Query("SELECT * FROM credit_card_installment_payment_matches WHERE scheduleId = :scheduleId ORDER BY createdAt, transferId")
    suspend fun findBySchedule(scheduleId: String): List<CreditCardInstallmentPaymentMatchEntity>

    @Query("SELECT COUNT(*) FROM credit_card_installment_payment_matches WHERE planId = :planId AND status = 'PENDING'")
    suspend fun countPendingByPlan(planId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(matches: List<CreditCardInstallmentPaymentMatchEntity>)

    @Query("UPDATE credit_card_installment_payment_matches SET principalCents = :principalCents, status = :status, source = :source, resolvedAt = :resolvedAt WHERE transferId = :transferId AND scheduleId = :scheduleId")
    suspend fun resolve(
        transferId: String,
        scheduleId: String,
        principalCents: Long,
        status: String,
        source: String,
        resolvedAt: Long
    )

    @Query("UPDATE credit_card_installment_payment_matches SET status = 'REJECTED', resolvedAt = :resolvedAt WHERE transferId = :transferId AND scheduleId != :selectedScheduleId AND status = 'PENDING'")
    suspend fun rejectOtherPending(transferId: String, selectedScheduleId: String, resolvedAt: Long)

    @Query("UPDATE credit_card_installment_payment_matches SET status = 'REVERSED', resolvedAt = :resolvedAt WHERE transferId = :transferId AND status IN ('PENDING', 'AUTO_MATCHED', 'USER_CONFIRMED')")
    suspend fun reverseByTransfer(transferId: String, resolvedAt: Long)
}

@Dao
interface CreditCardInstallmentAuditDao {
    @Query("SELECT * FROM credit_card_installment_audit_events WHERE planId = :planId ORDER BY occurredAt, id")
    fun observeByPlan(planId: String): Flow<List<CreditCardInstallmentAuditEventEntity>>

    @Query("SELECT * FROM credit_card_installment_audit_events WHERE planId = :planId ORDER BY occurredAt, id")
    suspend fun findByPlan(planId: String): List<CreditCardInstallmentAuditEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: CreditCardInstallmentAuditEventEntity)
}

@Dao
interface WindfallDao {
    @Query("SELECT * FROM windfalls ORDER BY expectedDateEpochDay")
    fun observeAll(): Flow<List<WindfallEntity>>

    @Query("SELECT * FROM windfalls WHERE id = :id")
    suspend fun findById(id: String): WindfallEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(windfall: WindfallEntity)

    @Query("DELETE FROM windfalls WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MonthDebtAnchorDao {
    @Query("SELECT * FROM month_debt_anchors ORDER BY yearMonth DESC")
    fun observeAll(): Flow<List<MonthDebtAnchorEntity>>

    @Query("SELECT * FROM month_debt_anchors WHERE yearMonth = :yearMonth")
    suspend fun findByYearMonth(yearMonth: String): MonthDebtAnchorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(anchor: MonthDebtAnchorEntity)
}
