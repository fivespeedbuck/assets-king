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
