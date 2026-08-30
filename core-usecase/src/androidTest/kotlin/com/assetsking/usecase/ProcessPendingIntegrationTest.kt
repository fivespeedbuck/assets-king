package com.assetsking.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assetsking.database.AccountEntity
import com.assetsking.database.AssetsKingDatabase
import com.assetsking.database.BalanceCheckpointEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.MerchantEntity
import com.assetsking.database.RawNotificationEntity
import com.assetsking.ledger.ContentFingerprint
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProcessPendingIntegrationTest {
    private lateinit var database: AssetsKingDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AssetsKingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LedgerRepository(
            context,
            database,
            context.getSharedPreferences("pending-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        )
        database.accountDao().upsert(
            AccountEntity(
                id = "cash",
                name = "宁波银行",
                type = AccountType.ASSET.name,
                balanceCents = 10_000L,
                cardTail = "1234"
            )
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "opening-cash",
                accountId = "cash",
                balanceCents = 10_000L,
                checkedAt = Long.MIN_VALUE,
                source = "OPENING"
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun learnedMerchantOnlyPrefillsAndNeverAutoPosts() = runBlocking {
        database.merchantDao().upsert(
            MerchantEntity(
                id = "全家便利店",
                learnedType = TransactionType.EXPENSE.name,
                learnedAccountId = "cash",
                learnedCategory = "餐饮"
            )
        )
        val notification = rawNotification(
            id = "learned",
            content = "支付12.50元 商户:全家便利店"
        )
        database.rawNotificationDao().insert(notification)

        assertEquals(1, ProcessPendingUseCase(repository).invoke())

        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals("PENDING_CONFIRMATION", database.rawNotificationDao().findById(notification.id)?.status)
        assertEquals(10_000L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun reportedBankBalanceDoesNotChangeAccountBeforeConfirmation() = runBlocking {
        val notification = rawNotification(
            id = "bank-balance",
            content = "【宁波银行】您尾号1234账户支出人民币12.50，余额87.50元。"
        )
        database.rawNotificationDao().insert(notification)

        ProcessPendingUseCase(repository).invoke()

        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals(10_000L, database.accountDao().find("cash")?.balanceCents)
        assertEquals("PENDING_CONFIRMATION", database.rawNotificationDao().findById(notification.id)?.status)
    }

    @Test
    fun ignoredEvidenceRemainsAPermanentTombstoneAfterEightDays() = runBlocking {
        val oldPostedAt = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1_000
        val content = "支付12.50元 商户:全家便利店"
        val fingerprint = ContentFingerprint.of("95574", content)
        database.rawNotificationDao().insert(
            rawNotification("ignored-old", content).copy(
                postedAt = oldPostedAt,
                receivedAt = oldPostedAt,
                status = "IGNORED",
                contentFingerprint = fingerprint
            )
        )
        database.rawNotificationDao().insert(
            rawNotification("rescan-new-id", content).copy(
                postedAt = oldPostedAt,
                contentFingerprint = fingerprint
            )
        )

        assertEquals(0, ProcessPendingUseCase(repository).invoke())

        assertTrue(database.transactionDao().all().isEmpty())
        assertEquals("IGNORED", database.rawNotificationDao().findById("rescan-new-id")?.status)
    }

    @Test
    fun smsReceiverAndRescanUseOneStableDatabaseIdentity() = runBlocking {
        val postedAt = System.currentTimeMillis()
        val content = "【招商银行】您账户3683于08月25日17:47快捷支付35.00元，余额3138.74"

        repository.saveRawNotification(
            rawNotification("sms:95555:$postedAt", content).copy(postedAt = postedAt)
        )
        repository.saveRawNotification(
            rawNotification("sms:rescan:95555:$postedAt", content).copy(postedAt = postedAt),
            updateLastReceived = false
        )

        assertEquals(1, database.rawNotificationDao().countAll())
    }

    @Test
    fun existingPendingSmsDuplicateIsCollapsedAgainstIgnoredTombstone() = runBlocking {
        val postedAt = System.currentTimeMillis()
        val content = "【招商银行】您账户3683于08月25日17:47快捷支付35.00元，余额3138.74"
        val fingerprint = ContentFingerprint.of("95555", content)
        database.rawNotificationDao().insert(
            rawNotification("sms:95555:$postedAt", content).copy(
                postedAt = postedAt,
                status = "IGNORED",
                contentFingerprint = fingerprint
            )
        )
        database.rawNotificationDao().insert(
            rawNotification("sms:rescan:95555:$postedAt", content).copy(
                postedAt = postedAt,
                status = "PENDING_CONFIRMATION",
                contentFingerprint = fingerprint
            )
        )

        assertEquals(0, ProcessPendingUseCase(repository).invoke())

        assertEquals(
            "IGNORED",
            database.rawNotificationDao().findById("sms:rescan:95555:$postedAt")?.status
        )
        assertEquals(0, database.rawNotificationDao().countPendingConfirmation())
    }

    @Test
    fun existingPendingSmsDuplicatesCollapseToOneCandidate() = runBlocking {
        val postedAt = System.currentTimeMillis()
        val content = "【招商银行】您账户3683于08月25日17:47快捷支付35.00元，余额3138.74"
        val fingerprint = ContentFingerprint.of("95555", content)
        listOf(
            "sms:95555:$postedAt",
            "sms:rescan:95555:$postedAt"
        ).forEach { id ->
            database.rawNotificationDao().insert(
                rawNotification(id, content).copy(
                    postedAt = postedAt,
                    status = "PENDING_CONFIRMATION",
                    contentFingerprint = fingerprint
                )
            )
        }

        assertEquals(0, ProcessPendingUseCase(repository).invoke())

        assertEquals(1, database.rawNotificationDao().countPendingConfirmation())
    }

    @Test
    fun fullWechatMeituanRefundKeepsExpenseAndRefundWithAllEvidence() = runBlocking {
        val evidence = listOf(
            rawNotification("wechat-stream:1000", "[1条]微信支付: 已支付¥14.60").copy(
                packageName = "com.tencent.mm",
                sourceLabel = "微信",
                title = "微信支付",
                postedAt = 1_000L,
                receivedAt = 1_000L
            ),
            rawNotification("meituan-pay", "您的美团订单已支付成功，点击查看详情>").copy(
                packageName = "com.sankuai.meituan",
                sourceLabel = "美团",
                title = "您已成功付款14.60元",
                postedAt = 1_100L,
                receivedAt = 1_100L
            ),
            rawNotification("wechat-stream:2000", "[2条]微信支付: 退款到账通知").copy(
                packageName = "com.tencent.mm",
                sourceLabel = "微信",
                title = "微信支付",
                postedAt = 2_000L,
                receivedAt = 2_000L
            ),
            rawNotification("meituan-refund", "您有一笔14.60元的退款，点击查看详情！").copy(
                packageName = "com.sankuai.meituan",
                sourceLabel = "美团",
                title = "退款通知",
                postedAt = 2_100L,
                receivedAt = 2_100L
            )
        )
        evidence.forEach { database.rawNotificationDao().insert(it) }

        assertEquals(2, ProcessPendingUseCase(repository).invoke())
        assertEquals(2, database.rawNotificationDao().countPendingConfirmation())

        repository.confirmNotification(
            notificationId = "wechat-stream:1000",
            accountId = "cash",
            amountCents = 1_460L,
            type = TransactionType.EXPENSE,
            category = "餐饮",
            merchant = "美团",
            note = null,
            channel = "微信"
        )
        val expense = database.transactionDao().all().single()
        repository.confirmNotification(
            // 有明确金额与商户语义的美团退款作为 keeper；微信无金额退款只补充证据。
            notificationId = "meituan-refund",
            accountId = "cash",
            amountCents = 1_460L,
            type = TransactionType.REFUND,
            category = "餐饮",
            merchant = "美团",
            note = null,
            channel = "微信支付",
            refundOfId = expense.id
        )

        val transactions = database.transactionDao().all()
        assertEquals(setOf(TransactionType.EXPENSE.name, TransactionType.REFUND.name), transactions.map { it.type }.toSet())
        assertEquals(expense.id, transactions.single { it.type == TransactionType.REFUND.name }.refundOfId)
        assertTrue(evidence.all { database.rawNotificationDao().findById(it.id)?.status == "LINKED" })
        transactions.forEach { transaction ->
            assertEquals(
                2,
                database.ledgerEvidenceLinkDao().findBySubject("TRANSACTION", transaction.id)
                    .count { it.sourceType == "RAW_NOTIFICATION" }
            )
        }
        assertEquals(10_000L, database.accountDao().find("cash")?.balanceCents)
    }

    @Test
    fun guangfaStatementUpdatesBillStateOnceAndNeverCreatesATransaction() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(
                id = "cgb",
                name = "广发信用卡",
                type = AccountType.CREDIT.name,
                balanceCents = 0L,
                cardTail = "3304",
                statementDay = 26,
                dueDay = 10,
                statementOriginalDueCents = 99L
            )
        )
        val content = "【广发银行】您尾号3304广发信用卡06月人民币账单金额1,570.44元，最低还款116.00元，还款到期07月15日。点 n.95508.com/x 即可极速办理账单分期，以批核为准。"
        val postedAt = System.currentTimeMillis()
        val fingerprint = ContentFingerprint.of("95508", content)
        database.rawNotificationDao().insert(
            rawNotification("statement-1", content).copy(postedAt = postedAt, contentFingerprint = fingerprint)
        )
        database.rawNotificationDao().insert(
            rawNotification("statement-2", content).copy(postedAt = postedAt + 1_000L, contentFingerprint = fingerprint)
        )

        assertEquals(0, ProcessPendingUseCase(repository).invoke())

        val account = requireNotNull(database.accountDao().find("cgb"))
        assertEquals(157_044L, account.statementOriginalDueCents)
        assertEquals(26, account.statementDay)
        assertEquals(15, account.dueDay)
        assertTrue(database.transactionDao().all().isEmpty())
        val statements = listOfNotNull(
            database.rawNotificationDao().findById("statement-1"),
            database.rawNotificationDao().findById("statement-2")
        )
        assertTrue(statements.all { it.status == "IGNORED" })
        // receivedAt 可能落在同一毫秒，Room 对同值行不保证次序；只要求两份中恰有一份
        // 记录“内容相同”的归并证据，不把哪一个 id 被保留写死成时序契约。
        assertTrue(statements.count { it.processingNote?.contains("内容相同") == true } == 1)
    }

    private fun rawNotification(id: String, content: String) = RawNotificationEntity(
        id = id,
        packageName = "sms",
        sourceLabel = "95574",
        title = "95574",
        content = content,
        postedAt = System.currentTimeMillis(),
        receivedAt = System.currentTimeMillis(),
        status = "NEW"
    )
}
