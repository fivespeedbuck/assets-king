package com.assetsking.usecase

import com.assetsking.database.AccountEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.model.AccountType

data class Overview(
    val totalAssets: Long,
    val totalDebts: Long,
    val netWorth: Long
)

/**
 * 统一净资产口径：资产总额 - 负债总额。
 * 所有展示净资产的地方都通过这个 UseCase，避免口径不一致。
 */
class GetOverviewUseCase {
    operator fun invoke(accounts: List<AccountEntity>): Overview {
        val assets = accounts
            .filter { it.type == AccountType.ASSET.name }
            .sumOf { it.balanceCents }
        val debts = accounts
            .filter { it.type != AccountType.ASSET.name }
            .sumOf { it.balanceCents }
        return Overview(
            totalAssets = assets,
            totalDebts = debts,
            netWorth = assets - debts
        )
    }
}
