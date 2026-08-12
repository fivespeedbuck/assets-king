package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.ledger.Ledger
import com.assetsking.model.Money
import com.assetsking.model.RecordStatus
import com.assetsking.model.Transaction
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType

/**
 * 记录一笔流水。组合 Ledger（账务规则）和 LedgerRepository（持久化），
 * 不重复余额计算逻辑。
 */
class RecordTransactionUseCase(private val repository: LedgerRepository) {
    suspend operator fun invoke(
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        categoryStr: String,
        merchant: String?,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        isReimbursable: Boolean = false
    ) {
        // Repository 负责持久化 + 同步余额
        repository.addTransaction(accountId, amountCents, type, category = categoryStr, merchant = merchant, note = note, occurredAt = occurredAt, isReimbursable = isReimbursable)
    }
}
