package com.assetsking.database

import android.content.Context
import androidx.room.Database
import com.assetsking.ledger.BalanceMath
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class, TransactionEntity::class, TransferEntity::class,
        RawNotificationEntity::class, BudgetEntity::class, LoanPlanEntity::class,
        RecurringRuleEntity::class, SnapshotEntity::class,
        CreditCardInstallmentEntity::class,
        CreditCardInstallmentAllocationEntity::class,
        CreditCardInstallmentScheduleEntity::class,
        CreditCardInstallmentPaymentMatchEntity::class,
        CreditCardInstallmentAuditEventEntity::class,
        WindfallEntity::class, MonthDebtAnchorEntity::class,
        BalanceCheckpointEntity::class, BalanceAdjustmentEntity::class,
        ReimbursementLinkEntity::class,
        CategoryEntity::class, MerchantEntity::class
    ],
    version = 24,
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
    abstract fun creditCardInstallmentDao(): CreditCardInstallmentDao
    abstract fun creditCardInstallmentAllocationDao(): CreditCardInstallmentAllocationDao
    abstract fun creditCardInstallmentScheduleDao(): CreditCardInstallmentScheduleDao
    abstract fun creditCardInstallmentPaymentMatchDao(): CreditCardInstallmentPaymentMatchDao
    abstract fun creditCardInstallmentAuditDao(): CreditCardInstallmentAuditDao
    abstract fun windfallDao(): WindfallDao
    abstract fun monthDebtAnchorDao(): MonthDebtAnchorDao
    abstract fun balanceCheckpointDao(): BalanceCheckpointDao
    abstract fun balanceAdjustmentDao(): BalanceAdjustmentDao
    abstract fun reimbursementLinkDao(): ReimbursementLinkDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantDao(): MerchantDao

    companion object {
        @Volatile private var instance: AssetsKingDatabase? = null

        /**
         * v9→v10 是最后一次允许清库的版本：DROP 全部旧表（v9 数据为测试数据）后
         * 按新 schema 重建（DDL 与 Room 生成的 createAllTables 完全一致，迁移后校验才能通过）。
         * 从 v11 起必须手写 Migration——没有 destructive fallback，
         * 缺迁移会拒绝启动（抛异常），绝不可能静默清掉真实负债数据。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 删除 v9 全部旧表（goals 永久删除，其余重建）
                listOf(
                    "goals", "accounts", "transactions", "transfers", "raw_notifications",
                    "budgets", "loan_plans", "recurring_rules", "snapshots", "custom_categories"
                ).forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
                listOf(
                    "index_accounts_cardTail", "index_transactions_accountId", "index_transactions_occurredAt",
                    "index_transfers_fromAccountId", "index_transfers_toAccountId", "index_transfers_occurredAt",
                    "index_raw_notifications_packageName", "index_raw_notifications_postedAt", "index_raw_notifications_status",
                    "index_loan_plans_accountId", "index_snapshots_dateEpochDay"
                ).forEach { db.execSQL("DROP INDEX IF EXISTS `$it`") }

                // 2. 按新 schema 重建（复制自 Room 生成的 createAllTables，勿手改）
                db.execSQL("CREATE TABLE IF NOT EXISTS `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `balanceCents` INTEGER NOT NULL, `cardTail` TEXT, `balanceStatus` TEXT NOT NULL, `lastCheckedAt` INTEGER, `groupName` TEXT, `statementDay` INTEGER, `dueDay` INTEGER, `creditLimitCents` INTEGER NOT NULL, `statementOriginalDueCents` INTEGER NOT NULL, `pendingCents` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_accounts_cardTail` ON `accounts` (`cardTail`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `type` TEXT NOT NULL, `category` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `merchant` TEXT, `note` TEXT, `status` TEXT NOT NULL, `isReimbursable` INTEGER NOT NULL, `recurringRuleId` TEXT, `principalCents` INTEGER NOT NULL, `interestCents` INTEGER NOT NULL, `feeCents` INTEGER NOT NULL, `loanPlanId` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_occurredAt` ON `transactions` (`occurredAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `transfers` (`id` TEXT NOT NULL, `fromAccountId` TEXT NOT NULL, `toAccountId` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `occurredAt` INTEGER NOT NULL, `note` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_fromAccountId` ON `transfers` (`fromAccountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_toAccountId` ON `transfers` (`toAccountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_occurredAt` ON `transfers` (`occurredAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `raw_notifications` (`id` TEXT NOT NULL, `packageName` TEXT NOT NULL, `sourceLabel` TEXT, `title` TEXT, `content` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `processingNote` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_notifications_packageName` ON `raw_notifications` (`packageName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_notifications_postedAt` ON `raw_notifications` (`postedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_notifications_status` ON `raw_notifications` (`status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` TEXT NOT NULL, `category` TEXT NOT NULL, `monthlyLimitCents` INTEGER NOT NULL, `month` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `loan_plans` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `principalCents` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `repaymentMethod` TEXT NOT NULL, `installmentsJson` TEXT NOT NULL, `annualRateBps` INTEGER NOT NULL, `remainingPrincipalCents` INTEGER NOT NULL, `earlyRepaidCents` INTEGER NOT NULL, `repaymentDay` INTEGER, `status` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_plans_accountId` ON `loan_plans` (`accountId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `type` TEXT NOT NULL, `category` TEXT NOT NULL, `merchant` TEXT, `note` TEXT, `interval` TEXT NOT NULL, `nextRunAt` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `isSubscription` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `snapshots` (`id` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `totalAssets` INTEGER NOT NULL, `totalDebts` INTEGER NOT NULL, `netWorth` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_snapshots_dateEpochDay` ON `snapshots` (`dateEpochDay`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `custom_categories` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `credit_card_installments` (`id` TEXT NOT NULL, `cardAccountId` TEXT NOT NULL, `label` TEXT NOT NULL, `originalPrincipalCents` INTEGER NOT NULL, `remainingPrincipalCents` INTEGER NOT NULL, `monthlyPaymentCents` INTEGER NOT NULL, `feeCentsPerPeriod` INTEGER NOT NULL, `periodsRemaining` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installments_cardAccountId` ON `credit_card_installments` (`cardAccountId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `windfalls` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `expectedAmountCents` INTEGER NOT NULL, `expectedDateEpochDay` INTEGER NOT NULL, `plannedDebtPaymentCents` INTEGER NOT NULL, `status` TEXT NOT NULL, `receivedAmountCents` INTEGER NOT NULL, `receivedAtEpochDay` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_windfalls_status` ON `windfalls` (`status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `month_debt_anchors` (`yearMonth` TEXT NOT NULL, `totalDebtCents` INTEGER NOT NULL, PRIMARY KEY(`yearMonth`))")
            }
        }

        /**
         * v10→v11：新增 balance_checkpoints 表，并为每个账户回填「开户」检查点。
         * 开户检查点 = 当前余额 − 全部已确认事件增量，checkedAt = Long.MIN_VALUE 表示时间起点，
         * 这样任何银行短信检查点（真实时间戳）都排在它之后。只加表、不改旧数据，非破坏性。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `balance_checkpoints` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `balanceCents` INTEGER NOT NULL, `checkedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_balance_checkpoints_accountId` ON `balance_checkpoints` (`accountId`)")
                backfillOpeningCheckpoints(db)
            }
        }

        /** v20→v21：accounts 加 startDateEpochDay 列（新增账户启用日期，REQ 账户对账§17）。只加列，非破坏性。 */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN startDateEpochDay INTEGER")
            }
        }

        /** v21→v22：旧版无层级自定义分类已由一级/二级分类库完整替代。 */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS custom_categories")
            }
        }

        /**
         * v22→v23：信用卡既有消费事后分期。
         * 旧预览行没有原流水证据，只标 LEGACY_UNLINKED；绝不猜测关联、绝不改余额或流水。
         */
        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN installmentType TEXT NOT NULL DEFAULT 'POST_PURCHASE_INSTALLMENT'")
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN installmentCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN nextDueDateEpochDay INTEGER")
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN status TEXT NOT NULL DEFAULT 'LEGACY_UNLINKED'")
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN scheduleRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE credit_card_installments SET installmentCount = periodsRemaining WHERE installmentCount = 0")

                db.execSQL("CREATE TABLE IF NOT EXISTS `credit_card_installment_allocations` (`planId` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `allocatedPrincipalCents` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`planId`, `transactionId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_allocations_planId` ON `credit_card_installment_allocations` (`planId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_allocations_transactionId` ON `credit_card_installment_allocations` (`transactionId`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `credit_card_installment_schedules` (`id` TEXT NOT NULL, `planId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `number` INTEGER NOT NULL, `dueDateEpochDay` INTEGER NOT NULL, `principalDueCents` INTEGER NOT NULL, `expectedInterestCents` INTEGER NOT NULL, `expectedFeeCents` INTEGER NOT NULL, `expectedUnclassifiedChargeCents` INTEGER NOT NULL, `principalPaidCents` INTEGER NOT NULL, `interestPaidCents` INTEGER NOT NULL, `feePaidCents` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_schedules_planId` ON `credit_card_installment_schedules` (`planId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credit_card_installment_schedules_planId_revision_number` ON `credit_card_installment_schedules` (`planId`, `revision`, `number`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `credit_card_installment_payment_matches` (`transferId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `planId` TEXT NOT NULL, `paymentCents` INTEGER NOT NULL, `principalCents` INTEGER NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, PRIMARY KEY(`transferId`, `scheduleId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_payment_matches_transferId` ON `credit_card_installment_payment_matches` (`transferId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_payment_matches_scheduleId` ON `credit_card_installment_payment_matches` (`scheduleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_payment_matches_planId` ON `credit_card_installment_payment_matches` (`planId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_payment_matches_status` ON `credit_card_installment_payment_matches` (`status`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `credit_card_installment_audit_events` (`id` TEXT NOT NULL, `planId` TEXT NOT NULL, `eventType` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `source` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_audit_events_planId` ON `credit_card_installment_audit_events` (`planId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_installment_audit_events_occurredAt` ON `credit_card_installment_audit_events` (`occurredAt`)")
            }
        }

        /** v23→v24：账单分期记录账期起点；只加可空列，不触碰既有计划与流水。 */
        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE credit_card_installments ADD COLUMN statementCycleStartEpochDay INTEGER")
            }
        }

        /** v19→v20：categories 加 kind 列（收入分类库独立于消费分类，REQ 预期收入§4）。只加列，非破坏性。 */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN kind TEXT NOT NULL DEFAULT 'EXPENSE'")
            }
        }

        /** v18→v19：transactions 加 notificationId 列（删除通知流水时原通知回待确认箱，REQ 流水§9）。只加列，非破坏性。 */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN notificationId TEXT")
            }
        }

        /** v17→v18：transactions 加 channel 列（支付渠道与资金账户分开，REQ 流水§5）。只加列，非破坏性。 */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN channel TEXT")
            }
        }

        /** v16→v17：分类库与交易对象库（REQ 商户库/初始分类库）：categories、merchants 表 + transactions.necessity 列。非破坏性。 */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN necessity INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `shortName` TEXT NOT NULL, `parentId` TEXT, `iconKey` TEXT NOT NULL, `defaultNecessary` INTEGER, `sortOrder` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `isCustom` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_parentId` ON `categories` (`parentId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `merchants` (`id` TEXT NOT NULL, `aliasesJson` TEXT NOT NULL, `learnedType` TEXT, `learnedAccountId` TEXT, `learnedCategory` TEXT, PRIMARY KEY(`id`))")
            }
        }

        /** v15→v16：报销（REQ 报销§1-6）：transactions 加 reimbursedCents 列 + 新建 reimbursement_links。非破坏性。 */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN reimbursedCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reimbursement_links` (`reimbursementTxId` TEXT NOT NULL, `expenseTxId` TEXT NOT NULL, `coveredCents` INTEGER NOT NULL, PRIMARY KEY(`reimbursementTxId`, `expenseTxId`))")
            }
        }

        /** v14→v15：transactions 加 refundOfId 列（退款关联原消费，REQ 待确认交易类型 §6-8）。只加列，非破坏性。 */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN refundOfId TEXT")
            }
        }

        /** v13→v14：raw_notifications 加 contentFingerprint 列（内容指纹判重）。只加列，非破坏性。 */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE raw_notifications ADD COLUMN contentFingerprint TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v12→v13：accounts 加 archived 列（账户归档）。只加列，非破坏性。 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v11→v12：新增 balance_adjustments 表（余额调整记录）。只加表，非破坏性。 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `balance_adjustments` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `beforeCents` INTEGER NOT NULL, `afterCents` INTEGER NOT NULL, `diffCents` INTEGER NOT NULL, `reason` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_balance_adjustments_accountId` ON `balance_adjustments` (`accountId`)")
            }
        }

        private fun backfillOpeningCheckpoints(db: SupportSQLiteDatabase) {
            val accounts = db.query("SELECT id, type, balanceCents FROM accounts")
            val rows = mutableListOf<Triple<String, String, Long>>()
            while (accounts.moveToNext()) {
                rows.add(Triple(accounts.getString(0), accounts.getString(1), accounts.getLong(2)))
            }
            accounts.close()

            for ((id, type, balance) in rows) {
                val accountType = runCatching { AccountType.valueOf(type) }.getOrNull() ?: continue
                var deltaSum = 0L

                val tx = db.query("SELECT type, amountCents FROM transactions WHERE accountId = ?", arrayOf(id))
                while (tx.moveToNext()) {
                    val txType = tx.getString(0)
                    val amount = tx.getLong(1)
                    runCatching { TransactionType.valueOf(txType) }.getOrNull()?.let {
                        deltaSum += BalanceMath.transactionDelta(accountType, it, amount)
                    }
                }
                tx.close()

                val tf = db.query("SELECT fromAccountId, toAccountId, amountCents FROM transfers")
                while (tf.moveToNext()) {
                    val fromId = tf.getString(0)
                    val toId = tf.getString(1)
                    val amount = tf.getLong(2)
                    if (fromId == id) deltaSum += BalanceMath.transferOutDelta(accountType, amount)
                    if (toId == id) deltaSum += BalanceMath.transferInDelta(accountType, amount)
                }
                tf.close()

                val opening = balance - deltaSum
                db.execSQL(
                    "INSERT INTO balance_checkpoints (id, accountId, balanceCents, checkedAt, source) VALUES (?, ?, ?, ?, ?)",
                    arrayOf("opening_$id", id, opening, Long.MIN_VALUE, "OPENING")
                )
            }
        }

        fun get(context: Context): AssetsKingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AssetsKingDatabase::class.java,
                "assets-king.db"
            ).addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24).build().also { instance = it }
        }
    }
}
