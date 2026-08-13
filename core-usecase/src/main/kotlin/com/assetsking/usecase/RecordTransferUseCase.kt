package com.assetsking.usecase

import com.assetsking.database.LedgerRepository

class RecordTransferUseCase(private val repository: LedgerRepository) {
    suspend operator fun invoke(
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        note: String?,
        occurredAt: Long
    ) {
        repository.addTransfer(fromAccountId, toAccountId, amountCents, note, occurredAt)
    }
}
