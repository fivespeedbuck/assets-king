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
        assertEquals("IGNORED", database.rawNotificationDao().findById("statement-1")?.status)
        assertTrue(
            database.rawNotificationDao().findById("statement-2")?.processingNote
                ?.contains("内容相同") == true
        )
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
