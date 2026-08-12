package com.assetsking.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.usecase.AddAccountUseCase
import com.assetsking.usecase.GetOverviewUseCase
import com.assetsking.usecase.Overview
import com.assetsking.usecase.RecordTransactionUseCase
import com.assetsking.usecase.RecordTransferUseCase
import com.assetsking.usecase.SeedAccountsUseCase
import com.assetsking.usecase.UpdateCategoryUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

enum class RecordMode(val label: String) {
    EXPENSE("支出"), INCOME("收入"), TRANSFER("转账/还款"), REFUND("退款")
}

data class LedgerUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val unprocessedNotifications: Int = 0,
    val overview: Overview = Overview(0, 0, 0)
)

class LedgerViewModel(
    private val seedAccounts: SeedAccountsUseCase,
    private val recordTransaction: RecordTransactionUseCase,
    private val recordTransfer: RecordTransferUseCase,
    private val addAccount: AddAccountUseCase,
    private val updateCategory: UpdateCategoryUseCase,
    getOverview: GetOverviewUseCase,
    private val repository: com.assetsking.database.LedgerRepository
) : ViewModel() {
    val state = combine(
        repository.accounts,
        repository.transactions,
        repository.unprocessedNotifications
    ) { accounts, transactions, count ->
        LedgerUiState(
            accounts = accounts,
            transactions = transactions,
            unprocessedNotifications = count,
            overview = getOverview(accounts)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    init { viewModelScope.launch { seedAccounts() } }

    fun categorize(merchant: String?, note: String?): TransactionCategory =
        repository.categorize(merchant, note)

    fun addAccount(name: String, type: AccountType, openingBalance: String, cardTail: String?) {
        val cents = openingBalance.toCentsOrNull(allowZero = true) ?: return
        viewModelScope.launch { addAccount(name, type, cents, cardTail) }
    }

    fun updateTransactionCategory(id: String, category: TransactionCategory) {
        viewModelScope.launch { updateCategory(id, category) }
    }

    fun addTransaction(
        accountId: String, amount: String, mode: RecordMode,
        category: TransactionCategory, merchant: String?, note: String?
    ) {
        val cents = amount.toCentsOrNull() ?: return
        val type = when (mode) {
            RecordMode.EXPENSE -> TransactionType.EXPENSE
            RecordMode.INCOME -> TransactionType.INCOME
            RecordMode.REFUND -> TransactionType.REFUND
            RecordMode.TRANSFER -> return
        }
        viewModelScope.launch { recordTransaction(accountId, cents, type, category, merchant, note) }
    }

    fun addTransfer(fromAccountId: String, toAccountId: String, amount: String, note: String?) {
        val cents = amount.toCentsOrNull() ?: return
        viewModelScope.launch { recordTransfer(fromAccountId, toAccountId, cents, note) }
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
                        repository = app.repository
                    ) as T
            }
    }
}

private fun String.toCentsOrNull(allowZero: Boolean = false): Long? = runCatching {
    BigDecimal(trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}.getOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }
