package com.assetsking.database

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assetsking.ledger.CardInstallmentAllocationRequest
import com.assetsking.ledger.CardInstallmentPaymentMatchKind
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryIntegrationTest {
    private lateinit var database: AssetsKingDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("assets-king.db")
        java.io.File(context.filesDir, "backups").deleteRecursively()
        database = Room.databaseBuilder(context, AssetsKingDatabase::class.java, "assets-king.db")
            .allowMainThreadQueries()
            .build()
        prefs = context.getSharedPreferences("ledger-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
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
    fun loanPaymentNotificationIsAtomicIdempotentAndReversible() = runBlocking {
        val dueDay = 21_000L
        val notification = pendingNotification("loan-payment").copy(postedAt = epochMillis(dueDay))
        database.rawNotificationDao().insert(notification)
        database.accountDao().upsert(
            AccountEntity(id = "loan", name = "测试贷款", type = AccountType.LOAN.name, balanceCents = 600_000L)
        )
        database.loanPlanDao().upsert(
            LoanPlanEntity(
                id = "loan-plan",
                accountId = "loan",
                principalCents = 600_000L,
                startDateEpochDay = dueDay - 31,
                repaymentMethod = "EQUAL_PAYMENT",
                installmentsJson = """[
                    {"number":1,"dueDateEpochDay":21000,"principal":300000,"interest":40000,"fee":2000,"status":"UPCOMING"},
                    {"number":2,"dueDateEpochDay":21031,"principal":300000,"interest":30000,"fee":1000,"status":"UPCOMING"}
                ]""".trimIndent(),
                remainingPrincipalCents = 600_000L
            )
        )

        repeat(2) {
            repository.confirmLoanPaymentNotification(
                notificationId = notification.id,
                cashAccountId = "cash",
                planId = "loan-plan",
                totalCents = 342_000L,
                principalCents = 300_000L,
                interestCents = 40_000L,
                feeCents = 2_000L,
                note = "银行自动扣款"
            )
        }

        val transaction = database.transactionDao().all().single()
        val paidPlan = requireNotNull(database.loanPlanDao().findById("loan-plan"))
        assertEquals(TransactionType.LOAN_PAYMENT.name, transaction.type)
        assertEquals(notification.id, transaction.notificationId)
        assertEquals(300_000L, transaction.principalCents)
        assertEquals(40_000L, transaction.interestCents)
        assertEquals(2_000L, transaction.feeCents)
        assertEquals(300_000L, paidPlan.remainingPrincipalCents)
        assertEquals(listOf(true, false), repository.v5PlanInput(paidPlan).installments.map { it.isPaid })
        assertEquals(-342_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)

        repository.deleteTransaction(transaction.id)

        val restoredPlan = requireNotNull(database.loanPlanDao().findById("loan-plan"))
        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(600_000L, restoredPlan.remainingPrincipalCents)
        assertEquals(listOf(false, false), repository.v5PlanInput(restoredPlan).installments.map { it.isPaid })
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("IGNORED", database.rawNotificationDao().findById(notification.id)?.status)
        assertTrue(
            database.rawNotificationDao().findById(notification.id)?.processingNote
                ?.contains("垃圾箱") == true
        )

        assertEquals(transaction.id, repository.deletedTransactions.first().single().id)

        repository.restoreTransactionFromTrash(transaction.id)

        val reactivatedPlan = requireNotNull(database.loanPlanDao().findById("loan-plan"))
        assertEquals(transaction.id, database.transactionDao().all().single().id)
        assertTrue(repository.deletedTransactions.first().isEmpty())
        assertEquals(300_000L, reactivatedPlan.remainingPrincipalCents)
        assertEquals(listOf(true, false), repository.v5PlanInput(reactivatedPlan).installments.map { it.isPaid })
        assertEquals(-342_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)
    }

    @Test
    fun interestOnlyPaymentAdvancesExactlyOneZeroPrincipalInstallment() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(id = "interest-only-loan", name = "先息后本测试", type = AccountType.LOAN.name, balanceCents = 5_000_000L)
        )
        database.loanPlanDao().upsert(
            LoanPlanEntity(
                id = "interest-only-plan",
                accountId = "interest-only-loan",
                principalCents = 5_000_000L,
                startDateEpochDay = 20_000L,
                repaymentMethod = "INTEREST_ONLY",
                installmentsJson = """[
                    {"number":1,"dueDateEpochDay":20030,"principal":0,"interest":32705,"fee":0,"status":"UPCOMING"},
                    {"number":2,"dueDateEpochDay":20060,"principal":0,"interest":31650,"fee":0,"status":"UPCOMING"},
                    {"number":3,"dueDateEpochDay":20090,"principal":5000000,"interest":32705,"fee":0,"status":"UPCOMING"}
                ]""".trimIndent(),
                remainingPrincipalCents = 5_000_000L
            )
        )

        repository.addLoanPayment(
            cashAccountId = "cash",
            planId = "interest-only-plan",
            totalCents = 32_705L,
            principalCents = 0L,
            interestCents = 32_705L,
            feeCents = 0L,
            note = "首期付息"
        )

        val paidPlan = requireNotNull(database.loanPlanDao().findById("interest-only-plan"))
        assertEquals(listOf(true, false, false), repository.v5PlanInput(paidPlan).installments.map { it.isPaid })
        assertEquals(5_000_000L, paidPlan.remainingPrincipalCents)

        repository.deleteTransaction(database.transactionDao().all().single().id)

        val restored = requireNotNull(database.loanPlanDao().findById("interest-only-plan"))
        assertEquals(listOf(false, false, false), repository.v5PlanInput(restored).installments.map { it.isPaid })
        assertEquals(5_000_000L, restored.remainingPrincipalCents)
    }

    @Test
    fun loanPrepaymentFeeChangesCashFlowButDoesNotReducePrincipalTwice() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(id = "prepay-loan", name = "提前还款测试", type = AccountType.LOAN.name, balanceCents = 300_000L)
        )
        database.loanPlanDao().upsert(
            LoanPlanEntity(
                id = "prepay-plan",
                accountId = "prepay-loan",
                principalCents = 300_000L,
                startDateEpochDay = 20_000L,
                repaymentMethod = "CUSTOM",
                installmentsJson = "[]",
                remainingPrincipalCents = 300_000L
            )
        )

        repository.addLoanPrepayment(
            cashAccountId = "cash",
            planId = "prepay-plan",
            principalCents = 100_000L,
            note = "含提前还款违约金",
            feeCents = 5_000L
        )

        val transaction = database.transactionDao().all().single()
        val prepaidPlan = requireNotNull(database.loanPlanDao().findById("prepay-plan"))
        assertEquals(TransactionType.LOAN_PREPAYMENT.name, transaction.type)
        assertEquals(105_000L, transaction.amountCents)
        assertEquals(100_000L, transaction.principalCents)
        assertEquals(5_000L, transaction.feeCents)
        assertEquals(200_000L, prepaidPlan.remainingPrincipalCents)
        assertEquals(100_000L, prepaidPlan.earlyRepaidCents)
        assertEquals(-105_000L, database.accountDao().find("cash")?.balanceCents)

        repository.deleteTransaction(transaction.id)

        val restoredPlan = requireNotNull(database.loanPlanDao().findById("prepay-plan"))
        assertEquals(300_000L, restoredPlan.remainingPrincipalCents)
        assertEquals(0L, restoredPlan.earlyRepaidCents)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)

        repository.restoreTransactionFromTrash(transaction.id)

        val reappliedPlan = requireNotNull(database.loanPlanDao().findById("prepay-plan"))
        assertEquals(200_000L, reappliedPlan.remainingPrincipalCents)
        assertEquals(100_000L, reappliedPlan.earlyRepaidCents)
        assertEquals(-105_000L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun categorySeedUpgradeAddsExternalCashflowFallbacksOnlyOnce() = runBlocking {
        database.categoryDao().insertAll(
            listOf(
                CategoryEntity(
                    id = "social",
                    name = "人情往来",
                    shortName = "人情",
                    parentId = null,
                    iconKey = "volunteer-activism",
                    defaultNecessary = false,
                    kind = "EXPENSE",
                    sortOrder = 0,
                    isCustom = false
                ),
                CategoryEntity(
                    id = "income-salary",
                    name = "工资",
                    shortName = "工资",
                    parentId = null,
                    iconKey = "payments",
                    defaultNecessary = null,
                    kind = "INCOME",
                    sortOrder = 1,
                    isCustom = false
                )
            )
        )

        repository.seedDefaultCategoriesIfEmpty()

        assertEquals("其他转赠", database.categoryDao().findById("social-other-gift")?.name)
        assertEquals("其他收入", database.categoryDao().findById("income-other")?.name)

        database.categoryDao().deleteById("income-other")
        repository.seedDefaultCategoriesIfEmpty()

        assertEquals(null, database.categoryDao().findById("income-other"))
    }

    @Test
    fun transactionTrashPreviewsBalanceRestoresExactlyAndKeepsNotificationTombstoneAfterExpiry() = runBlocking {
        val notification = pendingNotification("trash-expense")
        database.rawNotificationDao().insert(notification)
        repository.confirmNotification(
            notificationId = notification.id,
            accountId = "cash",
            amountCents = 2_999L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "重复消费",
            note = null
        )
        val transaction = database.transactionDao().all().single()
        assertEquals(-2_999L, database.accountDao().find("cash")?.balanceCents)

        val preview = repository.previewTransactionDeletion(listOf(transaction.id)).single()
        assertEquals(-2_999L, preview.currentBalanceCents)
        assertEquals(0L, preview.projectedBalanceCents)

        val deletedAt = 10_000L
        repository.deleteTransaction(transaction.id, deletedAt)
        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(transaction.id, repository.deletedTransactions.first().single().id)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("IGNORED", database.rawNotificationDao().findById(notification.id)?.status)

        repository.restoreTransactionFromTrash(transaction.id)
        assertEquals(transaction.id, database.transactionDao().all().single().id)
        assertEquals(-2_999L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)

        repository.deleteTransaction(transaction.id, deletedAt)
        assertEquals(1, repository.purgeExpiredTransactionTrash(deletedAt + 7L * 24 * 60 * 60 * 1000))
        assertTrue(repository.deletedTransactions.first().isEmpty())
        assertEquals(null, database.transactionDao().findIncludingDeleted(transaction.id))
        assertEquals("IGNORED", database.rawNotificationDao().findById(notification.id)?.status)
    }

    @Test
    fun restoringOldExpenseReplaysOnlyThatEventAndPreservesLaterIncome() = runBlocking {
        repository.addTransaction(
            accountId = "cash",
            amountCents = 10_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "先发生的支出",
            note = null,
            occurredAt = 1_000L
        )
        val deletedId = database.transactionDao().all().single().id
        repository.deleteTransaction(deletedId, deletedAt = 2_000L)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)

        repository.addTransaction(
            accountId = "cash",
            amountCents = 20_000L,
            type = TransactionType.INCOME,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "后来正常进账",
            note = null,
            occurredAt = 3_000L
        )
        assertEquals(20_000L, database.accountDao().find("cash")?.balanceCents)

        repository.restoreTransactionFromTrash(deletedId)

        assertEquals(10_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(2, database.transactionDao().all().size)
    }

    @Test
    fun restoringLoanTransactionRefusesToOverwriteLoanChangesMadeAfterDeletion() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(id = "guarded-loan", name = "恢复保护贷款", type = AccountType.LOAN.name, balanceCents = 300_000L)
        )
        database.loanPlanDao().upsert(
            LoanPlanEntity(
                id = "guarded-plan",
                accountId = "guarded-loan",
                principalCents = 300_000L,
                startDateEpochDay = 20_000L,
                repaymentMethod = "CUSTOM",
                installmentsJson = "[]",
                remainingPrincipalCents = 300_000L
            )
        )
        repository.addLoanPrepayment(
            cashAccountId = "cash",
            planId = "guarded-plan",
            principalCents = 100_000L,
            note = null
        )
        val transaction = database.transactionDao().all().single()
        repository.deleteTransaction(transaction.id)
        val afterDelete = requireNotNull(database.loanPlanDao().findById("guarded-plan"))
        database.loanPlanDao().upsert(afterDelete.copy(remainingPrincipalCents = 250_000L))

        val result = runCatching { repository.restoreTransactionFromTrash(transaction.id) }

        assertTrue(result.isFailure)
        assertEquals(250_000L, database.loanPlanDao().findById("guarded-plan")?.remainingPrincipalCents)
        assertEquals(transaction.id, repository.deletedTransactions.first().single().id)
        assertTrue(database.transactionDao().all().isEmpty())
    }

    @Test
    fun duplicateRawNotificationDoesNotAdvanceLastReceivedAt() = runBlocking {
        val original = pendingNotification("duplicate-evidence").copy(receivedAt = 1_000L)
        repository.saveRawNotification(original)
        repository.saveRawNotification(original.copy(receivedAt = 9_000L))

        assertEquals(1_000L, repository.lastReceivedAt.first())
        assertEquals(1, database.rawNotificationDao().observeByStatus(original.status).first().size)
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
    fun singleLegWithdrawalCreatesTransferAndFeeExactlyOnce() = runBlocking {
        val notification = pendingNotification("wechat-withdrawal")
        database.rawNotificationDao().insert(notification)

        repeat(2) {
            repository.confirmTransferFromNotification(
                notificationId = notification.id,
                fromAccountId = "cash",
                toAccountId = "savings",
                amountCents = 32_509L,
                feeCents = 33L,
                note = "微信零钱全部提现"
            )
        }

        val transfer = database.transferDao().all().single()
        assertEquals(32_509L, transfer.amountCents)
        val fee = database.transactionDao().all().single()
        assertEquals(TransactionType.FEE.name, fee.type)
        assertEquals(33L, fee.amountCents)
        assertEquals("cash", fee.accountId)
        assertEquals(notification.id, fee.notificationId)
        assertEquals("IGNORED", database.rawNotificationDao().findById(notification.id)?.status)
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
    fun recurringRuleAdvancesWhenIncomingChargeWasAlreadyAutoLinked() = runBlocking {
        val dueAt = System.currentTimeMillis() - 60_000L
        val rule = recurringRule(dueAt)
        database.recurringRuleDao().upsert(rule)
        database.transactionDao().insert(
            TransactionEntity(
                id = "already-linked-charge",
                accountId = rule.accountId,
                amountCents = rule.amountCents,
                type = rule.type,
                category = rule.category,
                merchant = rule.merchant,
                occurredAt = dueAt,
                recurringRuleId = rule.id
            )
        )

        assertEquals(0, repository.processRecurring())
        assertTrue(database.recurringRuleDao().observeAll().first().single().nextRunAt > dueAt)
        assertEquals(1, database.transactionDao().all().size)
    }

    @Test
    fun ambiguousRecurringRulesDoNotAutoClaimAnIncomingExpense() = runBlocking {
        val dueAt = System.currentTimeMillis()
        val first = recurringRule(dueAt).copy(id = "rule-a", accountId = "cash", merchant = "保险公司")
        val second = first.copy(id = "rule-b")
        database.recurringRuleDao().upsert(first)
        database.recurringRuleDao().upsert(second)
        val notification = pendingNotification("ambiguous-recurring").copy(postedAt = dueAt)
        database.rawNotificationDao().insert(notification)

        repository.confirmNotification(
            notificationId = notification.id,
            accountId = "cash",
            amountCents = first.amountCents,
            type = TransactionType.EXPENSE,
            category = first.category,
            merchant = "保险公司",
            note = null
        )

        assertEquals(null, database.transactionDao().all().single().recurringRuleId)
    }

    @Test
    fun editingTransactionPersistsPaymentChannel() = runBlocking {
        database.transactionDao().insert(sampleExpense("editable-channel"))

        repository.updateTransaction(
            id = "editable-channel",
            amountCents = 1_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "测试",
            note = null,
            accountId = "cash",
            occurredAt = System.currentTimeMillis(),
            necessity = true,
            channel = "支付宝"
        )

        assertEquals("支付宝", database.transactionDao().findById("editable-channel")?.channel)

        repository.updateTransaction(
            id = "editable-channel",
            amountCents = 1_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "测试",
            note = null,
            accountId = "cash",
            occurredAt = System.currentTimeMillis(),
            necessity = true,
            channel = null
        )

        assertEquals(null, database.transactionDao().findById("editable-channel")?.channel)
    }

    @Test
    fun editingExpenseCanRemoveOutstandingMarkWithoutErasingPaidAudit() = runBlocking {
        database.transactionDao().insert(
            sampleExpense("cancel-reimbursement").copy(
                isReimbursable = true,
                reimbursedCents = 400L
            )
        )

        repository.updateTransaction(
            id = "cancel-reimbursement",
            amountCents = 1_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "小额垫付",
            note = null,
            accountId = "cash",
            occurredAt = System.currentTimeMillis(),
            necessity = true,
            channel = "支付宝",
            isReimbursable = false
        )

        val updated = database.transactionDao().findById("cancel-reimbursement")!!
        assertFalse(updated.isReimbursable)
        assertEquals(400L, updated.reimbursedCents)
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
    fun oneArrivalCanSettleOldExpensesAndDeletionRestoresTheirPendingState() = runBlocking {
        val oldExpense = sampleExpense("old-reimbursable").copy(
            amountCents = 12_000L,
            occurredAt = 1_672_531_200_000L,
            isReimbursable = true
        )
        val recentExpense = sampleExpense("recent-reimbursable").copy(
            amountCents = 8_000L,
            occurredAt = 1_735_689_600_000L,
            isReimbursable = true
        )
        database.transactionDao().insert(oldExpense)
        database.transactionDao().insert(recentExpense)

        repository.addReimbursement(
            accountId = "cash",
            amountCents = 20_000L,
            note = "合并报销到账",
            occurredAt = 1_777_593_600_000L,
            expenseIds = listOf(oldExpense.id, recentExpense.id)
        )

        val arrival = database.transactionDao().all().single {
            it.type == TransactionType.REIMBURSEMENT.name
        }
        assertEquals(12_000L, database.transactionDao().findById(oldExpense.id)?.reimbursedCents)
        assertEquals(8_000L, database.transactionDao().findById(recentExpense.id)?.reimbursedCents)
        assertEquals(
            mapOf(oldExpense.id to 12_000L, recentExpense.id to 8_000L),
            database.reimbursementLinkDao().findByReimbursement(arrival.id)
                .associate { it.expenseTxId to it.coveredCents }
        )

        repository.updateReimbursement(
            id = arrival.id,
            accountId = "savings",
            amountCents = 20_000L,
            note = "修改后的合并到账",
            occurredAt = 1_777_680_000_000L,
            expenseIds = listOf(recentExpense.id, oldExpense.id)
        )
        val updatedArrival = database.transactionDao().findById(arrival.id)!!
        assertEquals("savings", updatedArrival.accountId)
        assertEquals("修改后的合并到账", updatedArrival.note)
        assertEquals(20_000L, updatedArrival.amountCents)

        var linkedExpenseDeleteFailed = false
        try {
            repository.deleteTransaction(oldExpense.id)
        } catch (_: IllegalArgumentException) {
            linkedExpenseDeleteFailed = true
        }
        assertTrue(linkedExpenseDeleteFailed)
        assertEquals(oldExpense.id, database.transactionDao().findById(oldExpense.id)?.id)

        repository.deleteTransaction(arrival.id)

        assertEquals(0L, database.transactionDao().findById(oldExpense.id)?.reimbursedCents)
        assertEquals(0L, database.transactionDao().findById(recentExpense.id)?.reimbursedCents)
        assertEquals(2, database.reimbursementLinkDao().findByReimbursement(arrival.id).size)

        repository.restoreTransactionFromTrash(arrival.id)

        assertEquals(12_000L, database.transactionDao().findById(oldExpense.id)?.reimbursedCents)
        assertEquals(8_000L, database.transactionDao().findById(recentExpense.id)?.reimbursedCents)
        assertEquals(arrival.id, database.transactionDao().findById(arrival.id)?.id)
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

    @Test
    fun postPurchaseInstallmentKeepsOriginalExpenseAndCardDebtWhileCreatingAuditTrail() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 1_200_000L, expenseCents = 1_200_000L)
        val cardBefore = requireNotNull(database.accountDao().find("card"))
        val transactionBefore = requireNotNull(database.transactionDao().findById("card-purchase"))

        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "手机 12 期",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 1_200_000L)),
                installmentCount = 12,
                firstDueDateEpochDay = 21_000L,
                expectedFeeCentsPerPeriod = 600L
            )
        )

        assertEquals(cardBefore, database.accountDao().find("card"))
        assertEquals(transactionBefore, database.transactionDao().findById("card-purchase"))
        assertEquals(1_200_000L, database.creditCardInstallmentDao().findById(planId)?.remainingPrincipalCents)
        assertEquals(1_200_000L, database.creditCardInstallmentAllocationDao().findByPlan(planId).single().allocatedPrincipalCents)
        assertEquals(12, database.creditCardInstallmentScheduleDao().findByPlan(planId).size)
        assertEquals("CREATED", database.creditCardInstallmentAuditDao().findByPlan(planId).single().eventType)

        val duplicateFailure = runCatching {
            repository.createCardInstallment(
                CreditCardInstallmentDraft(
                    cardAccountId = "card",
                    label = "重复分期",
                    allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 1L)),
                    installmentCount = 1,
                    firstDueDateEpochDay = 21_000L
                )
            )
        }.exceptionOrNull()
        assertTrue(duplicateFailure is IllegalArgumentException)
    }

    @Test
    fun statementInstallmentUsesMultipleOriginalPurchasesAndCannotExceedCurrentStatement() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 400_000L, expenseCents = 240_000L)
        val account = requireNotNull(database.accountDao().find("card"))
        database.accountDao().upsert(
            account.copy(statementOriginalDueCents = 300_000L, statementDay = 8, dueDay = 23)
        )
        val fixedNow = java.time.LocalDate.of(2026, 8, 23)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val billedAt = java.time.LocalDate.of(2026, 7, 20)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        database.transactionDao().update(
            id = "card-purchase",
            amountCents = 240_000L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "第一笔已出账消费",
            note = null,
            accountId = "card",
            occurredAt = billedAt,
            necessity = null,
            channel = null
        )
        database.transactionDao().insert(
            TransactionEntity(
                id = "card-purchase-2",
                accountId = "card",
                amountCents = 160_000L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.SHOPPING.name,
                merchant = "第二笔已出账消费",
                occurredAt = billedAt + 1_000L
            )
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity("opening-card", "card", 0L, Long.MIN_VALUE, "OPENING")
        )
        val service = CreditCardInstallmentService(database, now = { fixedNow })

        val planId = service.create(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "8月账单分期",
                allocations = listOf(
                    CardInstallmentAllocationRequest("card-purchase", 120_000L),
                    CardInstallmentAllocationRequest("card-purchase-2", 120_000L)
                ),
                installmentCount = 6,
                firstDueDateEpochDay = java.time.LocalDate.of(2026, 9, 23).toEpochDay(),
                installmentType = CreditCardInstallmentService.TYPE_STATEMENT
            )
        )

        val plan = requireNotNull(database.creditCardInstallmentDao().findById(planId))
        assertEquals(CreditCardInstallmentService.TYPE_STATEMENT, plan.installmentType)
        assertEquals(java.time.LocalDate.of(2026, 8, 8).toEpochDay(), plan.statementCycleStartEpochDay)
        assertEquals(400_000L, database.accountDao().find("card")?.balanceCents)
        assertEquals(240_000L, database.transactionDao().findById("card-purchase")?.amountCents)
        assertEquals(
            listOf("card-purchase", "card-purchase-2"),
            database.creditCardInstallmentAllocationDao().findByPlan(planId).map { it.transactionId }.sorted()
        )

        val overStatement = runCatching {
            service.create(
                CreditCardInstallmentDraft(
                    cardAccountId = "card",
                    label = "重复纳入同一期账单",
                    allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 60_001L)),
                    installmentCount = 3,
                    firstDueDateEpochDay = java.time.LocalDate.of(2026, 9, 23).toEpochDay(),
                    installmentType = CreditCardInstallmentService.TYPE_STATEMENT
                )
            )
        }.exceptionOrNull()
        assertTrue(overStatement is IllegalArgumentException)
        assertTrue(overStatement?.message?.contains("账单分期本金不能超过") == true)

    }

    @Test
    fun unbilledInstallmentsCannotExceedDebtOutsideCurrentStatement() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 150_000L, expenseCents = 50_000L)
        val account = requireNotNull(database.accountDao().find("card"))
        database.accountDao().upsert(
            account.copy(statementOriginalDueCents = 100_000L, statementDay = 26, dueDay = 15)
        )
        val zone = java.time.ZoneId.systemDefault()
        val fixedNow = java.time.LocalDate.of(2026, 8, 23).atStartOfDay(zone).toInstant().toEpochMilli()
        val unbilledAt = java.time.LocalDate.of(2026, 8, 20).atStartOfDay(zone).toInstant().toEpochMilli()
        database.transactionDao().update(
            id = "card-purchase",
            amountCents = 50_000L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "未出账一",
            note = null,
            accountId = "card",
            occurredAt = unbilledAt,
            necessity = null,
            channel = null
        )
        val service = CreditCardInstallmentService(database, now = { fixedNow })
        service.create(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "未出账500",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 50_000L)),
                installmentCount = 3,
                firstDueDateEpochDay = java.time.LocalDate.of(2026, 9, 15).toEpochDay()
            )
        )
        database.transactionDao().insert(
            TransactionEntity(
                id = "card-purchase-2",
                accountId = "card",
                amountCents = 64_000L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.SHOPPING.name,
                merchant = "未出账二",
                occurredAt = unbilledAt + 1_000L
            )
        )

        val overflow = runCatching {
            service.create(
                CreditCardInstallmentDraft(
                    cardAccountId = "card",
                    label = "超额未出账640",
                    allocations = listOf(CardInstallmentAllocationRequest("card-purchase-2", 64_000L)),
                    installmentCount = 3,
                    firstDueDateEpochDay = java.time.LocalDate.of(2026, 9, 15).toEpochDay()
                )
            )
        }.exceptionOrNull()

        assertTrue(overflow?.message?.contains("尚未出账、尚未分期") == true)
        assertEquals(1, database.creditCardInstallmentDao().all().count { it.status == "ACTIVE" })
    }

    @Test
    fun installmentCreationRejectsWrongBillingWindowAndMultipleUnbilledPurchases() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 400_000L, expenseCents = 120_000L)
        val account = requireNotNull(database.accountDao().find("card"))
        database.accountDao().upsert(
            account.copy(statementOriginalDueCents = 300_000L, statementDay = 8, dueDay = 23)
        )
        val zone = java.time.ZoneId.systemDefault()
        val fixedNow = java.time.LocalDate.of(2026, 8, 23).atStartOfDay(zone).toInstant().toEpochMilli()
        val unbilledAt = java.time.LocalDate.of(2026, 8, 20).atStartOfDay(zone).toInstant().toEpochMilli()
        database.transactionDao().update(
            id = "card-purchase",
            amountCents = 120_000L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "未出账一",
            note = null,
            accountId = "card",
            occurredAt = unbilledAt,
            necessity = null,
            channel = null
        )
        database.transactionDao().insert(
            TransactionEntity(
                id = "card-purchase-2",
                accountId = "card",
                amountCents = 80_000L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.SHOPPING.name,
                merchant = "未出账二",
                occurredAt = unbilledAt + 1_000L
            )
        )
        val service = CreditCardInstallmentService(database, now = { fixedNow })

        val wrongWindow = runCatching {
            service.create(
                CreditCardInstallmentDraft(
                    cardAccountId = "card",
                    label = "错误账单分期",
                    allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 100_000L)),
                    installmentCount = 3,
                    firstDueDateEpochDay = java.time.LocalDate.of(2026, 9, 23).toEpochDay(),
                    installmentType = CreditCardInstallmentService.TYPE_STATEMENT
                )
            )
        }.exceptionOrNull()
        assertTrue(wrongWindow?.message?.contains("本期已出账消费") == true)

        val multipleUnbilled = runCatching {
            service.create(
                CreditCardInstallmentDraft(
                    cardAccountId = "card",
                    label = "错误多笔消费分期",
                    allocations = listOf(
                        CardInstallmentAllocationRequest("card-purchase", 60_000L),
                        CardInstallmentAllocationRequest("card-purchase-2", 40_000L)
                    ),
                    installmentCount = 3,
                    firstDueDateEpochDay = java.time.LocalDate.of(2026, 9, 23).toEpochDay(),
                    installmentType = CreditCardInstallmentService.TYPE_POST_PURCHASE
                )
            )
        }.exceptionOrNull()
        assertTrue(multipleUnbilled?.message?.contains("只能选择一笔消费") == true)
    }

    @Test
    fun allocatedCardExpenseCannotBeEditedOrDeletedOutsideInstallmentWorkflow() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 120_000L, expenseCents = 120_000L)
        repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "受保护原消费",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 120_000L)),
                installmentCount = 12,
                firstDueDateEpochDay = 21_000L
            )
        )
        val before = requireNotNull(database.transactionDao().findById("card-purchase"))

        val editFailure = runCatching {
            repository.updateTransaction(
                id = before.id,
                amountCents = 1L,
                type = TransactionType.EXPENSE,
                category = before.category,
                merchant = before.merchant,
                note = before.note,
                accountId = before.accountId,
                occurredAt = before.occurredAt,
                necessity = before.necessity,
                channel = before.channel
            )
        }.exceptionOrNull()
        val deleteFailure = runCatching { repository.deleteTransaction(before.id) }.exceptionOrNull()

        assertTrue(editFailure is IllegalArgumentException)
        assertTrue(deleteFailure is IllegalArgumentException)
        assertEquals(before, database.transactionDao().findById(before.id))
    }

    @Test
    fun paymentOnlyInstallmentStoresUnknownForecastChargeWithoutPostingInterestOrExpense() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 120_000L, expenseCents = 120_000L)
        val transactionsBefore = database.transactionDao().all()
        val cardBefore = requireNotNull(database.accountDao().find("card"))

        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "只填月供",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 120_000L)),
                installmentCount = 12,
                firstDueDateEpochDay = 21_000L,
                expectedPaymentCentsPerPeriod = 10_500L
            )
        )

        val plan = requireNotNull(database.creditCardInstallmentDao().findById(planId))
        val schedule = database.creditCardInstallmentScheduleDao().findByPlan(planId)
        assertEquals(10_500L, plan.monthlyPaymentCents)
        assertEquals(6_000L, schedule.sumOf { it.expectedUnclassifiedChargeCents })
        assertEquals(0L, schedule.sumOf { it.expectedInterestCents + it.expectedFeeCents })
        assertEquals(transactionsBefore, database.transactionDao().all())
        assertEquals(cardBefore, database.accountDao().find("card"))
    }

    @Test
    fun realCardPaymentAutoMatchesUniqueScheduleWithoutTurningForecastChargeIntoExpense() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 120_000L, expenseCents = 120_000L)
        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "固定月供",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 120_000L)),
                installmentCount = 12,
                firstDueDateEpochDay = 21_000L,
                expectedPaymentCentsPerPeriod = 10_500L
            )
        )
        val transactionsBefore = database.transactionDao().all()

        repository.addTransfer("cash", "card", 10_500L, "信用卡还款", epochMillis(21_000L))

        val match = database.creditCardInstallmentPaymentMatchDao().all().single()
        val firstSchedule = database.creditCardInstallmentScheduleDao().findByPlan(planId).first()
        val plan = requireNotNull(database.creditCardInstallmentDao().findById(planId))
        assertEquals("AUTO_MATCHED", match.status)
        assertEquals(10_000L, match.principalCents)
        assertEquals(10_000L, firstSchedule.principalPaidCents)
        assertEquals(500L, firstSchedule.expectedUnclassifiedChargeCents)
        assertEquals(0L, firstSchedule.interestPaidCents + firstSchedule.feePaidCents)
        assertEquals("PAID", firstSchedule.status)
        assertEquals(110_000L, plan.remainingPrincipalCents)
        assertEquals(11, plan.periodsRemaining)
        assertEquals(109_500L, database.accountDao().find("card")?.balanceCents)
        assertEquals(transactionsBefore, database.transactionDao().all())
        assertEquals(
            listOf("CREATED", "PAYMENT_MATCHED"),
            database.creditCardInstallmentAuditDao().findByPlan(planId).map { it.eventType }
        )
    }

    @Test
    fun unmatchedCardPaymentRemainsARealTransferWithoutChangingInstallmentProgress() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 120_000L, expenseCents = 120_000L)
        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "金额不匹配",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 120_000L)),
                installmentCount = 12,
                firstDueDateEpochDay = 21_000L,
                expectedPaymentCentsPerPeriod = 10_500L
            )
        )

        repository.addTransfer("cash", "card", 10_499L, "普通整卡还款", epochMillis(21_000L))

        assertEquals(1, database.transferDao().all().size)
        assertTrue(database.creditCardInstallmentPaymentMatchDao().all().isEmpty())
        assertEquals(120_000L, database.creditCardInstallmentDao().findById(planId)?.remainingPrincipalCents)
        assertEquals("UPCOMING", database.creditCardInstallmentScheduleDao().findByPlan(planId).first().status)
        assertEquals(listOf("CREATED"), database.creditCardInstallmentAuditDao().findByPlan(planId).map { it.eventType })
        assertEquals(109_501L, database.accountDao().find("card")?.balanceCents)
    }

    @Test
    fun repeatingAutoMatchForTheSameTransferIsIdempotent() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 120_000L, expenseCents = 120_000L)
        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "幂等匹配",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 120_000L)),
                installmentCount = 12,
                firstDueDateEpochDay = 21_000L,
                expectedPaymentCentsPerPeriod = 10_500L
            )
        )
        repository.addTransfer("cash", "card", 10_500L, "信用卡还款", epochMillis(21_000L))
        val transferId = database.transferDao().all().single().id

        val repeated = CreditCardInstallmentService(database).autoMatchTransfer(transferId)

        assertEquals(CardInstallmentPaymentMatchKind.MATCHED, repeated.kind)
        assertEquals(1, database.creditCardInstallmentPaymentMatchDao().all().size)
        assertEquals(110_000L, database.creditCardInstallmentDao().findById(planId)?.remainingPrincipalCents)
        assertEquals(listOf("CREATED", "PAYMENT_MATCHED"), database.creditCardInstallmentAuditDao().findByPlan(planId).map { it.eventType })
    }

    @Test
    fun ambiguousCardPaymentStaysPendingWithoutAdvancingEitherPlan() = runBlocking {
        val planIds = insertAmbiguousCardInstallments()

        repository.addTransfer("cash", "card", 10_000L, "整卡还款", epochMillis(21_000L))

        val matches = database.creditCardInstallmentPaymentMatchDao().all()
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.status == "PENDING" })
        planIds.forEach { planId ->
            assertEquals(10_000L, database.creditCardInstallmentDao().findById(planId)?.remainingPrincipalCents)
            assertEquals("UPCOMING", database.creditCardInstallmentScheduleDao().findByPlan(planId).single().status)
            assertEquals(listOf("CREATED"), database.creditCardInstallmentAuditDao().findByPlan(planId).map { it.eventType })
        }
    }

    @Test
    fun confirmingAmbiguousCardPaymentAdvancesOnePlanAndRejectsTheOtherCandidate() = runBlocking {
        val planIds = insertAmbiguousCardInstallments()
        repository.addTransfer("cash", "card", 10_000L, "整卡还款", epochMillis(21_000L))
        val transferId = database.transferDao().all().single().id
        val selectedSchedule = database.creditCardInstallmentScheduleDao().findByPlan(planIds.first()).single()

        repository.confirmCardInstallmentPaymentMatch(transferId, selectedSchedule.id, 10_000L)

        val matches = database.creditCardInstallmentPaymentMatchDao().all()
        assertEquals(1, matches.count { it.status == "USER_CONFIRMED" })
        assertEquals(1, matches.count { it.status == "REJECTED" })
        assertEquals(0L, database.creditCardInstallmentDao().findById(planIds.first())?.remainingPrincipalCents)
        assertEquals(10_000L, database.creditCardInstallmentDao().findById(planIds.last())?.remainingPrincipalCents)
    }

    @Test
    fun pendingCardPaymentMustBeResolvedBeforeTermsChangeOrCancellation() = runBlocking {
        val planIds = insertAmbiguousCardInstallments()
        repository.addTransfer("cash", "card", 10_000L, "整卡还款", epochMillis(21_000L))

        val adjustFailure = runCatching {
            repository.adjustCardInstallmentTerms(
                planIds.first(),
                CreditCardInstallmentTerms(1, 21_031L)
            )
        }.exceptionOrNull()
        val cancelFailure = runCatching { repository.deleteCardInstallment(planIds.first()) }.exceptionOrNull()

        assertTrue(adjustFailure is IllegalArgumentException)
        assertTrue(cancelFailure is IllegalArgumentException)
        assertEquals("ACTIVE", database.creditCardInstallmentDao().findById(planIds.first())?.status)
        assertTrue(database.creditCardInstallmentPaymentMatchDao().findByTransfer(database.transferDao().all().single().id).all { it.status == "PENDING" })
    }

    @Test
    fun deletingMatchedTransferRestoresInstallmentProgressAndKeepsAuditHistory() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 120_000L, expenseCents = 120_000L)
        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "删除回滚",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 120_000L)),
                installmentCount = 12,
                firstDueDateEpochDay = 21_000L,
                expectedPaymentCentsPerPeriod = 10_500L
            )
        )
        repository.addTransfer("cash", "card", 10_500L, "信用卡还款", epochMillis(21_000L))
        val transferId = database.transferDao().all().single().id

        repository.deleteTransfer(transferId)

        val match = database.creditCardInstallmentPaymentMatchDao().all().single()
        val firstSchedule = database.creditCardInstallmentScheduleDao().findByPlan(planId).first()
        val plan = requireNotNull(database.creditCardInstallmentDao().findById(planId))
        assertEquals("REVERSED", match.status)
        assertEquals(0L, firstSchedule.principalPaidCents)
        assertEquals("UPCOMING", firstSchedule.status)
        assertEquals(120_000L, plan.remainingPrincipalCents)
        assertEquals(12, plan.periodsRemaining)
        assertEquals(120_000L, database.accountDao().find("card")?.balanceCents)
        assertEquals(
            listOf("CREATED", "PAYMENT_MATCHED", "PAYMENT_MATCH_REVERSED"),
            database.creditCardInstallmentAuditDao().findByPlan(planId).map { it.eventType }
        )
        assertTrue(database.transferDao().all().isEmpty())
        assertTrue(database.transferDao().findIncludingDeleted(transferId)?.deletedAt != null)

        repository.restoreTransferFromTrash(transferId)

        assertEquals(1, database.transferDao().all().size)
        assertEquals("AUTO_MATCHED", database.creditCardInstallmentPaymentMatchDao().all().single().status)
        assertEquals(10_000L, database.creditCardInstallmentScheduleDao().findByPlan(planId).first().principalPaidCents)
        assertEquals(110_000L, database.creditCardInstallmentDao().findById(planId)?.remainingPrincipalCents)
    }

    @Test
    fun cancellingInstallmentReleasesCapacityWithoutDeletingAllocationOrAudit() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 500_000L, expenseCents = 500_000L)
        val firstPlanId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "第一版",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 500_000L)),
                installmentCount = 5,
                firstDueDateEpochDay = 21_000L
            )
        )

        repository.deleteCardInstallment(firstPlanId)

        assertEquals("CANCELLED", database.creditCardInstallmentDao().findById(firstPlanId)?.status)
        assertEquals(1, database.creditCardInstallmentAllocationDao().findByPlan(firstPlanId).size)
        assertEquals(listOf("CREATED", "CANCELLED"), database.creditCardInstallmentAuditDao().findByPlan(firstPlanId).map { it.eventType })
        assertTrue(database.creditCardInstallmentScheduleDao().findByPlan(firstPlanId).all { it.status == "CANCELLED" })

        val replacementId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "重新分期",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 500_000L)),
                installmentCount = 10,
                firstDueDateEpochDay = 21_031L
            )
        )
        assertEquals("ACTIVE", database.creditCardInstallmentDao().findById(replacementId)?.status)
    }

    @Test
    fun adjustingInstallmentAppendsRevisionAndPreservesCancelledForecastRows() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 600_000L, expenseCents = 600_000L)
        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "调整测试",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 600_000L)),
                installmentCount = 6,
                firstDueDateEpochDay = 21_000L
            )
        )

        repository.adjustCardInstallmentTerms(
            planId,
            CreditCardInstallmentTerms(
                installmentCount = 3,
                firstDueDateEpochDay = 21_031L,
                expectedInterestCentsPerPeriod = 100L,
                expectedFeeCentsPerPeriod = 50L
            )
        )

        val plan = requireNotNull(database.creditCardInstallmentDao().findById(planId))
        val schedules = database.creditCardInstallmentScheduleDao().findByPlan(planId)
        assertEquals(2, plan.scheduleRevision)
        assertEquals(9, schedules.size)
        assertTrue(schedules.filter { it.revision == 1 }.all { it.status == "CANCELLED" })
        assertTrue(schedules.filter { it.revision == 2 }.all { it.status == "UPCOMING" })
        assertEquals(listOf("CREATED", "TERMS_ADJUSTED"), database.creditCardInstallmentAuditDao().findByPlan(planId).map { it.eventType })
    }

    @Test
    fun customInstallmentScheduleUsesEditedDatesAndPaymentsAcrossCreateAndAdjustment() = runBlocking {
        insertCreditCardExpense(cardDebtCents = 60_000L, expenseCents = 60_000L)
        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "card",
                label = "逐期自填",
                allocations = listOf(CardInstallmentAllocationRequest("card-purchase", 60_000L)),
                installmentCount = 3,
                firstDueDateEpochDay = 21_000L,
                customSchedule = listOf(
                    CreditCardInstallmentScheduleDraft(21_001L, 21_000L),
                    CreditCardInstallmentScheduleDraft(21_033L, 22_000L),
                    CreditCardInstallmentScheduleDraft(21_062L, 23_000L)
                )
            )
        )

        val createdPlan = requireNotNull(database.creditCardInstallmentDao().findById(planId))
        val createdSchedule = database.creditCardInstallmentScheduleDao().findByPlan(planId)
        assertEquals(21_001L, createdPlan.nextDueDateEpochDay)
        assertEquals(listOf(21_001L, 21_033L, 21_062L), createdSchedule.map { it.dueDateEpochDay })
        assertEquals(
            listOf(21_000L, 22_000L, 23_000L),
            createdSchedule.map {
                it.principalDueCents + it.expectedInterestCents + it.expectedFeeCents + it.expectedUnclassifiedChargeCents
            }
        )
        assertEquals(60_000L, createdSchedule.sumOf { it.principalDueCents })

        repository.adjustCardInstallmentTerms(
            planId,
            CreditCardInstallmentTerms(
                installmentCount = 2,
                firstDueDateEpochDay = 21_100L,
                customSchedule = listOf(
                    CreditCardInstallmentScheduleDraft(21_101L, 31_000L),
                    CreditCardInstallmentScheduleDraft(21_132L, 32_000L)
                )
            )
        )

        val adjustedPlan = requireNotNull(database.creditCardInstallmentDao().findById(planId))
        val adjustedSchedule = database.creditCardInstallmentScheduleDao().findByPlan(planId)
            .filter { it.revision == 2 }
        assertEquals(21_101L, adjustedPlan.nextDueDateEpochDay)
        assertEquals(listOf(21_101L, 21_132L), adjustedSchedule.map { it.dueDateEpochDay })
        assertEquals(
            listOf(31_000L, 32_000L),
            adjustedSchedule.map {
                it.principalDueCents + it.expectedInterestCents + it.expectedFeeCents + it.expectedUnclassifiedChargeCents
            }
        )
        assertEquals(60_000L, adjustedSchedule.sumOf { it.principalDueCents })
    }

    private suspend fun insertCreditCardExpense(cardDebtCents: Long, expenseCents: Long) {
        database.accountDao().upsert(
            AccountEntity(
                id = "card",
                name = "测试信用卡",
                type = AccountType.CREDIT.name,
                balanceCents = cardDebtCents
            )
        )
        database.transactionDao().insert(
            TransactionEntity(
                id = "card-purchase",
                accountId = "card",
                amountCents = expenseCents,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.SHOPPING.name,
                merchant = "测试消费",
                occurredAt = 1_000L
            )
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "opening-card",
                accountId = "card",
                // The inserted expense below is already part of the requested current card debt.
                // Keep only the debt that predates that expense in the opening checkpoint.
                balanceCents = cardDebtCents - expenseCents,
                checkedAt = Long.MIN_VALUE,
                source = "OPENING"
            )
        )
    }

    private suspend fun insertAmbiguousCardInstallments(): List<String> {
        insertCreditCardExpense(cardDebtCents = 20_000L, expenseCents = 10_000L)
        database.transactionDao().insert(
            TransactionEntity(
                id = "card-purchase-2",
                accountId = "card",
                amountCents = 10_000L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.SHOPPING.name,
                merchant = "第二笔消费",
                occurredAt = 2_000L
            )
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "opening-card",
                accountId = "card",
                balanceCents = 0L,
                checkedAt = Long.MIN_VALUE,
                source = "OPENING"
            )
        )
        return listOf("card-purchase", "card-purchase-2").map { transactionId ->
            repository.createCardInstallment(
                CreditCardInstallmentDraft(
                    cardAccountId = "card",
                    label = transactionId,
                    allocations = listOf(CardInstallmentAllocationRequest(transactionId, 10_000L)),
                    installmentCount = 1,
                    firstDueDateEpochDay = 21_000L
                )
            )
        }
    }

    private fun epochMillis(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

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
