package com.assetsking.database

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assetsking.ledger.CardInstallmentAllocationRequest
import com.assetsking.ledger.CardInstallmentPaymentMatchKind
import com.assetsking.ledger.ContentFingerprint
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
        seedCash(1_250L)

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
        seedCash(342_000L)
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
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)

        repository.deleteTransaction(transaction.id)

        val restoredPlan = requireNotNull(database.loanPlanDao().findById("loan-plan"))
        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(600_000L, restoredPlan.remainingPrincipalCents)
        assertEquals(listOf(false, false), repository.v5PlanInput(restoredPlan).installments.map { it.isPaid })
        assertEquals(342_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("PENDING_CONFIRMATION", database.rawNotificationDao().findById(notification.id)?.status)
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
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)
    }

    @Test
    fun notificationConfirmationPersistsTheSelectedReimbursableFlag() = runBlocking {
        val notification = pendingNotification("reimbursable-expense")
        database.rawNotificationDao().insert(notification)
        seedCash(1_250L)

        repository.confirmNotification(
            notificationId = notification.id,
            accountId = "cash",
            amountCents = 1_250L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "公司垫付",
            note = null,
            isReimbursable = true
        )

        assertTrue(database.transactionDao().all().single().isReimbursable)
    }

    @Test
    fun smsMirrorConfirmationCannotCreateSecondTransaction() = runBlocking {
        val body = "【招商银行】您账户3683于08月28日00:31在支付宝消费1299.00元，余额2934.82元"
        val sms = pendingNotification("sms-mirror").copy(
            packageName = "sms",
            title = "95555",
            content = body,
            postedAt = 1_000_000L,
            receivedAt = 1_000_000L
        )
        val systemSms = pendingNotification("system-sms-mirror").copy(
            packageName = "com.android.mms.service",
            title = "招商银行",
            content = body,
            postedAt = 1_003_000L,
            receivedAt = 1_003_000L
        )
        database.rawNotificationDao().insert(sms)
        database.rawNotificationDao().insert(systemSms)
        seedCash(200_000L)

        repository.confirmNotification(
            notificationId = sms.id,
            accountId = "cash",
            amountCents = 129_900L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "扫码支付",
            note = null
        )
        repository.confirmNotification(
            notificationId = systemSms.id,
            accountId = "cash",
            amountCents = 129_900L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "扫码支付",
            note = null
        )

        assertEquals(1, database.transactionDao().all().size)
        assertEquals("LINKED", database.rawNotificationDao().findById(sms.id)?.status)
        assertEquals("IGNORED", database.rawNotificationDao().findById(systemSms.id)?.status)
    }

    @Test
    fun notificationBalanceOverridesStaleShortfallButRequiresOldestSameCardFirst() = runBlocking {
        val older = pendingNotification("older-balance").copy(
            content = "【招商银行】您账户3683消费1.00元，余额9.00元",
            postedAt = 1_000L,
            receivedAt = 1_000L
        )
        val newer = pendingNotification("newer-balance").copy(
            content = "【招商银行】您账户3683消费20.00元，余额5.00元",
            postedAt = 2_000L,
            receivedAt = 2_000L
        )
        database.rawNotificationDao().insert(older)
        database.rawNotificationDao().insert(newer)
        seedCash(50L)
        database.accountDao().find("cash")?.let {
            database.accountDao().upsert(it.copy(cardTail = "3683"))
        }

        val outOfOrder = runCatching {
            repository.confirmNotification(
                notificationId = newer.id,
                accountId = "cash",
                amountCents = 2_000L,
                type = TransactionType.EXPENSE,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = "较新消费",
                note = null,
                bankBalanceCents = 500L,
                bankCardTail = "3683"
            )
        }
        assertTrue(outOfOrder.isFailure)
        assertTrue(outOfOrder.exceptionOrNull()?.message?.contains("更早的待确认通知") == true)
        assertEquals("PENDING_CONFIRMATION", database.rawNotificationDao().findById(newer.id)?.status)
        assertTrue(database.transactionDao().all().isEmpty())

        repository.confirmNotification(
            notificationId = older.id,
            accountId = "cash",
            amountCents = 100L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "较早消费",
            note = null,
            bankBalanceCents = 900L,
            bankCardTail = "3683"
        )
        repository.confirmNotification(
            notificationId = newer.id,
            accountId = "cash",
            amountCents = 2_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "较新消费",
            note = null,
            bankBalanceCents = 500L,
            bankCardTail = "3683"
        )

        assertEquals(2, database.transactionDao().all().size)
        assertEquals("LINKED", database.rawNotificationDao().findById(newer.id)?.status)
        assertEquals(500L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun loanPaymentRejectsNonAssetPaymentAccount() = runBlocking {
        insertAccount("card-payment", AccountType.CREDIT.name)
        database.accountDao().upsert(
            AccountEntity(
                id = "loan-account",
                name = "测试贷款",
                type = AccountType.LOAN.name,
                balanceCents = 0L
            )
        )
        database.loanPlanDao().upsert(
            LoanPlanEntity(
                id = "loan-plan",
                accountId = "loan-account",
                principalCents = 2_000L,
                startDateEpochDay = 20_000L,
                repaymentMethod = "CUSTOM",
                installmentsJson = "[]",
                remainingPrincipalCents = 2_000L,
                status = "ACTIVE",
                originType = "OPENING_BALANCE",
            )
        )

        val result = runCatching {
            repository.addLoanPayment(
                cashAccountId = "card-payment",
                planId = "loan-plan",
                totalCents = 1_000L,
                principalCents = 1_000L,
                interestCents = 0L,
                feeCents = 0L,
                note = "信用卡不能作为贷款还款付款账户",
                occurredAt = 1L
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(database.transactionDao().all().isEmpty())
    }

    @Test
    fun transferRejectsInsufficientAssetBalance() = runBlocking {
        insertAccount("cash", balanceCents = 1_000L)

        val result = runCatching {
            repository.addTransfer(
                fromAccountId = "cash",
                toAccountId = "savings",
                amountCents = 2_000L,
                note = "余额不足划转",
                occurredAt = 1L
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(database.transferDao().all().isEmpty())
    }

    @Test
    fun lendingDisbursementRejectsInsufficientAssetBalance() = runBlocking {
        insertAccount("cash", balanceCents = 1_000L)
        insertAccount("receivable")
        database.lendingPlanDao().upsert(
            LendingPlanEntity(
                id = "lending-plan",
                receivableAccountId = "receivable",
                label = "测试出借",
                borrowerName = "测试借款人",
                principalCents = 2_000L,
                remainingPrincipalCents = 0L,
                startDateEpochDay = 20_000L,
                status = "PENDING_DISBURSEMENT",
                originType = "PENDING_DISBURSEMENT",
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        val result = runCatching {
            repository.addLendingDisbursement(
                cashAccountId = "cash",
                planId = "lending-plan",
                amountCents = 2_000L,
                note = "借出本金不能透支",
                occurredAt = 1L
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(database.transferDao().all().isEmpty())
    }

    @Test
    fun refundRequiresTheOriginalAccountWhenLinkingSource() = runBlocking {
        database.transactionDao().insert(
            TransactionEntity(
                id = "original-expense",
                accountId = "cash",
                amountCents = 5_000L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.SHOPPING.name,
                occurredAt = 1L,
                merchant = "外卖平台",
                channel = "微信"
            )
        )
        insertAccount("other-cash")

        val result = runCatching {
            repository.addTransaction(
                accountId = "other-cash",
                amountCents = 1_200L,
                type = TransactionType.REFUND,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = "外卖平台",
                note = "退款必须回到原出账卡片",
                occurredAt = 2L,
                refundOfId = "original-expense",
                channel = "微信"
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, database.transactionDao().all().size)
        assertTrue(database.transactionDao().all().all { it.type != TransactionType.REFUND.name })
    }

    @Test
    fun partialRefundPersistsOriginalLinkAndMatchingOrderPlatform() = runBlocking {
        seedCash(10_000L)
        val sourceId = repository.addTransaction(
            accountId = "cash",
            amountCents = 3_621L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.SHOPPING.name,
            merchant = "美团宁波象鲜科技有限公司",
            note = null,
            occurredAt = 1L,
            channel = "微信",
            orderPlatform = "美团"
        )
        val refundId = repository.addTransaction(
            accountId = "cash",
            amountCents = 17L,
            type = TransactionType.REFUND,
            category = TransactionCategory.SHOPPING.name,
            merchant = "美团宁波象鲜科技有限公司",
            note = null,
            occurredAt = 2L,
            channel = "微信",
            orderPlatform = "美团",
            refundOfId = sourceId
        )

        val reopened = requireNotNull(database.transactionDao().findById(refundId))
        assertEquals(sourceId, reopened.refundOfId)
        assertEquals("美团", reopened.orderPlatform)
    }

    @Test
    fun deletedRefundDoesNotConsumeRemainingRefundableAmount() = runBlocking {
        seedCash(10_000L)
        val sourceId = repository.addTransaction(
            accountId = "cash",
            amountCents = 10_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.SHOPPING.name,
            merchant = "外卖平台",
            note = "原消费",
            occurredAt = 1L,
            channel = "微信"
        )

        val deletedRefundId = repository.addTransaction(
            accountId = "cash",
            amountCents = 4_000L,
            type = TransactionType.REFUND,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "外卖平台",
            note = "先删掉的退款",
            occurredAt = 2L,
            refundOfId = sourceId,
            channel = "微信"
        )
        repository.deleteTransaction(deletedRefundId)

        val secondRefundId = repository.addTransaction(
            accountId = "cash",
            amountCents = 10_000L,
            type = TransactionType.REFUND,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "外卖平台",
            note = "重新退款",
            occurredAt = 3L,
            refundOfId = sourceId,
            channel = "微信"
        )

        assertEquals(2, database.transactionDao().all().size)
        assertEquals(secondRefundId, database.transactionDao().findById(secondRefundId)?.id)
        assertTrue(database.transactionDao().findIncludingDeleted(deletedRefundId)?.deletedAt != null)
        assertEquals(10_000L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun interestOnlyPaymentAdvancesExactlyOneZeroPrincipalInstallment() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(id = "interest-only-loan", name = "先息后本测试", type = AccountType.LOAN.name, balanceCents = 5_000_000L)
        )
        seedCash(32_705L)
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
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)

        repository.deleteTransaction(database.transactionDao().all().single().id)

        val restored = requireNotNull(database.loanPlanDao().findById("interest-only-plan"))
        assertEquals(listOf(false, false, false), repository.v5PlanInput(restored).installments.map { it.isPaid })
        assertEquals(5_000_000L, restored.remainingPrincipalCents)
        assertEquals(32_705L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun loanPrepaymentFeeChangesCashFlowButDoesNotReducePrincipalTwice() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(id = "prepay-loan", name = "提前还款测试", type = AccountType.LOAN.name, balanceCents = 300_000L)
        )
        seedCash(105_000L)
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
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)

        repository.deleteTransaction(transaction.id)

        val restoredPlan = requireNotNull(database.loanPlanDao().findById("prepay-plan"))
        assertEquals(300_000L, restoredPlan.remainingPrincipalCents)
        assertEquals(0L, restoredPlan.earlyRepaidCents)
        assertEquals(105_000L, database.accountDao().find("cash")?.balanceCents)

        repository.restoreTransactionFromTrash(transaction.id)

        val reappliedPlan = requireNotNull(database.loanPlanDao().findById("prepay-plan"))
        assertEquals(200_000L, reappliedPlan.remainingPrincipalCents)
        assertEquals(100_000L, reappliedPlan.earlyRepaidCents)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
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
    fun transactionTrashPreviewsBalanceRestoresExactlyAndKeepsNotificationAvailableAfterExpiry() = runBlocking {
        val notification = pendingNotification("trash-expense")
        database.rawNotificationDao().insert(notification)
        seedCash(2_999L)
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
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)

        val preview = repository.previewTransactionDeletion(listOf(transaction.id)).single()
        assertEquals(0L, preview.currentBalanceCents)
        assertEquals(2_999L, preview.projectedBalanceCents)

        val deletedAt = 10_000L
        repository.deleteTransaction(transaction.id, deletedAt)
        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(transaction.id, repository.deletedTransactions.first().single().id)
        assertEquals(2_999L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("PENDING_CONFIRMATION", database.rawNotificationDao().findById(notification.id)?.status)

        repository.restoreTransactionFromTrash(transaction.id)
        assertEquals(transaction.id, database.transactionDao().all().single().id)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)

        repository.deleteTransaction(transaction.id, deletedAt)
        assertEquals(1, repository.purgeExpiredTransactionTrash(deletedAt + 7L * 24 * 60 * 60 * 1000))
        assertTrue(repository.deletedTransactions.first().isEmpty())
        assertEquals(null, database.transactionDao().findIncludingDeleted(transaction.id))
        assertEquals("PENDING_CONFIRMATION", database.rawNotificationDao().findById(notification.id)?.status)
    }

    @Test
    fun restoringOldTrashAfterNotificationReconfirmationIsRejected() = runBlocking {
        val notification = pendingNotification("trash-reconfirmed")
        database.rawNotificationDao().insert(notification)
        seedCash(1_111L)
        repository.confirmNotification(
            notificationId = notification.id,
            accountId = "cash",
            amountCents = 1_111L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "重复确认边界",
            note = null
        )
        val original = database.transactionDao().all().single()
        repository.deleteTransaction(original.id, deletedAt = 10_000L)

        repository.confirmNotification(
            notificationId = notification.id,
            accountId = "cash",
            amountCents = 1_111L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "重新记账",
            note = null
        )
        val active = database.transactionDao().all().single()
        assertTrue(active.id != original.id)

        val restoreResult = runCatching { repository.restoreTransactionFromTrash(original.id) }
        assertTrue(restoreResult.isFailure)
        assertTrue(database.transactionDao().all().single().id == active.id)
        assertEquals(original.id, repository.deletedTransactions.first().single().id)
    }

    @Test
    fun restoringOldExpenseReplaysOnlyThatEventAndPreservesLaterIncome() = runBlocking {
        seedCash(10_000L)
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
        assertEquals(10_000L, database.accountDao().find("cash")?.balanceCents)

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

        assertEquals(20_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(2, database.transactionDao().all().size)
    }

    @Test
    fun restoringLoanTransactionRefusesToOverwriteLoanChangesMadeAfterDeletion() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(id = "guarded-loan", name = "恢复保护贷款", type = AccountType.LOAN.name, balanceCents = 300_000L)
        )
        seedCash(100_000L)
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
        seedCash(5_000L)

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
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun singleLegWithdrawalCreatesTransferAndFeeExactlyOnce() = runBlocking {
        val notification = pendingNotification("wechat-withdrawal")
        database.rawNotificationDao().insert(notification)
        seedCash(32_542L)

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
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
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
    fun legacyRulePersistsItsOriginalAnchorWhenItFirstAdvances() = runBlocking {
        val zone = ZoneId.systemDefault()
        fun at(date: LocalDate): Long = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val august31 = at(LocalDate.of(2026, 8, 31))
        val legacy = recurringRule(august31).copy(firstRunAt = 0L)
        database.recurringRuleDao().upsert(legacy)
        database.transactionDao().insert(
            TransactionEntity(
                id = "legacy-august-charge",
                accountId = legacy.accountId,
                amountCents = legacy.amountCents,
                type = legacy.type,
                category = legacy.category,
                merchant = legacy.merchant,
                occurredAt = august31
            )
        )

        assertEquals(1, repository.processRecurring(now = august31))
        val advanced = database.recurringRuleDao().findById(legacy.id)!!
        assertEquals(august31, advanced.firstRunAt)
        assertEquals(LocalDate.of(2026, 9, 30), java.time.Instant.ofEpochMilli(advanced.nextRunAt).atZone(zone).toLocalDate())
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
    fun recurringRuleWithoutAccountWaitsForManualClaimEvenWithUniqueSameDayAmount() = runBlocking {
        val dueAt = System.currentTimeMillis() - 60_000L
        val rule = recurringRule(dueAt).copy(
            id = "unbound-recurring",
            accountId = "",
            category = "",
            merchant = null,
            note = "房租"
        )
        database.recurringRuleDao().upsert(rule)
        database.transactionDao().insert(
            TransactionEntity(
                id = "savings-charge",
                accountId = "savings",
                amountCents = rule.amountCents,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.HOUSING.name,
                merchant = "房东",
                occurredAt = dueAt
            )
        )

        assertEquals(0, repository.processRecurring())
        assertEquals(null, database.transactionDao().findById("savings-charge")?.recurringRuleId)
        assertEquals(dueAt, database.recurringRuleDao().findById(rule.id)!!.nextRunAt)
    }

    @Test
    fun manualRecurringLinkAllowsAmountWithinToleranceButRejectsOutOfRange() = runBlocking {
        val dueAt = System.currentTimeMillis() - 60_000L
        val rule = recurringRule(dueAt).copy(id = "manual-match", accountId = "", merchant = null, category = "", amountCents = 3_000L)
        database.recurringRuleDao().upsert(rule)
        database.transactionDao().insert(
            TransactionEntity(
                id = "within-range",
                accountId = "savings",
                amountCents = 2_500L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.UNCATEGORIZED.name,
                occurredAt = dueAt
            )
        )
        repository.linkToRecurringRule("within-range", rule.id)
        assertEquals(rule.id, database.transactionDao().findById("within-range")?.recurringRuleId)

        val outOfRange = rule.copy(id = "manual-match-out-of-range", nextRunAt = dueAt)
        database.recurringRuleDao().upsert(outOfRange)
        database.transactionDao().insert(
            TransactionEntity(
                id = "out-of-range",
                accountId = "savings",
                amountCents = 2_000L,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.UNCATEGORIZED.name,
                occurredAt = dueAt
            )
        )

        var failed = false
        try {
            repository.linkToRecurringRule("out-of-range", outOfRange.id)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(null, database.transactionDao().findById("out-of-range")?.recurringRuleId)
    }

    @Test
    fun historicalShortMonthOccurrenceCanBeClaimedAndAdvancesToTheRestoredAnchorDay() = runBlocking {
        val zone = ZoneId.systemDefault()
        fun at(date: LocalDate): Long = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val august31 = at(LocalDate.of(2026, 8, 31))
        val september30 = at(LocalDate.of(2026, 9, 30))
        val rule = recurringRule(august31).copy(
            id = "historic-month-end",
            accountId = "",
            merchant = null,
            category = "",
            firstRunAt = august31
        )
        database.recurringRuleDao().upsert(rule)
        database.transactionDao().insert(
            TransactionEntity(
                id = "september-historic-charge",
                accountId = "savings",
                amountCents = rule.amountCents,
                type = TransactionType.EXPENSE.name,
                category = TransactionCategory.UNCATEGORIZED.name,
                occurredAt = september30
            )
        )

        repository.linkToRecurringRule("september-historic-charge", rule.id)

        assertEquals(rule.id, database.transactionDao().findById("september-historic-charge")?.recurringRuleId)
        val nextDate = java.time.Instant.ofEpochMilli(database.recurringRuleDao().findById(rule.id)!!.nextRunAt)
            .atZone(zone)
            .toLocalDate()
        assertEquals(LocalDate.of(2026, 10, 31), nextDate)
    }

    @Test
    fun cancellingCurrentRecurringLinkMakesTheSameOccurrenceSelectableAgain() = runBlocking {
        val dueAt = System.currentTimeMillis() - 60_000L
        val rule = recurringRule(dueAt).copy(id = "cancel-recurring", accountId = "", merchant = null, category = "")
        database.recurringRuleDao().upsert(rule)
        database.transactionDao().insert(
            TransactionEntity(
                id = "cancel-charge",
                accountId = "savings",
                amountCents = rule.amountCents,
                type = rule.type,
                category = TransactionCategory.UNCATEGORIZED.name,
                occurredAt = dueAt
            )
        )

        repository.linkToRecurringRule("cancel-charge", rule.id)
        val advanced = database.recurringRuleDao().findById(rule.id)!!.nextRunAt
        assertTrue(advanced > dueAt)
        repository.linkToRecurringRule("cancel-charge", null)

        assertEquals(null, database.transactionDao().findById("cancel-charge")?.recurringRuleId)
        assertEquals(dueAt, database.recurringRuleDao().findById(rule.id)!!.nextRunAt)
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
        seedCash(88_000L)

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
            channel = "支付宝",
            orderPlatform = "美团"
        )

        val saved = database.transactionDao().findById("editable-channel")
        assertEquals("支付宝", saved?.channel)
        assertEquals("美团", saved?.orderPlatform)

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
            channel = null,
            orderPlatform = null
        )

        val cleared = database.transactionDao().findById("editable-channel")
        assertEquals(null, cleared?.channel)
        assertEquals(null, cleared?.orderPlatform)
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
            .listFiles { file -> file.name.startsWith("manual_") && file.name.endsWith(".akbackup") }
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
            .listFiles { file -> file.name.endsWith(".akbackup") }
            .orEmpty()
            .single()
        assertFalse(repository.restoreFromPicked(Uri.fromFile(backup), "654321"))
        assertEquals("current-ledger", database.transactionDao().all().single().id)
    }

    @Test
    fun singleFileBackupRestoresDatabaseAndPreferencesByReplacement() = runBlocking {
        database.transactionDao().insert(sampleExpense("from-backup"))
        repository.setBackupPin("123456")
        prefs.edit().putString("theme_key", "light-green").commit()
        assertTrue(repository.backupNow(manual = true))
        val backupDir = java.io.File(context.filesDir, "backups/manual")
        val backup = backupDir.listFiles().orEmpty().single()
        assertTrue(backup.name.endsWith(".akbackup"))

        database.transactionDao().insert(sampleExpense("current-only"))
        prefs.edit().putString("theme_key", "dragon-nest").commit()
        assertTrue(repository.restoreFromPicked(Uri.fromFile(backup), "123456"))

        database = Room.databaseBuilder(context, AssetsKingDatabase::class.java, "assets-king.db")
            .allowMainThreadQueries()
            .build()
        assertEquals(listOf("from-backup"), database.transactionDao().all().map { it.id })
        assertEquals("light-green", prefs.getString("theme_key", null))
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
            channel = null,
            orderPlatform = null
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
            channel = null,
            orderPlatform = null
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
            channel = null,
            orderPlatform = null
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
    fun restoringTransferFailsWhenItsFeeWasPermanentlyDeleted() = runBlocking {
        val notification = pendingNotification("transfer-fee-missing")
        database.rawNotificationDao().insert(notification)
        seedCash(32_542L)

        repository.confirmTransferFromNotification(
            notificationId = notification.id,
            fromAccountId = "cash",
            toAccountId = "savings",
            amountCents = 32_509L,
            feeCents = 33L,
            note = "微信零钱全部提现"
        )

        val transferId = database.transferDao().all().single().id
        val feeId = database.transactionDao().all().single().id

        repository.deleteTransfer(transferId)
        database.transactionDao().deleteById(feeId)

        val failure = runCatching { repository.restoreTransferFromTrash(transferId) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("关联手续费已被永久删除，不能恢复这条划转", failure?.message)
        assertTrue(database.transferDao().findIncludingDeleted(transferId)?.deletedAt != null)
        assertTrue(database.transactionDao().findIncludingDeleted(feeId) == null)
    }

    @Test
    fun restoringTransferFailsWhenItsFeeWasRestoredSeparately() = runBlocking {
        val notification = pendingNotification("transfer-fee-restored")
        database.rawNotificationDao().insert(notification)
        seedCash(32_542L)

        repository.confirmTransferFromNotification(
            notificationId = notification.id,
            fromAccountId = "cash",
            toAccountId = "savings",
            amountCents = 32_509L,
            feeCents = 33L,
            note = "微信零钱全部提现"
        )

        val transferId = database.transferDao().all().single().id
        val feeId = database.transactionDao().all().single().id

        repository.deleteTransfer(transferId)
        repository.restoreTransactionFromTrash(feeId)

        val failure = runCatching { repository.restoreTransferFromTrash(transferId) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("关联手续费已单独恢复，请先重新删除手续费后再恢复划转", failure?.message)
        assertTrue(database.transferDao().findIncludingDeleted(transferId)?.deletedAt != null)
        assertEquals(feeId, database.transactionDao().findById(feeId)?.id)
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

    @Test
    fun openingLendingPlanCreatesReceivableWithoutInventingCashMovement() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "历史借款",
                borrowerName = "张三",
                principalCents = 500_000L,
                expectedInterestCents = 20_000L,
                startDateEpochDay = 20_000L
            )
        )

        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        assertEquals(LendingOriginType.OPENING_BALANCE, plan.originType)
        assertEquals(LendingPlanStatus.ACTIVE, plan.status)
        assertEquals(500_000L, plan.remainingPrincipalCents)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(500_000L, database.accountDao().find(plan.receivableAccountId)?.balanceCents)
        assertTrue(database.transactionDao().all().isEmpty())
        assertTrue(database.transferDao().all().isEmpty())
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun lendingDisbursementNotificationIsAtomicIdempotentAndLeavesTombstoneAfterDeletion() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "通知借出",
                borrowerName = "测试借款人",
                principalCents = 300_000L,
                startDateEpochDay = 20_000L,
                originType = LendingOriginType.PENDING_DISBURSEMENT
            )
        )
        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        val notification = pendingNotification("lending-disbursement-notification").copy(
            postedAt = plan.ledgerBaselineAt + 1L
        )
        database.rawNotificationDao().insert(notification)
        seedCash(300_000L)

        repeat(2) {
            repository.confirmLendingDisbursementNotification(
                notificationId = notification.id,
                cashAccountId = "cash",
                planId = planId,
                amountCents = 300_000L,
                note = "通知确认"
            )
        }

        assertEquals(1, database.transferDao().all().size)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(300_000L, database.accountDao().find(plan.receivableAccountId)?.balanceCents)
        assertEquals(LendingPlanStatus.ACTIVE, database.lendingPlanDao().findById(planId)?.status)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)

        val transferId = database.transferDao().all().single().id
        repository.deleteTransfer(transferId)
        assertEquals("IGNORED", database.rawNotificationDao().findById(notification.id)?.status)
        assertEquals(0, database.transferDao().all().size)
        assertEquals(300_000L, database.accountDao().find("cash")?.balanceCents)

        repository.confirmLendingDisbursementNotification(
            notificationId = notification.id,
            cashAccountId = "cash",
            planId = planId,
            amountCents = 300_000L,
            note = "不应复活"
        )
        assertEquals(0, database.transferDao().all().size)
        assertEquals(300_000L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun lendingRepaymentNotificationIsAtomicIdempotentAndRejectsMismatchedSplit() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "通知收回",
                borrowerName = "测试借款人",
                principalCents = 100_000L,
                startDateEpochDay = 20_000L
            )
        )
        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        val notification = pendingNotification("lending-repayment-notification").copy(
            postedAt = plan.ledgerBaselineAt + 1L
        )
        database.rawNotificationDao().insert(notification)

        val mismatch = runCatching {
            repository.confirmLendingRepaymentNotification(
                notificationId = notification.id,
                cashAccountId = "cash",
                planId = planId,
                totalCents = 32_000L,
                principalCents = 30_000L,
                interestCents = 1_000L,
                note = "拆分错误"
            )
        }.exceptionOrNull()
        assertTrue(mismatch is IllegalArgumentException)
        assertEquals("PENDING_CONFIRMATION", database.rawNotificationDao().findById(notification.id)?.status)
        assertTrue(database.transferDao().all().isEmpty())
        assertTrue(database.transactionDao().all().isEmpty())

        repeat(2) {
            repository.confirmLendingRepaymentNotification(
                notificationId = notification.id,
                cashAccountId = "cash",
                planId = planId,
                totalCents = 32_000L,
                principalCents = 30_000L,
                interestCents = 2_000L,
                note = "通知确认"
            )
        }

        assertEquals(1, database.transferDao().all().size)
        assertEquals(1, database.transactionDao().all().size)
        assertEquals(32_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(70_000L, database.lendingPlanDao().findById(planId)?.remainingPrincipalCents)
        assertEquals(2_000L, database.lendingPlanDao().findById(planId)?.receivedInterestCents)
        assertEquals("LINKED", database.rawNotificationDao().findById(notification.id)?.status)
    }

    @Test
    fun confirmedIncomeCanBeLinkedToLendingInterestAndRestored() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft("补挂利息", "测试借款人", 100_000L, startDateEpochDay = 20_000L)
        )
        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        val txId = repository.addTransaction(
            accountId = "cash",
            amountCents = 2_000L,
            type = TransactionType.INCOME,
            category = "红包赠与",
            merchant = "测试借款人",
            note = "已确认到账",
            occurredAt = plan.ledgerBaselineAt + 1L
        )

        repository.linkTransactionToLendingInterest(
            id = txId,
            planId = planId,
            amountCents = 2_000L,
            merchant = "测试借款人",
            note = "补挂利息",
            accountId = "cash",
            occurredAt = plan.ledgerBaselineAt + 1L,
            channel = null
        )
        repository.linkTransactionToLendingInterest(
            id = txId,
            planId = planId,
            amountCents = 2_000L,
            merchant = "测试借款人",
            note = "幂等补挂",
            accountId = "cash",
            occurredAt = plan.ledgerBaselineAt + 1L,
            channel = null
        )

        assertEquals(planId, database.transactionDao().findById(txId)?.lendingPlanId)
        assertEquals("利息收益", database.transactionDao().findById(txId)?.category)
        assertEquals(2_000L, database.lendingPlanDao().findById(planId)?.receivedInterestCents)
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)

        repository.deleteTransaction(txId)
        assertEquals(0L, database.lendingPlanDao().findById(planId)?.receivedInterestCents)
        repository.restoreTransactionFromTrash(txId)
        assertEquals(2_000L, database.lendingPlanDao().findById(planId)?.receivedInterestCents)
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun confirmedPrincipalAndInterestIncomeConvertsToTransferWithoutChangingCashNet() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft("转换收回", "测试借款人", 100_000L, startDateEpochDay = 20_000L)
        )
        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        val txId = repository.addTransaction(
            accountId = "cash",
            amountCents = 32_000L,
            type = TransactionType.INCOME,
            category = "其他收入",
            merchant = "测试借款人",
            note = "本金和利息一起到账",
            occurredAt = plan.ledgerBaselineAt + 1L
        )
        assertEquals(32_000L, database.accountDao().find("cash")?.balanceCents)

        repository.convertIncomeToLendingRepayment(
            id = txId,
            planId = planId,
            totalCents = 32_000L,
            principalCents = 30_000L,
            interestCents = 2_000L,
            note = "拆分收回",
            accountId = "cash",
            occurredAt = plan.ledgerBaselineAt + 1L,
            channel = null
        )

        val tx = requireNotNull(database.transactionDao().findById(txId))
        val transfer = database.transferDao().all().single()
        val converted = requireNotNull(database.lendingPlanDao().findById(planId))
        assertEquals(TransactionType.INCOME.name, tx.type)
        assertEquals(2_000L, tx.amountCents)
        assertEquals(planId, tx.lendingPlanId)
        assertEquals(LendingTransferRole.PRINCIPAL_REPAYMENT, transfer.lendingRole)
        assertEquals(30_000L, transfer.amountCents)
        assertEquals(32_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(70_000L, database.accountDao().find(converted.receivableAccountId)?.balanceCents)
        assertEquals(70_000L, converted.remainingPrincipalCents)
        assertEquals(2_000L, converted.receivedInterestCents)
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)

        val duplicate = runCatching {
            repository.convertIncomeToLendingRepayment(
                id = txId,
                planId = planId,
                totalCents = 32_000L,
                principalCents = 30_000L,
                interestCents = 2_000L,
                note = "禁止重复转换",
                accountId = "cash",
                occurredAt = plan.ledgerBaselineAt + 1L,
                channel = null
            )
        }.exceptionOrNull()
        assertTrue(duplicate is IllegalArgumentException)
        assertEquals(1, database.transferDao().all().size)
    }

    @Test
    fun confirmedPrincipalOnlyIncomeConvertsWithoutLeavingZeroAmountIncome() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft("纯本金转换", "测试借款人", 100_000L, startDateEpochDay = 20_000L)
        )
        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        val txId = repository.addTransaction(
            accountId = "cash",
            amountCents = 30_000L,
            type = TransactionType.INCOME,
            category = "其他收入",
            merchant = "测试借款人",
            note = "只有本金到账",
            occurredAt = plan.ledgerBaselineAt + 1L
        )

        repository.convertIncomeToLendingRepayment(
            id = txId,
            planId = planId,
            totalCents = 30_000L,
            principalCents = 30_000L,
            interestCents = 0L,
            note = "纯本金收回",
            accountId = "cash",
            occurredAt = plan.ledgerBaselineAt + 1L,
            channel = null
        )

        assertEquals(null, database.transactionDao().findIncludingDeleted(txId))
        assertEquals(1, database.transferDao().all().size)
        assertEquals(30_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(70_000L, database.lendingPlanDao().findById(planId)?.remainingPrincipalCents)
        assertEquals(0L, database.lendingPlanDao().findById(planId)?.receivedInterestCents)
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun pendingLendingPlanActivatesOnlyThroughARealDisbursementTransfer() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "待借出",
                borrowerName = "李四",
                principalCents = 300_000L,
                startDateEpochDay = 20_000L,
                originType = LendingOriginType.PENDING_DISBURSEMENT
            )
        )
        val pending = requireNotNull(database.lendingPlanDao().findById(planId))
        assertEquals(0L, database.accountDao().find(pending.receivableAccountId)?.balanceCents)
        assertEquals(LendingPlanStatus.PENDING_DISBURSEMENT, pending.status)
        seedCash(300_000L)

        val transferId = repository.addLendingDisbursement(
            cashAccountId = "cash",
            planId = planId,
            amountCents = 300_000L,
            note = "实际转出",
            occurredAt = pending.ledgerBaselineAt + 1L
        )

        val active = requireNotNull(database.lendingPlanDao().findById(planId))
        val transfer = requireNotNull(database.transferDao().findById(transferId))
        assertEquals(LendingOriginType.DISBURSEMENT_TRANSFER, active.originType)
        assertEquals(LendingPlanStatus.ACTIVE, active.status)
        assertEquals(transferId, active.disbursementTransferId)
        assertEquals(LendingTransferRole.DISBURSEMENT, transfer.lendingRole)
        assertEquals(0L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(300_000L, database.accountDao().find(active.receivableAccountId)?.balanceCents)
        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun pendingLendingPlanCanBeDeletedWithoutAFlow() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "待删除计划",
                borrowerName = "测试借款人",
                principalCents = 100_000L,
                startDateEpochDay = 20_000L,
                originType = LendingOriginType.PENDING_DISBURSEMENT
            )
        )
        val accountId = requireNotNull(database.lendingPlanDao().findById(planId)).receivableAccountId

        repository.deleteLendingPlan(planId)

        assertEquals(null, database.lendingPlanDao().findById(planId))
        assertEquals(null, database.accountDao().find(accountId))
        assertTrue(database.balanceCheckpointDao().allFor(accountId).isEmpty())
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun openingLendingPlanCanBeDeletedWithoutInventingCashRestatement() = runBlocking {
        seedCash(250_000L)
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "期初测试应收",
                borrowerName = "测试借款人",
                principalCents = 100_000L,
                startDateEpochDay = 20_000L,
                originType = LendingOriginType.OPENING_BALANCE
            )
        )
        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        assertEquals(100_000L, database.accountDao().find(plan.receivableAccountId)?.balanceCents)

        repository.deleteLendingPlan(planId)

        assertEquals(null, database.lendingPlanDao().findById(planId))
        assertEquals(null, database.accountDao().find(plan.receivableAccountId))
        assertEquals(250_000L, database.accountDao().find("cash")?.balanceCents)
        assertTrue(database.balanceCheckpointDao().allFor(plan.receivableAccountId).isEmpty())
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun openingLendingPlanWithConfirmedHistoricalReceivableDifferenceCanBeDeleted() = runBlocking {
        seedCash(250_000L)
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "旧期初测试应收",
                borrowerName = "测试借款人",
                principalCents = 100_000L,
                startDateEpochDay = 20_000L,
                originType = LendingOriginType.OPENING_BALANCE
            )
        )
        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        val receivable = requireNotNull(database.accountDao().find(plan.receivableAccountId))
        database.accountDao().upsert(
            receivable.copy(balanceCents = 0L, balanceStatus = "CONFIRMED", lastCheckedAt = 1L)
        )

        repository.deleteLendingPlan(planId)

        assertEquals(null, database.lendingPlanDao().findById(planId))
        assertEquals(null, database.accountDao().find(plan.receivableAccountId))
        assertEquals(250_000L, database.accountDao().find("cash")?.balanceCents)
        assertTrue(database.balanceCheckpointDao().allFor(plan.receivableAccountId).isEmpty())
    }

    @Test
    fun lendingPlanDeletionRequiresItsFlowsRemovedButIgnoresUnrelatedCashActivity() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "可清理计划",
                borrowerName = "测试借款人",
                principalCents = 100_000L,
                startDateEpochDay = 20_000L,
                originType = LendingOriginType.PENDING_DISBURSEMENT
            )
        )
        val pending = requireNotNull(database.lendingPlanDao().findById(planId))
        seedCash(100_000L)
        val transferId = repository.addLendingDisbursement(
            cashAccountId = "cash",
            planId = planId,
            amountCents = 100_000L,
            note = null,
            occurredAt = pending.ledgerBaselineAt + 1L
        )
        repository.addTransaction(
            accountId = "cash",
            amountCents = 2_000L,
            type = TransactionType.INCOME,
            category = "其他收入",
            merchant = "无关收入",
            note = null,
            occurredAt = pending.ledgerBaselineAt + 2L
        )

        assertTrue(runCatching { repository.deleteLendingPlan(planId) }.exceptionOrNull() is IllegalArgumentException)

        repository.deleteTransfer(transferId)
        repository.deleteLendingPlan(planId)

        assertEquals(null, database.lendingPlanDao().findById(planId))
        assertEquals(null, database.accountDao().find(pending.receivableAccountId))
        assertEquals(null, database.transferDao().findIncludingDeleted(transferId))
        assertEquals(102_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("无关收入", database.transactionDao().all().single().merchant)
        assertTrue(database.balanceCheckpointDao().allFor(pending.receivableAccountId).isEmpty())
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun lendingRepaymentSplitsPrincipalTransferFromInterestIncome() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft(
                label = "本金利息拆分",
                borrowerName = "王五",
                principalCents = 100_000L,
                expectedInterestCents = 5_000L,
                startDateEpochDay = 20_000L
            )
        )
        val opening = requireNotNull(database.lendingPlanDao().findById(planId))

        repository.addLendingRepayment(
            cashAccountId = "cash",
            planId = planId,
            principalCents = 30_000L,
            interestCents = 2_000L,
            note = "首笔收回",
            occurredAt = opening.ledgerBaselineAt + 1L
        )

        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        val principalTransfer = database.transferDao().all().single()
        val interestIncome = database.transactionDao().all().single()
        assertEquals(LendingTransferRole.PRINCIPAL_REPAYMENT, principalTransfer.lendingRole)
        assertEquals(30_000L, principalTransfer.amountCents)
        assertEquals(TransactionType.INCOME.name, interestIncome.type)
        assertEquals("利息收益", interestIncome.category)
        assertEquals(2_000L, interestIncome.amountCents)
        assertEquals(70_000L, plan.remainingPrincipalCents)
        assertEquals(2_000L, plan.receivedInterestCents)
        assertEquals(32_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(70_000L, database.accountDao().find(plan.receivableAccountId)?.balanceCents)
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun lendingPrincipalAndInterestCanBeTrashedAndRestoredIndependently() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft("删除恢复", "赵六", 100_000L, startDateEpochDay = 20_000L)
        )
        val opening = requireNotNull(database.lendingPlanDao().findById(planId))
        repository.addLendingRepayment(
            "cash",
            planId,
            principalCents = 30_000L,
            interestCents = 2_000L,
            note = null,
            occurredAt = opening.ledgerBaselineAt + 1L
        )
        val principalTransferId = database.transferDao().all().single().id
        val interestTransactionId = database.transactionDao().all().single().id

        repository.deleteTransfer(principalTransferId)
        assertEquals(100_000L, database.lendingPlanDao().findById(planId)?.remainingPrincipalCents)
        assertEquals(2_000L, database.accountDao().find("cash")?.balanceCents)
        repository.restoreTransferFromTrash(principalTransferId)
        assertEquals(70_000L, database.lendingPlanDao().findById(planId)?.remainingPrincipalCents)
        assertEquals(32_000L, database.accountDao().find("cash")?.balanceCents)

        repository.deleteTransaction(interestTransactionId)
        assertEquals(0L, database.lendingPlanDao().findById(planId)?.receivedInterestCents)
        assertEquals(30_000L, database.accountDao().find("cash")?.balanceCents)
        repository.restoreTransactionFromTrash(interestTransactionId)
        assertEquals(2_000L, database.lendingPlanDao().findById(planId)?.receivedInterestCents)
        assertEquals(32_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)
    }

    @Test
    fun restoringDeletedLendingPrincipalRefusesToOverwriteLaterRepayments() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft("后续事件保护", "孙七", 100_000L, startDateEpochDay = 20_000L)
        )
        val opening = requireNotNull(database.lendingPlanDao().findById(planId))
        repository.addLendingRepayment(
            "cash",
            planId,
            principalCents = 30_000L,
            interestCents = 0L,
            note = null,
            occurredAt = opening.ledgerBaselineAt + 1L
        )
        val deletedTransferId = database.transferDao().all().single().id
        repository.deleteTransfer(deletedTransferId)

        val afterDelete = requireNotNull(database.lendingPlanDao().findById(planId))
        repository.addLendingRepayment(
            "cash",
            planId,
            principalCents = 20_000L,
            interestCents = 0L,
            note = null,
            occurredAt = afterDelete.ledgerBaselineAt + 2L
        )

        val failure = runCatching { repository.restoreTransferFromTrash(deletedTransferId) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(80_000L, database.lendingPlanDao().findById(planId)?.remainingPrincipalCents)
        assertTrue(database.transferDao().findIncludingDeleted(deletedTransferId)?.deletedAt != null)
        assertTrue(
            LedgerEvidenceAuditService(database).run().issues.any {
                it.code == "TRASH_SNAPSHOT_CONFLICT" && it.subjectId == deletedTransferId
            }
        )
    }

    @Test
    fun evidenceAuditDistinguishesWarningsFromBrokenBalances() = runBlocking {
        val planId = repository.createLendingPlan(
            LendingPlanDraft("审计状态", "周八", 100_000L, startDateEpochDay = 20_000L)
        )
        repository.addTransaction(
            accountId = "cash",
            amountCents = 1_000L,
            type = TransactionType.REFUND,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = null,
            note = "无法确认原消费"
        )

        val warning = LedgerEvidenceAuditService(database).run()
        assertEquals(EvidenceAuditStatus.WARNING, warning.status)
        assertTrue(warning.issues.any { it.code == "REFUND_UNLINKED" })

        val plan = requireNotNull(database.lendingPlanDao().findById(planId))
        database.lendingPlanDao().upsert(plan.copy(remainingPrincipalCents = 1L))
        val broken = LedgerEvidenceAuditService(database).run()
        assertEquals(EvidenceAuditStatus.BROKEN, broken.status)
        assertTrue(broken.issues.any { it.code == "LENDING_PRINCIPAL_MISMATCH" })
    }

    @Test
    fun evidenceAuditDetectsSameNotificationContentPostingTwoActiveEvents() = runBlocking {
        val postedAt = System.currentTimeMillis()
        val notificationKey = "0|com.android.mms.service|95555|null|1000"
        val first = pendingNotification("$notificationKey:$postedAt").copy(
            packageName = "com.android.mms.service",
            postedAt = postedAt,
            receivedAt = postedAt,
            contentFingerprint = "测试通知支付1250元商户全家便利店"
        )
        val repostedAt = postedAt + 3 * 24 * 3600_000L
        val second = pendingNotification("$notificationKey:$repostedAt").copy(
            packageName = "com.android.mms.service",
            postedAt = repostedAt,
            receivedAt = repostedAt,
            contentFingerprint = ContentFingerprint.of("测试通知", "支付12.50元 商户:全家便利店")
        )
        database.rawNotificationDao().insert(first)
        database.rawNotificationDao().insert(second)
        seedCash(1_250L)

        listOf(first, second).forEach { notification ->
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

        val report = LedgerEvidenceAuditService(database).run()
        assertEquals(2, database.transactionDao().all().size)
        assertTrue(report.issues.any { it.code == "DUPLICATE_ACTIVE_EVIDENCE" })
    }

    @Test
    fun evidenceAuditRequiresPurgedTombstoneForPhysicallyMissingSubject() = runBlocking {
        val transactionId = repository.addTransaction(
            accountId = "cash",
            amountCents = 1_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.UNCATEGORIZED.name,
            merchant = "永久删除墓碑",
            note = null
        )
        database.transactionDao().deleteById(transactionId)

        val missingTombstone = LedgerEvidenceAuditService(database).run()
        assertTrue(missingTombstone.issues.any { it.code == "SUBJECT_ORPHAN" && it.subjectId == transactionId })
        assertTrue(missingTombstone.issues.any { it.code == "PURGE_TOMBSTONE_MISSING" && it.subjectId == transactionId })

        database.ledgerLifecycleEventDao().insert(
            LedgerLifecycleEventEntity(
                id = UUID.randomUUID().toString(),
                subjectType = EvidenceSubjectType.TRANSACTION,
                subjectId = transactionId,
                action = EvidenceAction.PURGED,
                occurredAt = System.currentTimeMillis() + 1_000L
            )
        )
        val retainedTombstone = LedgerEvidenceAuditService(database).run()
        assertFalse(retainedTombstone.issues.any { it.code == "SUBJECT_ORPHAN" && it.subjectId == transactionId })
        assertFalse(retainedTombstone.issues.any { it.code == "PURGE_TOMBSTONE_MISSING" && it.subjectId == transactionId })
    }

    @Test
    fun evidenceAuditDetectsOrphanSubjectRelation() = runBlocking {
        database.ledgerEvidenceLinkDao().insertAll(
            listOf(
                LedgerEvidenceLinkEntity(
                    groupId = "orphan-group",
                    subjectType = EvidenceSubjectType.TRANSACTION,
                    subjectId = "missing-transaction",
                    subjectRole = "POSTED_EVENT",
                    sourceType = EvidenceSourceType.MANUAL_ENTRY,
                    sourceId = "manual:missing-transaction",
                    linkedAt = System.currentTimeMillis()
                )
            )
        )

        val report = LedgerEvidenceAuditService(database).run()
        assertTrue(report.issues.any { it.code == "SUBJECT_ORPHAN" && it.subjectId == "missing-transaction" })
    }

    @Test
    fun evidenceAuditDetectsMissingTrashDependencySnapshot() = runBlocking {
        seedCash(2_000L)
        val transferId = repository.addTransfer(
            fromAccountId = "cash",
            toAccountId = "savings",
            amountCents = 2_000L,
            note = "删除后损坏快照",
            occurredAt = System.currentTimeMillis()
        )
        repository.deleteTransfer(transferId)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE transfers SET trashContextJson = NULL WHERE id = ?",
            arrayOf(transferId)
        )

        val report = LedgerEvidenceAuditService(database).run()
        assertTrue(report.issues.any { it.code == "TRASH_SNAPSHOT_MISSING" && it.subjectId == transferId })
    }

    @Test
    fun evidenceAuditRecomputesCreditInstallmentFromSchedulesAndPaymentMatches() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(id = "audit-card", name = "审计信用卡", type = AccountType.CREDIT.name, balanceCents = 0L)
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity("opening-audit-card", "audit-card", 0L, Long.MIN_VALUE, "OPENING")
        )
        seedCash(10_000L)
        val expenseId = repository.addTransaction(
            accountId = "audit-card",
            amountCents = 10_000L,
            type = TransactionType.EXPENSE,
            category = TransactionCategory.SHOPPING.name,
            merchant = "分期原消费",
            note = null
        )
        val planId = repository.createCardInstallment(
            CreditCardInstallmentDraft(
                cardAccountId = "audit-card",
                label = "一期分期",
                allocations = listOf(CardInstallmentAllocationRequest(expenseId, 10_000L)),
                installmentCount = 1,
                firstDueDateEpochDay = 21_000L
            )
        )
        val transferId = repository.addTransfer(
            fromAccountId = "cash",
            toAccountId = "audit-card",
            amountCents = 10_000L,
            note = "真实信用卡还款",
            occurredAt = epochMillis(21_000L)
        )
        assertEquals(EvidenceAuditStatus.COMPLETE, LedgerEvidenceAuditService(database).run().status)

        val match = database.creditCardInstallmentPaymentMatchDao().findByTransfer(transferId).single()
        database.creditCardInstallmentPaymentMatchDao().upsertAll(listOf(match.copy(principalCents = 9_000L)))

        val report = LedgerEvidenceAuditService(database).run()
        assertTrue(report.issues.any { it.code == "CARD_SCHEDULE_PAYMENT_MISMATCH" && it.subjectId == planId })
        assertTrue(report.issues.any { it.code == "CARD_PLAN_PRINCIPAL_MISMATCH" && it.subjectId == planId })
    }

    private suspend fun insertCreditCardExpense(cardDebtCents: Long, expenseCents: Long) {
        seedCash(10_500L)
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

    private suspend fun insertAccount(id: String, type: String = AccountType.ASSET.name, balanceCents: Long = 0L) {
        database.accountDao().upsert(
            AccountEntity(id = id, name = id, type = type, balanceCents = balanceCents)
        )
        if (type == AccountType.ASSET.name) {
            database.balanceCheckpointDao().upsert(
                BalanceCheckpointEntity(
                    id = "opening-$id",
                    accountId = id,
                    balanceCents = balanceCents,
                    checkedAt = Long.MIN_VALUE,
                    source = "OPENING"
                )
            )
        }
    }

    private suspend fun seedCash(balanceCents: Long) {
        insertAccount("cash", balanceCents = balanceCents)
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
