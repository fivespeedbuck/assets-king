package com.assetsking.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class, TransactionEntity::class, TransferEntity::class,
        RawNotificationEntity::class, BudgetEntity::class, LoanPlanEntity::class,
        RecurringRuleEntity::class, SnapshotEntity::class,
        CustomCategoryEntity::class,
        CreditCardInstallmentEntity::class, WindfallEntity::class, MonthDebtAnchorEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AssetsKingDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transferDao(): TransferDao
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun budgetDao(): BudgetDao
    abstract fun loanPlanDao(): LoanPlanDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun customCategoryDao(): CustomCategoryDao
    abstract fun creditCardInstallmentDao(): CreditCardInstallmentDao
    abstract fun windfallDao(): WindfallDao
    abstract fun monthDebtAnchorDao(): MonthDebtAnchorDao

    companion object {
        @Volatile private var instance: AssetsKingDatabase? = null

        /**
         * v9→v10 是最后一次允许清库的版本：显式删除全部旧表（v9 数据为测试数据），
         * Room 迁移完成后按新 schema 重建。
         * 从 v11 起必须手写 Migration——没有 destructive fallback，
         * 缺迁移会拒绝启动（抛异常），绝不可能静默清掉真实负债数据。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "goals", "accounts", "transactions", "transfers", "raw_notifications",
                    "budgets", "loan_plans", "recurring_rules", "snapshots", "custom_categories"
                ).forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            }
        }

        fun get(context: Context): AssetsKingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AssetsKingDatabase::class.java,
                "assets-king.db"
            ).addMigrations(MIGRATION_9_10).build().also { instance = it }
        }
    }
}
