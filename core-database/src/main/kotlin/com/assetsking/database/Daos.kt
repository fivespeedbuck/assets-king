package com.assetsking.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY type, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun find(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(accounts: List<AccountEntity>)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(transaction: TransactionEntity)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Insert
    suspend fun insert(transfer: TransferEntity)
}

@Dao
interface RawNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: RawNotificationEntity)

    @Query("SELECT COUNT(*) FROM raw_notifications WHERE status = 'NEW'")
    fun observeUnprocessedCount(): Flow<Int>
}
