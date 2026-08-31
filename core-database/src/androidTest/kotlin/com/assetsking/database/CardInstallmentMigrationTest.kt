package com.assetsking.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardInstallmentMigrationTest {
    private lateinit var context: Context
    private val databaseName = "card-installment-migration.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun version22LegacyPreviewMigratesWithoutGuessingExpenseLinksOrChangingAmounts() {
        openWith(
            version = 22,
            onCreate = { db ->
                db.execSQL("CREATE TABLE `credit_card_installments` (`id` TEXT NOT NULL, `cardAccountId` TEXT NOT NULL, `label` TEXT NOT NULL, `originalPrincipalCents` INTEGER NOT NULL, `remainingPrincipalCents` INTEGER NOT NULL, `monthlyPaymentCents` INTEGER NOT NULL, `feeCentsPerPeriod` INTEGER NOT NULL, `periodsRemaining` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX `index_credit_card_installments_cardAccountId` ON `credit_card_installments` (`cardAccountId`)")
                db.execSQL("INSERT INTO credit_card_installments VALUES ('legacy', 'card', '旧分期', 120000, 90000, 10500, 500, 9, 20000)")
            }
        ).close()

        val migrated = openWith(
            version = 23,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(22, oldVersion)
                assertEquals(23, newVersion)
                AssetsKingDatabase.MIGRATION_22_23.migrate(db)
            }
        )
        val db = migrated.writableDatabase

        db.query("SELECT originalPrincipalCents, remainingPrincipalCents, monthlyPaymentCents, periodsRemaining, installmentCount, status FROM credit_card_installments WHERE id = 'legacy'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(120_000L, cursor.getLong(0))
            assertEquals(90_000L, cursor.getLong(1))
            assertEquals(10_500L, cursor.getLong(2))
            assertEquals(9, cursor.getInt(3))
            assertEquals(9, cursor.getInt(4))
            assertEquals("LEGACY_UNLINKED", cursor.getString(5))
        }
        db.query("SELECT COUNT(*) FROM credit_card_installment_allocations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("PRAGMA table_info(credit_card_installment_schedules)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("expectedUnclassifiedChargeCents" in columns)
        }
        db.query("SELECT COUNT(*) FROM credit_card_installment_payment_matches").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("PRAGMA table_info(credit_card_installment_payment_matches)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(setOf("transferId", "scheduleId", "planId", "paymentCents", "principalCents", "status").all { it in columns })
        }
        migrated.close()
    }

    @Test
    fun version23PlansGainNullableStatementCycleWithoutChangingExistingPlans() {
        openWith(
            version = 23,
            onCreate = { db ->
                db.execSQL("CREATE TABLE `credit_card_installments` (`id` TEXT NOT NULL, `cardAccountId` TEXT NOT NULL, `label` TEXT NOT NULL, `originalPrincipalCents` INTEGER NOT NULL, `remainingPrincipalCents` INTEGER NOT NULL, `monthlyPaymentCents` INTEGER NOT NULL, `feeCentsPerPeriod` INTEGER NOT NULL, `periodsRemaining` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `installmentType` TEXT NOT NULL, `installmentCount` INTEGER NOT NULL, `nextDueDateEpochDay` INTEGER, `status` TEXT NOT NULL, `scheduleRevision` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO credit_card_installments VALUES ('existing', 'card', '既有分期', 120000, 90000, 10500, 500, 9, 20000, 'POST_PURCHASE_INSTALLMENT', 12, 20100, 'ACTIVE', 1, 1000, 1000)")
            }
        ).close()

        val migrated = openWith(
            version = 24,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(23, oldVersion)
                assertEquals(24, newVersion)
                AssetsKingDatabase.MIGRATION_23_24.migrate(db)
            }
        )

        migrated.writableDatabase.query(
            "SELECT originalPrincipalCents, remainingPrincipalCents, statementCycleStartEpochDay FROM credit_card_installments WHERE id = 'existing'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(120_000L, cursor.getLong(0))
            assertEquals(90_000L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
        }
        migrated.close()
    }

    @Test
    fun version24TransactionsGainEmptyTrashStateWithoutChangingExistingRows() {
        openWith(
            version = 24,
            onCreate = { db ->
                db.execSQL("CREATE TABLE `transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `type` TEXT NOT NULL, `category` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `merchant` TEXT, `note` TEXT, `status` TEXT NOT NULL, `isReimbursable` INTEGER NOT NULL, `recurringRuleId` TEXT, `principalCents` INTEGER NOT NULL, `interestCents` INTEGER NOT NULL, `feeCents` INTEGER NOT NULL, `loanPlanId` TEXT, `refundOfId` TEXT, `reimbursedCents` INTEGER NOT NULL, `necessity` INTEGER, `channel` TEXT, `notificationId` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO transactions VALUES ('existing', 'cash', 1000, 'EXPENSE', 'UNCATEGORIZED', 100, NULL, NULL, 'CONFIRMED', 0, NULL, 0, 0, 0, NULL, NULL, 0, NULL, NULL, NULL)")
            }
        ).close()

        val migrated = openWith(
            version = 25,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(24, oldVersion)
                assertEquals(25, newVersion)
                AssetsKingDatabase.MIGRATION_24_25.migrate(db)
            }
        )

        migrated.writableDatabase.query("SELECT amountCents, deletedAt, trashContextJson FROM transactions WHERE id = 'existing'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_000L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        migrated.writableDatabase.query("PRAGMA index_list(transactions)").use { cursor ->
            val names = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("index_transactions_deletedAt" in names)
        }
        migrated.close()
    }

    @Test
    fun version25BackfillsEvidenceWithoutInventingHistoricalCashEvents() {
        openWith(
            version = 25,
            onCreate = { db ->
                db.execSQL("CREATE TABLE `transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `type` TEXT NOT NULL, `category` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `merchant` TEXT, `note` TEXT, `status` TEXT NOT NULL, `isReimbursable` INTEGER NOT NULL, `recurringRuleId` TEXT, `principalCents` INTEGER NOT NULL, `interestCents` INTEGER NOT NULL, `feeCents` INTEGER NOT NULL, `loanPlanId` TEXT, `refundOfId` TEXT, `reimbursedCents` INTEGER NOT NULL, `necessity` INTEGER, `channel` TEXT, `notificationId` TEXT, `deletedAt` INTEGER, `trashContextJson` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO transactions VALUES ('sms-tx', 'cash', 1000, 'EXPENSE', 'UNCATEGORIZED', 100, NULL, NULL, 'CONFIRMED', 0, NULL, 0, 0, 0, NULL, NULL, 0, NULL, NULL, 'sms-1', NULL, NULL)")
                db.execSQL("INSERT INTO transactions VALUES ('manual-tx', 'cash', 2000, 'INCOME', 'UNCATEGORIZED', 200, NULL, NULL, 'CONFIRMED', 0, NULL, 0, 0, 0, NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL)")
                db.execSQL("CREATE TABLE `transfers` (`id` TEXT NOT NULL, `fromAccountId` TEXT NOT NULL, `toAccountId` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `occurredAt` INTEGER NOT NULL, `note` TEXT, `deletedAt` INTEGER, `trashContextJson` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO transfers VALUES ('tf', 'cash', 'saving', 3000, 300, NULL, NULL, NULL)")
                db.execSQL("CREATE TABLE `loan_plans` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `principalCents` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `repaymentMethod` TEXT NOT NULL, `installmentsJson` TEXT NOT NULL, `annualRateBps` INTEGER NOT NULL, `remainingPrincipalCents` INTEGER NOT NULL, `earlyRepaidCents` INTEGER NOT NULL, `repaymentDay` INTEGER, `status` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO loan_plans VALUES ('old-loan', 'loan-account', 5000000, 20000, 'EQUAL_PAYMENT', '[]', 500, 4000000, 0, 20, 'ACTIVE')")
            }
        ).close()

        val migrated = openWith(
            version = 26,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(25, oldVersion)
                assertEquals(26, newVersion)
                AssetsKingDatabase.MIGRATION_25_26.migrate(db)
            }
        )
        val db = migrated.writableDatabase

        db.query("SELECT originType, disbursementTransactionId, ledgerBaselinePrincipalCents, ledgerBaselineAt FROM loan_plans WHERE id = 'old-loan'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("OPENING_BALANCE", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals(4_000_000L, cursor.getLong(2))
            assertTrue(cursor.getLong(3) > 0L)
        }
        db.query("SELECT sourceType, sourceId FROM ledger_evidence_links WHERE subjectType = 'TRANSACTION' AND subjectId = 'sms-tx'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("RAW_NOTIFICATION", cursor.getString(0))
            assertEquals("sms-1", cursor.getString(1))
        }
        db.query("SELECT sourceType FROM ledger_evidence_links WHERE subjectType = 'TRANSFER' AND subjectId = 'tf'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("LEGACY_IMPORT", cursor.getString(0))
        }
        db.query("SELECT sourceType FROM ledger_evidence_links WHERE subjectType = 'LOAN_PLAN' AND subjectId = 'old-loan'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("OPENING_BALANCE", cursor.getString(0))
        }
        db.query("SELECT COUNT(*) FROM ledger_lifecycle_events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
        }
        db.query("PRAGMA table_info(transactions)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("lendingPlanId" in columns)
        }
        db.query("PRAGMA table_info(transfers)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(setOf("lendingPlanId", "lendingRole").all { it in columns })
        }
        db.query("PRAGMA table_info(lending_plans)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(
                setOf(
                    "receivableAccountId",
                    "remainingPrincipalCents",
                    "receivedInterestCents",
                    "originType",
                    "disbursementTransferId",
                    "ledgerBaselinePrincipalCents",
                    "ledgerBaselineInterestCents"
                ).all { it in columns }
            )
        }
        db.query("SELECT COUNT(*) FROM lending_plans").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun version26AddsNullableOrderPlatformWithoutChangingExistingTransactions() {
        openWith(
            version = 26,
            onCreate = { db ->
                db.execSQL("CREATE TABLE transactions (id TEXT NOT NULL PRIMARY KEY, merchant TEXT)")
                db.execSQL("INSERT INTO transactions (id, merchant) VALUES ('existing', '原商户')")
            }
        ).close()

        val migrated = openWith(
            version = 27,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(26, oldVersion)
                assertEquals(27, newVersion)
                AssetsKingDatabase.MIGRATION_26_27.migrate(db)
            }
        )
        migrated.writableDatabase.query("SELECT merchant, orderPlatform FROM transactions WHERE id = 'existing'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("原商户", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.close()
    }

    @Test
    fun version27UpgradesRecurringRulesWithoutLosingExistingData() {
        openWith(
            version = 27,
            onCreate = { db ->
                db.execSQL("CREATE TABLE recurring_rules (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, amountCents INTEGER NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, merchant TEXT, note TEXT, interval TEXT NOT NULL, nextRunAt INTEGER NOT NULL, isActive INTEGER NOT NULL, isSubscription INTEGER NOT NULL)")
                db.execSQL("INSERT INTO recurring_rules VALUES ('rule-1', 'account-1', 3475, 'EXPENSE', '宠物保险', '帕帕保险', '三花', 'MONTHLY', 123456789, 1, 1)")
            }
        ).close()

        val migrated = openWith(
            version = 28,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(27, oldVersion)
                assertEquals(28, newVersion)
                AssetsKingDatabase.MIGRATION_27_28.migrate(db)
            }
        )
        migrated.writableDatabase.query("SELECT accountId, amountCents, category, merchant, note, isActive, channel, orderPlatform, includeInBudget, createdAt, firstRunAt FROM recurring_rules WHERE id = 'rule-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("account-1", cursor.getString(0))
            assertEquals(3_475L, cursor.getLong(1))
            assertEquals("宠物保险", cursor.getString(2))
            assertEquals("帕帕保险", cursor.getString(3))
            assertEquals("三花", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertEquals(1, cursor.getInt(8))
            assertEquals(0L, cursor.getLong(9))
            assertEquals(0L, cursor.getLong(10))
        }
        migrated.writableDatabase.query("PRAGMA table_info(recurring_rules)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue("isSubscription" !in columns)
            assertTrue(setOf("channel", "orderPlatform", "includeInBudget", "createdAt", "firstRunAt").all { it in columns })
        }
        migrated.close()
    }

    private fun openWith(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    onUpgrade(db, oldVersion, newVersion)
            })
            .build()
    ).also { it.writableDatabase }
}
