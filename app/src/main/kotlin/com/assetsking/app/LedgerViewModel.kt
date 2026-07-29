package com.assetsking.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assetsking.database.AccountEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
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
    val unprocessedNotifications: Int = 0
)

class LedgerViewModel(private val repository: LedgerRepository) : ViewModel() {
    val state = combine(repository.accounts, repository.transactions, repository.unprocessedNotifications) { accounts, transactions, count ->
        LedgerUiState(accounts, transactions, count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    init { viewModelScope.launch { repository.seedKnownAccounts() } }

    fun categorize(merchant: String?, note: String?): TransactionCategory = repository.categorize(merchant, note)

    fun addTransaction(accountId: String, amount: String, mode: RecordMode, category: TransactionCategory, merchant: String?, note: String?) {
        val cents = amount.toCentsOrNull() ?: return
        val type = when (mode) {
            RecordMode.EXPENSE -> TransactionType.EXPENSE
            RecordMode.INCOME -> TransactionType.INCOME
            RecordMode.REFUND -> TransactionType.REFUND
            RecordMode.TRANSFER -> return
        }
        viewModelScope.launch { repository.addTransaction(accountId, cents, type, category, merchant, note) }
    }

    fun addTransfer(fromAccountId: String, toAccountId: String, amount: String, note: String?) {
        val cents = amount.toCentsOrNull() ?: return
        viewModelScope.launch { repository.addTransfer(fromAccountId, toAccountId, cents, note) }
    }

    companion object {
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(repository) as T
        }
    }
}

private fun String.toCentsOrNull(): Long? = runCatching {
    BigDecimal(trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}.getOrNull()?.takeIf { it > 0 }
