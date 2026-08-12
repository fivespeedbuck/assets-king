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
        RecurringRuleEntity::class, SnapshotEntity::class, GoalEntity::class,
        CustomCategoryEntity::class
    ],
    version = 9,
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
    abstract fun goalDao(): GoalDao
    abstract fun customCategoryDao(): CustomCategoryDao

    companion object {
        @Volatile private var instance: AssetsKingDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS budgets (
                    id TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL,
                    monthlyLimitCents INTEGER NOT NULL, month TEXT NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS loan_plans (
                    id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL,
                    principalCents INTEGER NOT NULL, startDateEpochDay INTEGER NOT NULL,
                    repaymentMethod TEXT NOT NULL, installmentsJson TEXT NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_loan_plans_accountId ON loan_plans(accountId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN isReimbursable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS recurring_rules (
                    id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL,
                    amountCents INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL,
                    merchant TEXT, note TEXT, interval TEXT NOT NULL,
                    nextRunAt INTEGER NOT NULL, isActive INTEGER NOT NULL DEFAULT 1)""")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN groupName TEXT")
                db.execSQL("ALTER TABLE raw_notifications ADD COLUMN processingNote TEXT")
                db.execSQL("""CREATE TABLE IF NOT EXISTS snapshots (
                    id TEXT NOT NULL PRIMARY KEY, dateEpochDay INTEGER NOT NULL,
                    totalAssets INTEGER NOT NULL, totalDebts INTEGER NOT NULL, netWorth INTEGER NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_snapshots_dateEpochDay ON snapshots(dateEpochDay)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS goals (
                    id TEXT NOT NULL PRIMARY KEY, targetCents INTEGER NOT NULL,
                    deadlineEpochDay INTEGER NOT NULL, label TEXT NOT NULL, createdAt INTEGER NOT NULL)""")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recurring_rules ADD COLUMN isSubscription INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loan_plans ADD COLUMN annualRateBps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loan_plans ADD COLUMN remainingPrincipalCents INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN statementDay INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN dueDay INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN creditLimitCents INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurringRuleId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS custom_categories (
                    name TEXT NOT NULL PRIMARY KEY)""")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE loan_plans ADD COLUMN earlyRepaidCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE loan_plans ADD COLUMN repaymentDay INTEGER DEFAULT NULL")
            }
        }

        fun get(context: Context): AssetsKingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AssetsKingDatabase::class.java,
                "assets-king.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { instance = it }
        }
    }
}
