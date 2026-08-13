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

    @Query("UPDATE transactions SET amountCents = :amountCents, type = :type, category = :category, merchant = :merchant, note = :note WHERE id = :id")
    suspend fun update(id: String, amountCents: Long, type: String, category: String, merchant: String?, note: String?)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)

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
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Insert
    suspend fun insert(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun findById(id: String): TransferEntity?

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RawNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: RawNotificationEntity)

    @Query("SELECT COUNT(*) FROM raw_notifications WHERE status = 'NEW'")
    fun observeUnprocessedCount(): Flow<Int>

    @Query("SELECT * FROM raw_notifications WHERE status = :status ORDER BY receivedAt DESC")
    fun observeByStatus(status: String): Flow<List<RawNotificationEntity>>

    @Query("UPDATE raw_notifications SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE raw_notifications SET processingNote = :note WHERE id = :id")
    suspend fun updateProcessingNote(id: String, note: String)
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

    @Query("SELECT * FROM credit_card_installments WHERE id = :id")
    suspend fun findById(id: String): CreditCardInstallmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(installment: CreditCardInstallmentEntity)

    @Query("DELETE FROM credit_card_installments WHERE id = :id")
    suspend fun deleteById(id: String)
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

@Dao
interface CustomCategoryDao {
    @Query("SELECT * FROM custom_categories ORDER BY name")
    fun observeAll(): Flow<List<CustomCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cat: CustomCategoryEntity)

    @Query("DELETE FROM custom_categories WHERE name = :name")
    suspend fun deleteByName(name: String)
}
