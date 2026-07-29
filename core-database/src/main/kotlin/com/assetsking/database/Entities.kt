package com.assetsking.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts", indices = [Index(value = ["cardTail"])])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val balanceCents: Long,
    val cardTail: String? = null,
    val balanceStatus: String = "UNCHECKED",
    val lastCheckedAt: Long? = null
)

@Entity(tableName = "transactions", indices = [Index("accountId"), Index("occurredAt")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val amountCents: Long,
    val type: String,
    val category: String,
    val occurredAt: Long,
    val merchant: String? = null,
    val note: String? = null,
    val status: String = "CONFIRMED"
)

@Entity(tableName = "transfers", indices = [Index("fromAccountId"), Index("toAccountId"), Index("occurredAt")])
data class TransferEntity(
    @PrimaryKey val id: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amountCents: Long,
    val occurredAt: Long,
    val note: String? = null
)

@Entity(tableName = "raw_notifications", indices = [Index("packageName"), Index("postedAt"), Index("status")])
data class RawNotificationEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val sourceLabel: String?,
    val title: String?,
    val content: String,
    val postedAt: Long,
    val receivedAt: Long,
    val status: String = "NEW"
)
