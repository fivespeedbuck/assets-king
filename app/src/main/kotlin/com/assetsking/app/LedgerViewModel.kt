package com.assetsking.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.CustomCategoryEntity
import com.assetsking.database.GoalEntity
import com.assetsking.database.RawNotificationEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.SnapshotEntity
import com.assetsking.database.TransferEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.usecase.AddAccountUseCase
import com.assetsking.usecase.GetOverviewUseCase
import com.assetsking.usecase.NotificationParser
import com.assetsking.usecase.Overview
import com.assetsking.usecase.ParsedNotification
import com.assetsking.usecase.ProcessPendingUseCase
import com.assetsking.usecase.RecordTransactionUseCase
import com.assetsking.usecase.RecordTransferUseCase
import com.assetsking.usecase.SeedAccountsUseCase
import com.assetsking.usecase.UpdateCategoryUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

enum class RecordMode(val label: String) {
    EXPENSE("支出"), INCOME("收入"), TRANSFER("转账/还款"), REFUND("退款")
}

data class PendingItem(
    val notification: RawNotificationEntity,
    val parsed: ParsedNotification
)

data class LedgerUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val transfers: List<TransferEntity> = emptyList(),
    val unprocessedNotifications: Int = 0,
    val pendingItems: List<PendingItem> = emptyList(),
    val overview: Overview = Overview(0, 0, 0)
)

class LedgerViewModel(
    private val seedAccounts: SeedAccountsUseCase,
    private val recordTransaction: RecordTransactionUseCase,
    private val recordTransfer: RecordTransferUseCase,
    private val addAccount: AddAccountUseCase,
    private val updateCategory: UpdateCategoryUseCase,
    getOverview: GetOverviewUseCase,
    private val processPending: ProcessPendingUseCase,
    private val repository: com.assetsking.database.LedgerRepository
) : ViewModel() {
    val state = combine(
        repository.accounts,
        repository.transactions,
        repository.transfers,
        repository.unprocessedNotifications,
        repository.pendingNotifications
    ) { accounts, transactions, transfers, count, pending ->
        LedgerUiState(
            accounts = accounts,
            transactions = transactions,
            transfers = transfers,
            unprocessedNotifications = count,
            pendingItems = pending.map { entity ->
                PendingItem(entity, NotificationParser.parse(entity.content, entity.title))
            },
            overview = getOverview(accounts)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    val budgets: Flow<List<BudgetEntity>> = repository.budgets
    val loanPlans: Flow<List<LoanPlanEntity>> = repository.loanPlans
    val reimbursable: Flow<List<TransactionEntity>> = repository.reimbursableTransactions
    val recurringRules: Flow<List<RecurringRuleEntity>> = repository.recurringRules
    val snapshots: Flow<List<SnapshotEntity>> = repository.snapshots
    val latestGoal: Flow<GoalEntity?> = repository.latestGoal
    val customCategories: Flow<List<CustomCategoryEntity>> = repository.customCategories

    init {
        viewModelScope.launch {
            seedAccounts()
            repository.processRecurring()
            repository.saveTodaySnapshot()
        }
    }

    fun categorize(merchant: String?, note: String?): TransactionCategory =
        repository.categorize(merchant, note)

    fun addAccount(name: String, type: AccountType, openingBalance: String, cardTail: String?, statementDay: Int? = null, dueDay: Int? = null, creditLimit: Long = 0) {
        val cents = openingBalance.toCentsOrNull(allowZero = true) ?: return
        viewModelScope.launch { addAccount(name, type, cents, cardTail, statementDay, dueDay, creditLimit) }
    }

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch { repository.updateAccount(account) }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch { repository.deleteAccount(accountId) }
    }

    fun updateTransactionCategory(id: String, category: TransactionCategory) {
        viewModelScope.launch { updateCategory(id, category) }
    }

    fun updateTransaction(
        id: String, amountCents: Long, type: TransactionType,
        category: String, merchant: String?, note: String?
    ) {
        viewModelScope.launch {
            repository.updateTransaction(id, amountCents, type, category, merchant, note)
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch { repository.deleteTransaction(id) }
    }

    fun saveBudget(budget: BudgetEntity) {
        viewModelScope.launch { repository.saveBudget(budget) }
    }

    fun deleteBudget(id: String) {
        viewModelScope.launch { repository.deleteBudget(id) }
    }

    fun saveLoanPlan(plan: LoanPlanEntity) {
        viewModelScope.launch { repository.saveLoanPlan(plan) }
    }

    fun deleteLoanPlan(id: String) {
        viewModelScope.launch { repository.deleteLoanPlan(id) }
    }

    fun toggleReimbursable(id: String, isReimbursable: Boolean) {
        viewModelScope.launch { repository.toggleReimbursable(id, isReimbursable) }
    }

    fun linkToRecurringRule(transactionId: String, ruleId: String?) {
        viewModelScope.launch { repository.linkToRecurringRule(transactionId, ruleId) }
    }

    fun addCustomCategory(name: String) {
        viewModelScope.launch { repository.addCustomCategory(name) }
    }

    fun deleteCustomCategory(name: String) {
        viewModelScope.launch { repository.deleteCustomCategory(name) }
    }

    fun saveRecurringRule(rule: RecurringRuleEntity) {
        viewModelScope.launch { repository.saveRecurringRule(rule) }
    }

    fun deleteRecurringRule(id: String) {
        viewModelScope.launch { repository.deleteRecurringRule(id) }
    }

    fun saveGoal(goal: GoalEntity) {
        viewModelScope.launch { repository.saveGoal(goal) }
    }

    fun deleteGoal(id: String) {
        viewModelScope.launch { repository.deleteGoal(id) }
    }

    fun reconcileAccount(account: AccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(
                account.copy(
                    balanceStatus = "CONFIRMED",
                    lastCheckedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun addTransaction(
        accountId: String, amount: String, mode: RecordMode,
        category: String, merchant: String?, note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        isReimbursable: Boolean = false
    ) {
        val cents = amount.toCentsOrNull() ?: return
        val type = when (mode) {
            RecordMode.EXPENSE -> TransactionType.EXPENSE
            RecordMode.INCOME -> TransactionType.INCOME
            RecordMode.REFUND -> TransactionType.REFUND
            RecordMode.TRANSFER -> return
        }
        viewModelScope.launch { recordTransaction(accountId, cents, type, categoryStr = category, merchant = merchant, note = note, occurredAt = occurredAt, isReimbursable = isReimbursable) }
    }

    fun addTransfer(fromAccountId: String, toAccountId: String, amount: String, note: String?) {
        val cents = amount.toCentsOrNull() ?: return
        viewModelScope.launch { recordTransfer(fromAccountId, toAccountId, cents, note) }
    }

    // ── 通知处理 ──

    /** 批量解析 NEW 通知，触发去重和状态流转 */
    fun processNotifications() {
        viewModelScope.launch { processPending.invoke() }
    }

    /** 确认通知 → 创建交易+标记 LINKED + 学习规则 */
    fun confirmNotification(
        notificationId: String,
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?
    ) {
        viewModelScope.launch {
            repository.confirmNotification(notificationId, accountId, amountCents, type, category, merchant, note)
            repository.learnRule(merchant, accountId, type.name, category)
        }
    }

    /** 忽略通知 */
    fun ignoreNotification(notificationId: String) {
        viewModelScope.launch { repository.updateNotificationStatus(notificationId, "IGNORED") }
    }

    companion object {
        fun factory(app: AssetsKingApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LedgerViewModel(
                        seedAccounts = app.seedAccounts,
                        recordTransaction = app.recordTransaction,
                        recordTransfer = app.recordTransfer,
                        addAccount = app.addAccount,
                        updateCategory = app.updateCategory,
                        getOverview = app.getOverview,
                        processPending = app.processPending,
                        repository = app.repository
                    ) as T
            }
    }
}

private fun String.toCentsOrNull(allowZero: Boolean = false): Long? = runCatching {
    BigDecimal(trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}.getOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }
