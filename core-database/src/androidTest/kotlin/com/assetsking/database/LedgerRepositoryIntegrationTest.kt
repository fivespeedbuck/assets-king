package com.assetsking.database

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryIntegrationTest {
    private lateinit var database: AssetsKingDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("assets-king.db")
        java.io.File(context.filesDir, "backups").deleteRecursively()
        database = Room.databaseBuilder(context, AssetsKingDatabase::class.java, "assets-king.db")
            .allowMainThreadQueries()
            .build()
        val prefs = context.getSharedPreferences("ledger-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        repository = LedgerRepository(context, database, prefs)
        runBlocking {
            insertAccount("cash")
            insertAccount("savings")
        }
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("assets-king.db")
        java.io.File(context.filesDir, "backups").deleteRecursively()
    }

    @Test
    fun notificationConfirmationIsIdempotent() = runBlocking {
        val notification = pendingNotification("expense-one")
        database.rawNotificationDao().insert(notification)

        repeat(2) {
            repository.confirmNotification(
                notificationId = notification.id,
                accountId = "cash",
                amountCents = 1_250L,
                type = TransactionType.EXPENSE,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = "全家便利店",
                note = null
            )
        }

        assertEquals(1, database.transactionDao().all().size)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)
    }

    @Test
    fun mergedTransferConfirmationIsIdempotent() = runBlocking {
        val outgoing = pendingNotification("transfer-out")
        val incoming = pendingNotification("transfer-in")
        database.rawNotificationDao().insert(outgoing)
        database.rawNotificationDao().insert(incoming)

        repeat(2) {
            repository.confirmTransferFromNotifications(
                outNotificationId = outgoing.id,
                inNotificationId = incoming.id,
                fromAccountId = "cash",
                toAccountId = "savings",
                amountCents = 5_000L,
                note = null
            )
        }

        assertEquals(1, database.transferDao().all().size)
    }

    @Test
    fun overdueRecurringRuleDoesNotCreateAConfirmedTransaction() = runBlocking {
        val dueAt = System.currentTimeMillis() - 60_000L
        database.recurringRuleDao().upsert(recurringRule(dueAt))

        assertEquals(0, repository.processRecurring())
        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(dueAt, database.recurringRuleDao().observeAll().first().single().nextRunAt)
    }

    @Test
    fun recurringRuleAdvancesOnlyAfterClaimingARealTransaction() = runBlocking {
        val dueAt = System.currentTimeMillis() - 60_000L
        val rule = recurringRule(dueAt)
        database.recurringRuleDao().upsert(rule)
        database.transactionDao().insert(
            TransactionEntity(
                id = "real-charge",
                accountId = "cash",
                amountCents = rule.amountCents,
                type = TransactionType.EXPENSE.name,
                category = rule.category,
                merchant = rule.merchant,
                occurredAt = dueAt
            )
        )

        assertEquals(1, repository.processRecurring())
        assertEquals(rule.id, database.transactionDao().findById("real-charge")?.recurringRuleId)
        assertTrue(database.recurringRuleDao().observeAll().first().single().nextRunAt > dueAt)
    }

    @Test
    fun repeatedReimbursementIsCappedAtRemainingEligibleAmount() = runBlocking {
        database.transactionDao().insert(
            TransactionEntity(
                id = "expense",
                accountId = "cash",
                amountCents = 10_000L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = "差旅",
                occurredAt = System.currentTimeMillis(),
                isReimbursable = true,
                reimbursedCents = 7_000L
            )
        )

        repository.addReimbursement(
            accountId = "cash",
            amountCents = 10_000L,
            note = null,
            expenseIds = listOf("expense")
        )

        assertEquals(10_000L, database.transactionDao().findById("expense")?.reimbursedCents)
        val reimbursement = database.transactionDao().all().single { it.type == TransactionType.REIMBURSEMENT.name }
        assertEquals(
            3_000L,
            database.reimbursementLinkDao().findByReimbursement(reimbursement.id).single().coveredCents
        )
    }

    @Test
    fun migrationCreatesANewSnapshotBeforeClearingOldFlows() = runBlocking {
        database.transactionDao().insert(sampleExpense("before-migration"))
        repository.setBackupPin("123456")

        assertTrue(repository.runMigration())

        assertTrue(database.transactionDao().all().isEmpty())
        val snapshots = java.io.File(context.filesDir, "backups/manual")
            .listFiles { file -> file.name.startsWith("manual_") && file.name.endsWith(".db.enc") }
            .orEmpty()
        assertEquals(1, snapshots.size)
        assertTrue(snapshots.single().length() > 0L)
    }

    @Test
    fun failedMigrationBackupKeepsExistingFlowsUntouched() = runBlocking {
        database.transactionDao().insert(sampleExpense("must-survive"))
        repository.setBackupPin("123456")
        // Malformed tree URI fails locally and deterministically. A made-up content
        // authority can make some OEM document providers wait indefinitely.
        repository.setBackupDirUri(Uri.parse("invalid-backup-tree"))

        assertFalse(repository.runMigration())

        assertEquals("must-survive", database.transactionDao().all().single().id)
    }

    @Test
    fun migrationPreservesAccountsLoanPlansAndCardInstallments() = runBlocking {
        val asset = AccountEntity(
            id = "cash",
            name = "工资卡",
            type = AccountType.ASSET.name,
            balanceCents = 123_456L,
            cardTail = "3721",
            balanceStatus = "VERIFIED"
        )
        val loanAccount = AccountEntity(
            id = "loan",
            name = "消费贷",
            type = AccountType.LOAN.name,
            balanceCents = 654_321L
        )
        val creditAccount = AccountEntity(
            id = "card",
            name = "信用卡",
            type = AccountType.CREDIT.name,
            balanceCents = 456_700L,
            cardTail = "3304",
            statementOriginalDueCents = 120_000L
        )
        database.accountDao().upsert(asset)
        database.accountDao().upsert(loanAccount)
        database.accountDao().upsert(creditAccount)

        val loanPlan = LoanPlanEntity(
            id = "plan",
            accountId = loanAccount.id,
            principalCents = 800_000L,
            startDateEpochDay = 20_000L,
            repaymentMethod = "EQUAL_PAYMENT",
            installmentsJson = "[]",
            annualRateBps = 450,
            remainingPrincipalCents = 654_321L,
            repaymentDay = 23
        )
        val cardInstallment = CreditCardInstallmentEntity(
            id = "card-installment",
            cardAccountId = creditAccount.id,
            label = "手机分期",
            originalPrincipalCents = 240_000L,
            remainingPrincipalCents = 180_000L,
            monthlyPaymentCents = 10_000L,
            feeCentsPerPeriod = 200L,
            periodsRemaining = 18,
            startDateEpochDay = 20_000L
        )
        database.loanPlanDao().upsert(loanPlan)
        database.creditCardInstallmentDao().upsert(cardInstallment)
        database.transactionDao().insert(sampleExpense("legacy-flow"))
        repository.setBackupPin("123456")

        assertTrue(repository.runMigration())

        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(asset, database.accountDao().find(asset.id))
        assertEquals(loanAccount, database.accountDao().find(loanAccount.id))
        assertEquals(creditAccount, database.accountDao().find(creditAccount.id))
        assertEquals(loanPlan, database.loanPlanDao().findById(loanPlan.id))
        assertEquals(cardInstallment, database.creditCardInstallmentDao().findById(cardInstallment.id))
        assertEquals(asset.balanceCents, database.balanceCheckpointDao().latestFor(asset.id)?.balanceCents)
        assertEquals(loanAccount.balanceCents, database.balanceCheckpointDao().latestFor(loanAccount.id)?.balanceCents)
        assertEquals(creditAccount.balanceCents, database.balanceCheckpointDao().latestFor(creditAccount.id)?.balanceCents)
    }

    @Test(timeout = 30_000L)
    fun wrongRestorePinCannotReplaceTheCurrentDatabase() = runBlocking {
        database.transactionDao().insert(sampleExpense("current-ledger"))
        repository.setBackupPin("123456")
        assertTrue(repository.backupNow(manual = true))
        val backup = java.io.File(context.filesDir, "backups/manual")
            .listFiles { file -> file.name.endsWith(".db.enc") }
            .orEmpty()
            .single()
        assertFalse(repository.restoreFromPicked(Uri.fromFile(backup), "654321"))
        assertEquals("current-ledger", database.transactionDao().all().single().id)
    }

    @Test
    fun archiveZeroBalanceAccountKeepsAccountAndTransactionHistory() = runBlocking {
        database.transactionDao().insert(sampleExpense("archive-history"))

        repository.archiveAccount("cash")

        assertTrue(database.accountDao().find("cash")?.archived == true)
        assertEquals("archive-history", database.transactionDao().all().single().id)
    }

    @Test
    fun archiveRejectsAccountWithRemainingBalance() = runBlocking {
        val account = requireNotNull(database.accountDao().find("cash"))
        database.accountDao().upsert(account.copy(balanceCents = 1L))

        val failure = runCatching { repository.archiveAccount("cash") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(requireNotNull(database.accountDao().find("cash")).archived)
    }

    private suspend fun insertAccount(id: String) {
        database.accountDao().upsert(
            AccountEntity(id = id, name = id, type = AccountType.ASSET.name, balanceCents = 0L)
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "opening-$id",
                accountId = id,
                balanceCents = 0L,
                checkedAt = Long.MIN_VALUE,
                source = "OPENING"
            )
        )
    }

    private fun pendingNotification(id: String) = RawNotificationEntity(
        id = id,
        packageName = "test",
        sourceLabel = "test",
        title = "测试通知",
        content = "支付12.50元 商户:全家便利店",
        postedAt = System.currentTimeMillis(),
        receivedAt = System.currentTimeMillis(),
        status = "PENDING_CONFIRMATION"
    )

    private fun recurringRule(dueAt: Long) = RecurringRuleEntity(
        id = "monthly-rent",
        accountId = "cash",
        amountCents = 88_000L,
        type = TransactionType.EXPENSE.name,
        category = TransactionCategory.UNCATEGORIZED.name,
        merchant = "房租",
        note = null,
        interval = "MONTHLY",
        nextRunAt = dueAt
    )

    private fun sampleExpense(id: String) = TransactionEntity(
        id = id,
        accountId = "cash",
        amountCents = 1_000L,
        type = TransactionType.EXPENSE.name,
        category = TransactionCategory.UNCATEGORIZED.name,
        merchant = "测试",
        occurredAt = System.currentTimeMillis()
    )
}
