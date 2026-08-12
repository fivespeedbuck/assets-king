package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.model.TransactionCategory

class UpdateCategoryUseCase(private val repository: LedgerRepository) {
    suspend operator fun invoke(transactionId: String, category: TransactionCategory) {
        repository.updateTransactionCategory(transactionId, category)
    }
}
