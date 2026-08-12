package com.assetsking.usecase

import com.assetsking.database.AccountEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.model.AccountType

class SeedAccountsUseCase(private val repository: LedgerRepository) {
    suspend operator fun invoke() {
        repository.seedKnownAccounts()
    }
}
