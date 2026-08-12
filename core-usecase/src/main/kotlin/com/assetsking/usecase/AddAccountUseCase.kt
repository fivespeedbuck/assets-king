package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.model.AccountType

class AddAccountUseCase(private val repository: LedgerRepository) {
    suspend operator fun invoke(
        name: String,
        type: AccountType,
        openingBalanceCents: Long,
        cardTail: String?,
        statementDay: Int? = null,
        dueDay: Int? = null,
        creditLimitCents: Long = 0
    ) {
        repository.addAccount(name, type, openingBalanceCents, cardTail, statementDay, dueDay, creditLimitCents)
    }
}
