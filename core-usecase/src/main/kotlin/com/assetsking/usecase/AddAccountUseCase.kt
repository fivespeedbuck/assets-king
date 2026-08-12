package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.model.AccountType

class AddAccountUseCase(private val repository: LedgerRepository) {
    suspend operator fun invoke(
        name: String,
        type: AccountType,
        openingBalanceCents: Long,
        cardTail: String?
    ) {
        repository.addAccount(name, type, openingBalanceCents, cardTail)
    }
}
