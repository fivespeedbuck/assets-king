package com.assetsking.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, TransactionEntity::class, TransferEntity::class, RawNotificationEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AssetsKingDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transferDao(): TransferDao
    abstract fun rawNotificationDao(): RawNotificationDao

    companion object {
        @Volatile private var instance: AssetsKingDatabase? = null

        fun get(context: Context): AssetsKingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AssetsKingDatabase::class.java,
                "assets-king.db"
            ).build().also { instance = it }
        }
    }
}
