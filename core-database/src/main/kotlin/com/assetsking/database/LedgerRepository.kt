package com.assetsking.database

import androidx.room.withTransaction
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class LedgerRepository(private val database: AssetsKingDatabase) {
    val accounts: Flow<List<AccountEntity>> = database.accountDao().observeAll()
    val transactions: Flow<List<TransactionEntity>> = database.transactionDao().observeAll()
    val transfers: Flow<List<TransferEntity>> = database.transferDao().observeAll()
    val unprocessedNotifications: Flow<Int> = database.rawNotificationDao().observeUnprocessedCount()
    private val categorizer = RuleBasedCategorizer()

    suspend fun seedKnownAccounts() {
        database.accountDao().insertAll(
            listOf(
                AccountEntity("cmb", "招商银行", AccountType.ASSET.name, 0),
                AccountEntity("nbcb", "宁波银行", AccountType.ASSET.name, 0),
                AccountEntity("cgb", "广发信用卡", AccountType.CREDIT.name, 0),
                AccountEntity("huabei", "花呗", AccountType.LOAN.name, 0)
            )
        )
    }

    fun categorize(merchant: String?, note: String? = null): TransactionCategory =
        categorizer.categorize(merchant, note)

    suspend fun saveRawNotification(notification: RawNotificationEntity) {
        database.rawNotificationDao().insert(notification)
    }

    suspend fun addTransaction(
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String?,
        note: String?
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val account = requireNotNull(database.accountDao().find(accountId))
            val accountType = AccountType.valueOf(account.type)
            val assetDelta = when (type) {
                TransactionType.EXPENSE, TransactionType.FEE -> -amountCents
                TransactionType.INCOME, TransactionType.REFUND -> amountCents
            }
            val actualDelta = if (accountType == AccountType.ASSET) assetDelta else -assetDelta
            database.accountDao().upsert(account.copy(balanceCents = account.balanceCents + actualDelta))
            database.transactionDao().insert(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = accountId,
                    amountCents = amountCents,
                    type = type.name,
                    category = category.name,
                    occurredAt = System.currentTimeMillis(),
                    merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
                    note = note?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
    }

    suspend fun addTransfer(fromAccountId: String, toAccountId: String, amountCents: Long, note: String?) {
        require(amountCents > 0)
        require(fromAccountId != toAccountId)
        database.withTransaction {
            val from = requireNotNull(database.accountDao().find(fromAccountId))
            val to = requireNotNull(database.accountDao().find(toAccountId))
            val fromType = AccountType.valueOf(from.type)
            val toType = AccountType.valueOf(to.type)
            val fromDelta = if (fromType == AccountType.ASSET) -amountCents else amountCents
            val toDelta = if (toType == AccountType.ASSET) amountCents else -amountCents
            database.accountDao().upsert(from.copy(balanceCents = from.balanceCents + fromDelta))
            database.accountDao().upsert(to.copy(balanceCents = to.balanceCents + toDelta))
            database.transferDao().insert(
                TransferEntity(
                    id = UUID.randomUUID().toString(),
                    fromAccountId = fromAccountId,
                    toAccountId = toAccountId,
                    amountCents = amountCents,
                    occurredAt = System.currentTimeMillis(),
                    note = note?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
    }
}
