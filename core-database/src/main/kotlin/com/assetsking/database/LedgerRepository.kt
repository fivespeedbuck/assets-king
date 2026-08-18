package com.assetsking.database

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.assetsking.ledger.BalanceCheckpoint
import com.assetsking.ledger.BalanceMath
import com.assetsking.ledger.ContentFingerprint
import com.assetsking.ledger.LedgerDelta
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.model.AccountType
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.Money
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.model.WindfallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class LearnedRule(
    val accountId: String,
    val type: String,
    val category: String
)

class LedgerRepository(
    private val database: AssetsKingDatabase,
    private val prefs: SharedPreferences
) {
    val accounts: Flow<List<AccountEntity>> = database.accountDao().observeAll()
    val transactions: Flow<List<TransactionEntity>> = database.transactionDao().observeAll()
    val transfers: Flow<List<TransferEntity>> = database.transferDao().observeAll()
    val unprocessedNotifications: Flow<Int> = database.rawNotificationDao().observeUnprocessedCount()
    val pendingNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("PENDING_CONFIRMATION")
    val linkedNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("LINKED")
    val ignoredNotifications: Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("IGNORED")
    private val _lastReceivedAt = MutableStateFlow(prefs.getLong("last_notification_received_at", 0L))
    val lastReceivedAt: Flow<Long> = _lastReceivedAt
    private val categorizer = RuleBasedCategorizer()

    suspend fun seedKnownAccounts() {
        database.accountDao().insertAll(
            listOf(
                AccountEntity("cmb", "招商银行", AccountType.ASSET.name, 0),
                AccountEntity("nbcb", "宁波银行", AccountType.ASSET.name, 0),
                AccountEntity("cgb", "广发信用卡", AccountType.CREDIT.name, 0),
                AccountEntity("huabei", "花呗", AccountType.CREDIT.name, 0)
            )
        )
    }

    fun categorize(merchant: String?, note: String? = null): TransactionCategory =
        categorizer.categorize(merchant, note)

    suspend fun saveRawNotification(notification: RawNotificationEntity, updateLastReceived: Boolean = true) {
        database.rawNotificationDao().insert(
            if (notification.contentFingerprint.isBlank())
                notification.copy(contentFingerprint = ContentFingerprint.of(notification.title, notification.content))
            else notification
        )
        // 金库「最近入库时间」：实时证据落库才刷新；补扫旧短信不刷新（避免被历史补回污染）
        if (updateLastReceived) {
            _lastReceivedAt.value = notification.receivedAt
            prefs.edit().putLong("last_notification_received_at", notification.receivedAt).apply()
        }
    }

    // ── 待确认通知防丢：最后通知过几笔（prefs），心跳/开机/重连时据此补发 ──
    fun lastNotifiedPendingCount(): Int = prefs.getInt("pending_notified_count", 0)

    fun markPendingNotified(count: Int) {
        prefs.edit().putInt("pending_notified_count", count).apply()
    }

    fun observeNewNotifications(): Flow<List<RawNotificationEntity>> =
        database.rawNotificationDao().observeByStatus("NEW")

    suspend fun updateNotificationStatus(id: String, status: String) {
        database.rawNotificationDao().updateStatus(id, status)
    }

    suspend fun updateNotificationNote(id: String, note: String) {
        database.rawNotificationDao().updateProcessingNote(id, note)
    }

    /**
     * 确认通知 → 创建交易 + 标记 LINKED，同一事务保证不会重复入账。
     * ponytail: 复用 addTransaction 的余额计算逻辑。
     */
    suspend fun confirmNotification(
        notificationId: String,
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            // 流水日期用通知到达时间，不用确认时刻：银行短信即时推，postedAt≈真实扣款时间；
            // 用户可能隔天才点确认，用确认时刻会把日期记错（真机实报：88 元短信 08:39 到、09:41 确认）
            val postedAt = database.rawNotificationDao().findById(notificationId)?.postedAt
                ?: System.currentTimeMillis()
            // 周期账单反向认领：规则可能先于真实扣款自动记过一笔（同账户+同类型+金额±15%+前后5天）。
            // 认到就不再造第二笔，只把通知标 LINKED —— 否则规则+短信把同一笔记两遍。
            // ponytail: 同账户同金额同周的两笔真实消费会被误认领，宁可漏记不要虚增。
            val ruleTx = database.transactionDao().findInRange(
                postedAt - 5L * 24 * 60 * 60 * 1000, postedAt + 5L * 24 * 60 * 60 * 1000
            ).firstOrNull {
                it.recurringRuleId != null &&
                    it.type == type.name &&
                    it.accountId == accountId &&
                    kotlin.math.abs(it.amountCents - amountCents) * 100 <= amountCents * 15
            }
            if (ruleTx == null) {
                // 确认即认领：金额±15%、应扣日±5天、同类型的周期账单，把流水挂到规则下。
                // 挂上后：账单页显示「本月已扣」；规则自动记账时会认领它而不重复造笔。
                // 不要求账户一致——短信说的是真实扣款账户，规则里的账户只是默认值。
                // ponytail: 同日同额的规则（如老爹/老妈意外险各85.26）会都匹配到第一条，显示层已知。
                val ruleId = database.recurringRuleDao().observeAll().first()
                    .firstOrNull {
                        it.type == type.name &&
                            kotlin.math.abs(it.amountCents - amountCents) * 100 <= amountCents * 15 &&
                            kotlin.math.abs(it.nextRunAt - postedAt) <= 5L * 24 * 60 * 60 * 1000
                    }?.id
                // 退款关联原消费（REQ 待确认交易类型 §6-8）：同账户、30 天内的支出中取金额
                // 最接近且 ≤ 退款额的（同额取最近）——冲减原消费的分类与必要性，不计入本月收入。
                // ponytail: 找不到原消费仍允许独立退款确认（§7），只不参与冲减。
                val refundOfId = if (type == TransactionType.REFUND) {
                    database.transactionDao().findInRange(
                        postedAt - 30L * 24 * 60 * 60 * 1000, postedAt
                    ).filter {
                        it.accountId == accountId &&
                            it.type == TransactionType.EXPENSE.name &&
                            it.amountCents <= amountCents
                    }.maxWithOrNull(compareBy({ it.amountCents }, { it.occurredAt }))?.id
                } else null
                addTransaction(
                    accountId, amountCents, type, category, merchant, note,
                    occurredAt = postedAt, recurringRuleId = ruleId, refundOfId = refundOfId
                )
            }
            database.rawNotificationDao().updateStatus(notificationId, "LINKED")
            // addTransaction 刚按增量改过余额；银行自报的余额是权威值，最后再盖一次。
            // 顺序不能反，否则增量会把对好的余额又推歪。
            if (bankBalanceCents != null && bankCardTail != null) {
                applyBankBalance(accountId, bankCardTail, bankBalanceCents, postedAt)
            }
        }
    }

    /**
     * 用银行通知自报的余额对账：记录权威检查点并重算余额，不再直接覆盖
     * （覆盖会跟随后确认流水的增量二次扣减）。
     *
     * 只认储蓄卡（ASSET）：信用卡短信报的是可用额度、花呗报的是账单，都不是欠款余额，
     * 拿来盖会把负债算错。必须尾号对得上才动 —— 不知道是哪张卡的余额一律不用。
     *
     * 差额核对（REQ 账户对账 §4-5）：上次权威余额 + 期间已确认及待确认变化 = 本次银行余额。
     * 不一致仍照记银行检查点（银行是权威），但标 DISCREPANCY 并记一条可追溯调整记录，
     * 不静默吞差额。
     */
    private suspend fun applyBankBalance(
        accountId: String,
        cardTail: String,
        balanceCents: Long,
        checkedAt: Long,
        pendingDeltas: List<LedgerDelta> = emptyList(),
        thisEvidenceDeltaCents: Long? = null
    ) {
        val account = database.accountDao().find(accountId) ?: return
        if (account.cardTail != cardTail) return
        val accountType = AccountType.valueOf(account.type)
        if (accountType != AccountType.ASSET) return

        var status = "CONFIRMED"
        val prev = database.balanceCheckpointDao().latestFor(accountId)
        if (prev != null) {
            val allDeltas = accountDeltas(accountId, accountType) + pendingDeltas +
                (thisEvidenceDeltaCents?.let { listOf(LedgerDelta(checkedAt, it)) } ?: emptyList())
            val expected = BalanceMath.expectedBalance(
                BalanceCheckpoint(prev.balanceCents, prev.checkedAt),
                allDeltas,
                checkedAt
            )
            val diff = balanceCents - expected
            if (diff != 0L) {
                status = "DISCREPANCY"
                // 幂等：同一检查点重放（判重/补扫再触发）不会重复记调整
                database.balanceAdjustmentDao().upsert(
                    BalanceAdjustmentEntity(
                        id = "discrepancy_${accountId}_$checkedAt",
                        accountId = accountId,
                        beforeCents = expected,
                        afterCents = balanceCents,
                        diffCents = diff,
                        reason = "自动差额核对：账面应有与银行余额不符",
                        occurredAt = checkedAt
                    )
                )
            }
        }
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "bank_${accountId}_$checkedAt",
                accountId = accountId,
                balanceCents = balanceCents,
                checkedAt = checkedAt,
                source = "BANK_SMS"
            )
        )
        database.accountDao().upsert(account.copy(balanceStatus = status, lastCheckedAt = checkedAt))
        recomputeBalance(accountId)
    }

    /** 账户的全部已确认事件增量（流水 + 转账），余额重算与差额核对共用。 */
    private suspend fun accountDeltas(accountId: String, accountType: AccountType): List<LedgerDelta> {
        val txDeltas = database.transactionDao().all()
            .filter { it.accountId == accountId }
            .map {
                LedgerDelta(
                    occurredAt = it.occurredAt,
                    deltaCents = BalanceMath.transactionDelta(accountType, TransactionType.valueOf(it.type), it.amountCents)
                )
            }
        val transferDeltas = database.transferDao().all().flatMap { tf ->
            buildList {
                if (tf.fromAccountId == accountId) add(LedgerDelta(tf.occurredAt, BalanceMath.transferOutDelta(accountType, tf.amountCents)))
                if (tf.toAccountId == accountId) add(LedgerDelta(tf.occurredAt, BalanceMath.transferInDelta(accountType, tf.amountCents)))
            }
        }
        return txDeltas + transferDeltas
    }

    /**
     * 重算账户余额 = 最新检查点 + 其后已确认事件增量（决策 2 可信账务内核）。
     * 所有入账/删除/编辑/转账/对账路径统一「记事件 → 重算」，不再手工加减余额。
     */
    suspend fun recomputeBalance(accountId: String) {
        val account = database.accountDao().find(accountId) ?: return
        val checkpoint = database.balanceCheckpointDao().latestFor(accountId) ?: return
        val accountType = AccountType.valueOf(account.type)

        val newBalance = BalanceMath.balance(
            openingBalanceCents = 0L,
            checkpoint = BalanceCheckpoint(checkpoint.balanceCents, checkpoint.checkedAt),
            deltas = accountDeltas(accountId, accountType)
        )
        if (newBalance != account.balanceCents) {
            database.accountDao().upsert(account.copy(balanceCents = newBalance))
        }
    }

    /**
     * 通知一到就自动对账，不必等用户确认那笔流水。按尾号找账户，找不到就什么都不做。
     *
     * @param postedAt 通知的推送时间。补扫会把旧短信重新读一遍，旧余额不能覆盖新余额，
     *   所以比 lastCheckedAt 旧的直接丢弃。
     * @param pendingDeltas 可归属本账户的待确认证据增量（REQ 对账 §4 要求待确认变化也参与校验）
     * @param thisEvidenceDeltaCents 本条证据自身的增量（银行余额是扣款后的值，校验须包含它）
     * @return 是否真的对上了账
     */
    suspend fun reconcileFromNotification(
        cardTail: String,
        balanceCents: Long,
        postedAt: Long,
        pendingDeltas: List<LedgerDelta> = emptyList(),
        thisEvidenceDeltaCents: Long? = null
    ): Boolean {
        val candidates = database.accountDao().findByCardTail(cardTail, AccountType.ASSET.name)
        // 两张卡尾号相同就无法判断是哪张，宁可不对
        val account = candidates.singleOrNull() ?: return false
        if ((account.lastCheckedAt ?: 0L) >= postedAt) return false
        applyBankBalance(account.id, cardTail, balanceCents, postedAt, pendingDeltas, thisEvidenceDeltaCents)
        return true
    }

    suspend fun addAccount(
        name: String,
        type: AccountType,
        openingBalanceCents: Long,
        cardTail: String?,
        statementDay: Int? = null,
        dueDay: Int? = null,
        creditLimitCents: Long = 0
    ) {
        require(name.isNotBlank())
        require(openingBalanceCents >= 0)
        val id = UUID.randomUUID().toString()
        database.accountDao().upsert(
            AccountEntity(
                id = id,
                name = name.trim(),
                type = type.name,
                balanceCents = openingBalanceCents,
                cardTail = cardTail?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.isNotEmpty() },
                balanceStatus = "CONFIRMED",
                lastCheckedAt = System.currentTimeMillis(),
                statementDay = statementDay,
                dueDay = dueDay,
                creditLimitCents = creditLimitCents
            )
        )
        database.balanceCheckpointDao().upsert(
            BalanceCheckpointEntity(
                id = "opening_$id",
                accountId = id,
                balanceCents = openingBalanceCents,
                checkedAt = Long.MIN_VALUE,
                source = "OPENING"
            )
        )
    }

    suspend fun updateAccount(account: AccountEntity) {
        database.withTransaction {
            val old = database.accountDao().find(account.id)
            database.accountDao().upsert(account)
            // 用户手动改了余额：先记可追溯的余额调整（REQ 账户对账 §7/§9），再记 MANUAL 检查点。
            // 手动对账视为用户已核对：清掉差额标志，DISCREPANCY → CONFIRMED。
            if (old != null && old.balanceCents != account.balanceCents) {
                val now = System.currentTimeMillis()
                database.balanceAdjustmentDao().upsert(
                    BalanceAdjustmentEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = account.id,
                        beforeCents = old.balanceCents,
                        afterCents = account.balanceCents,
                        diffCents = account.balanceCents - old.balanceCents,
                        reason = "手动对账",
                        occurredAt = now
                    )
                )
                database.balanceCheckpointDao().upsert(
                    BalanceCheckpointEntity(
                        id = "manual_${account.id}_$now",
                        accountId = account.id,
                        balanceCents = account.balanceCents,
                        checkedAt = now,
                        source = "MANUAL"
                    )
                )
                database.accountDao().upsert(
                    account.copy(balanceStatus = "CONFIRMED", lastCheckedAt = now)
                )
            }
        }
    }

    suspend fun deleteAccount(accountId: String) {
        database.accountDao().deleteById(accountId)
    }

    /** 归档账户（REQ 账户对账 §14-15）：余额必须为 0，避免总资产凭空减少；历史仍可查。 */
    suspend fun archiveAccount(accountId: String) {
        val account = database.accountDao().find(accountId) ?: return
        require(account.balanceCents == 0L) { "归档前余额必须为 0" }
        database.accountDao().upsert(account.copy(archived = true))
    }

    suspend fun restoreAccount(accountId: String) {
        database.accountDao().find(accountId)?.let {
            database.accountDao().upsert(it.copy(archived = false))
        }
    }

    suspend fun updateTransactionCategory(id: String, category: TransactionCategory) {
        database.transactionDao().updateCategory(id, category.name)
    }

    suspend fun updateTransaction(
        id: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val old = requireNotNull(database.transactionDao().findById(id))
            // V5：借款/还款/提前还款流水不可编辑（联动了贷款计划），删除后重记
            val oldType = TransactionType.valueOf(old.type)
            val loanTypes = setOf(TransactionType.LOAN_DISBURSEMENT, TransactionType.LOAN_PAYMENT, TransactionType.LOAN_PREPAYMENT)
            val isLoanTx = type in loanTypes || oldType in loanTypes
            if (!isLoanTx) {
                database.transactionDao().update(id, amountCents, type.name, category, merchant, note)
                recomputeBalance(old.accountId)
            }
        }
    }

    suspend fun deleteTransaction(id: String) {
        database.withTransaction {
            val tx = requireNotNull(database.transactionDao().findById(id))
            val accountId = tx.accountId
            // V5 回滚：借款/还款/提前还款与贷款计划的联动还原，保持真相源对称
            when (TransactionType.valueOf(tx.type)) {
                TransactionType.LOAN_PAYMENT -> tx.loanPlanId?.let { planId ->
                    database.loanPlanDao().findById(planId)?.let { plan ->
                        // ponytail: 跨笔还款时按"最早优先"近似还原，单人串行操作下与标记过程对称
                        var covered = 0L
                        val insts = jsonToInstallments(plan.installmentsJson).map { inst ->
                            if (inst.status == InstallmentStatus.PAID && covered + inst.principal.cents <= tx.principalCents) {
                                covered += inst.principal.cents
                                inst.copy(status = InstallmentStatus.UPCOMING)
                            } else inst
                        }
                        database.loanPlanDao().upsert(
                            plan.copy(
                                remainingPrincipalCents = remainingEffective(plan) + tx.principalCents,
                                installmentsJson = installmentsToJson(insts),
                                status = "ACTIVE"  // 结清流水被删时恢复为进行中
                            )
                        )
                    }
                }
                TransactionType.LOAN_DISBURSEMENT -> tx.loanPlanId?.let { planId ->
                    database.loanPlanDao().findById(planId)?.let { plan ->
                        database.loanPlanDao().upsert(
                            plan.copy(remainingPrincipalCents = maxOf(0, remainingEffective(plan) - tx.amountCents))
                        )
                    }
                }
                TransactionType.LOAN_PREPAYMENT -> tx.loanPlanId?.let { planId ->
                    database.loanPlanDao().findById(planId)?.let { plan ->
                        database.loanPlanDao().upsert(
                            plan.copy(
                                remainingPrincipalCents = remainingEffective(plan) + tx.principalCents,
                                earlyRepaidCents = maxOf(0, plan.earlyRepaidCents - tx.principalCents)
                            )
                        )
                    }
                }
                else -> Unit
            }
            database.transactionDao().deleteById(id)
            recomputeBalance(accountId)
        }
    }

    suspend fun addTransaction(
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        isReimbursable: Boolean = false,
        recurringRuleId: String? = null,
        principalCents: Long = 0,
        interestCents: Long = 0,
        feeCents: Long = 0,
        loanPlanId: String? = null,
        refundOfId: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            requireNotNull(database.accountDao().find(accountId))
            database.transactionDao().insert(
                TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = accountId,
                    amountCents = amountCents,
                    type = type.name,
                    category = category,
                    occurredAt = occurredAt,
                    merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    refundOfId = refundOfId,
                    isReimbursable = isReimbursable,
                    recurringRuleId = recurringRuleId,
                    principalCents = principalCents,
                    interestCents = interestCents,
                    feeCents = feeCents,
                    loanPlanId = loanPlanId
                )
            )
            recomputeBalance(accountId)
        }
    }

    suspend fun addTransfer(fromAccountId: String, toAccountId: String, amountCents: Long, note: String?, occurredAt: Long) {
        require(amountCents > 0)
        require(fromAccountId != toAccountId)
        database.withTransaction {
            requireNotNull(database.accountDao().find(fromAccountId))
            requireNotNull(database.accountDao().find(toAccountId))
            database.transferDao().insert(
                TransferEntity(
                    id = UUID.randomUUID().toString(),
                    fromAccountId = fromAccountId,
                    toAccountId = toAccountId,
                    amountCents = amountCents,
                    occurredAt = occurredAt,
                    note = note?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            recomputeBalance(fromAccountId)
            recomputeBalance(toAccountId)
        }
    }

    /** 删除转账：删除后重算两边余额。账户已删的自动跳过（recomputeBalance 里 find 不到即返回）。 */
    suspend fun deleteTransfer(id: String) {
        database.withTransaction {
            val tf = database.transferDao().findById(id) ?: return@withTransaction
            database.transferDao().delete(id)
            recomputeBalance(tf.fromAccountId)
            recomputeBalance(tf.toAccountId)
        }
    }

    // ── Reimbursement ──

    val reimbursableTransactions: Flow<List<TransactionEntity>> =
        database.transactionDao().observeReimbursable()

    suspend fun toggleReimbursable(id: String, isReimbursable: Boolean) {
        database.transactionDao().updateReimbursable(id, isReimbursable)
    }

    suspend fun linkToRecurringRule(transactionId: String, ruleId: String?) {
        database.transactionDao().updateRecurringRuleId(transactionId, ruleId)
    }

    suspend fun transactionsByRule(ruleId: String): List<TransactionEntity> =
        database.transactionDao().findByRecurringRule(ruleId)

    // ── Budget CRUD ──

    val budgets: Flow<List<BudgetEntity>> = database.budgetDao().observeAll()

    suspend fun saveBudget(budget: BudgetEntity) {
        database.budgetDao().upsert(budget)
    }

    suspend fun deleteBudget(id: String) {
        database.budgetDao().deleteById(id)
    }

    // ── Snapshots ──

    suspend fun saveTodaySnapshot() {
        val today = java.time.LocalDate.now().toEpochDay()
        if (database.snapshotDao().findByDay(today) != null) return
        val all = allAccounts().filter { !it.archived }
        val assets = all.filter { it.type == AccountType.ASSET.name }.sumOf { it.balanceCents }
        val debts = all.filter { it.type != AccountType.ASSET.name }.sumOf { it.balanceCents }
        database.snapshotDao().upsert(
            SnapshotEntity(
                id = "snapshot_$today",
                dateEpochDay = today,
                totalAssets = assets,
                totalDebts = debts,
                netWorth = assets - debts
            )
        )
    }

    val snapshots: Flow<List<SnapshotEntity>> = database.snapshotDao().observeAll()

    // ── Custom Categories ──

    val customCategories: Flow<List<CustomCategoryEntity>> = database.customCategoryDao().observeAll()

    suspend fun addCustomCategory(name: String) {
        database.customCategoryDao().upsert(CustomCategoryEntity(name.trim()))
    }

    suspend fun deleteCustomCategory(name: String) {
        database.customCategoryDao().deleteByName(name)
    }

    // ── Smart Rules ──
    // 格式: { "美团": {"accountId":"xxx", "type":"EXPENSE", "category":"DINING"} }
    private val learnedRules = mutableMapOf<String, LearnedRule>()
    private var rulesLoaded = false

    private fun loadLearnedRules() {
        if (rulesLoaded) return
        val json = prefs.getString("learned_rules", null) ?: return
        runCatching {
            JSONObject(json).let { obj ->
                obj.keys().forEach { key ->
                    val rule = obj.getJSONObject(key)
                    learnedRules[key] = LearnedRule(
                        accountId = rule.getString("accountId"),
                        type = rule.getString("type"),
                        category = rule.getString("category")
                    )
                }
            }
        }
        rulesLoaded = true
    }

    /** 用户确认通知后学习：记住 商户→(账户,收支类型,分类) */
    fun learnRule(merchant: String?, accountId: String, type: String, category: String) {
        if (merchant.isNullOrBlank()) return
        val keyword = merchant.trim().take(8)
        learnedRules[keyword] = LearnedRule(accountId, type, category)
        val json = JSONObject()
        learnedRules.forEach { (k, v) ->
            json.put(k, JSONObject().apply {
                put("accountId", v.accountId)
                put("type", v.type)
                put("category", v.category)
            })
        }
        prefs.edit().putString("learned_rules", json.toString()).apply()
    }

    /** 匹配已学规则，返回完整的记账规则或 null */
    fun matchLearnedRule(merchant: String?): LearnedRule? {
        loadLearnedRules()
        if (merchant.isNullOrBlank()) return null
        val keyword = merchant.trim().take(8)
        learnedRules.forEach { (k, v) ->
            if (keyword.contains(k) || k.contains(keyword)) return v
        }
        return null
    }

    // ── Recurring Rules ──

    val recurringRules: Flow<List<RecurringRuleEntity>> = database.recurringRuleDao().observeAll()

    suspend fun saveRecurringRule(rule: RecurringRuleEntity) {
        database.recurringRuleDao().upsert(rule)
    }

    suspend fun deleteRecurringRule(id: String) {
        database.recurringRuleDao().deleteById(id)
    }

    /**
     * 处理到期的周期性账单：插入流水 + 计算下次执行时间。循环补漏多周期。
     *
     * 关键：**真实扣款已经被通知抓到时，绝不再造一笔**，改为把已有那笔认领给规则。
     * 否则「周期账单自动生成」+「通知自动记账」会把同一笔房租/订阅记两次，
     * 余额和 V5 的必须还/缺口全部虚高。
     */
    suspend fun processRecurring(): Int {
        val now = System.currentTimeMillis()
        val due = database.recurringRuleDao().observeActive().let { it.first() }
            .filter { it.nextRunAt <= now }
        if (due.isEmpty()) return 0

        var inserted = 0
        database.withTransaction {
            for (rule in due) {
                var cursor = rule.nextRunAt
                while (cursor <= now) {
                    val existing = findRealChargeFor(rule, cursor)
                    if (existing != null) {
                        database.transactionDao().updateRecurringRuleId(existing.id, rule.id)
                    } else {
                        addTransaction(
                            accountId = rule.accountId,
                            amountCents = rule.amountCents,
                            type = TransactionType.valueOf(rule.type),
                            category = rule.category,
                            merchant = rule.merchant,
                            note = rule.note,
                            occurredAt = cursor,
                            recurringRuleId = rule.id
                        )
                        inserted++
                    }
                    cursor = nextRun(cursor, rule.interval)
                }
                database.recurringRuleDao().upsert(rule.copy(nextRunAt = cursor))
            }
        }
        return inserted
    }

    /**
     * 该周期内是否已经有一笔真实扣款（通知抓的或手工记的）。
     * 匹配条件：同商户 + 同类型 + 金额相差 15% 以内 + 应扣日前后 5 天，且还没被别的规则认领。
     */
    private suspend fun findRealChargeFor(rule: RecurringRuleEntity, dueAt: Long): TransactionEntity? {
        val merchant = rule.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val window = 5L * 24 * 60 * 60 * 1000
        return database.transactionDao().findInRange(dueAt - window, dueAt + window)
            .firstOrNull {
                it.recurringRuleId == null &&
                    it.type == rule.type &&
                    it.merchant?.trim() == merchant &&
                    kotlin.math.abs(it.amountCents - rule.amountCents) * 100 <= rule.amountCents * 15
            }
    }

    private fun nextRun(from: Long, interval: String): Long {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = from }
        when (interval) {
            "DAILY" -> cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            "WEEKLY" -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
            "MONTHLY" -> cal.add(java.util.Calendar.MONTH, 1)
            "YEARLY" -> cal.add(java.util.Calendar.YEAR, 1)
        }
        return cal.timeInMillis
    }

    // ── Loan Plan CRUD ──

    val loanPlans: Flow<List<LoanPlanEntity>> = database.loanPlanDao().observeAll()

    suspend fun saveLoanPlan(plan: LoanPlanEntity) {
        database.loanPlanDao().upsert(plan)
    }

    suspend fun deleteLoanPlan(id: String) {
        database.loanPlanDao().deleteById(id)
    }

    // ── V5 借款到账 / 贷款还款（铁律：借款不是收入，还款不是消费）──

    /** 借款到账：现金+、贷款剩余本金+；绝不进收入统计（类型为 LOAN_DISBURSEMENT） */
    suspend fun addLoanDisbursement(
        cashAccountId: String,
        amountCents: Long,
        planId: String?,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(amountCents > 0)
        database.withTransaction {
            addTransaction(
                accountId = cashAccountId,
                amountCents = amountCents,
                type = TransactionType.LOAN_DISBURSEMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                loanPlanId = planId
            )
            if (planId != null) {
                database.loanPlanDao().findById(planId)?.let { plan ->
                    database.loanPlanDao().upsert(
                        plan.copy(remainingPrincipalCents = remainingEffective(plan) + amountCents)
                    )
                }
            }
        }
    }

    /** 贷款还款：现金−总额、剩余本金−本金、利息/费为真实成本；最早未还分期自动标 PAID */
    suspend fun addLoanPayment(
        cashAccountId: String,
        planId: String,
        totalCents: Long,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(totalCents > 0)
        require(principalCents >= 0 && interestCents >= 0 && feeCents >= 0)
        require(principalCents + interestCents + feeCents == totalCents) { "本金+利息+手续费必须等于总额" }
        val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
        require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
        database.withTransaction {
            addTransaction(
                accountId = cashAccountId,
                amountCents = totalCents,
                type = TransactionType.LOAN_PAYMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                principalCents = principalCents,
                interestCents = interestCents,
                feeCents = feeCents,
                loanPlanId = planId
            )
            // 完全覆盖该期本金才标 PAID（部分覆盖不标）
            var covered = 0L
            val insts = jsonToInstallments(plan.installmentsJson).map { inst ->
                if (inst.status != InstallmentStatus.PAID && covered + inst.principal.cents <= principalCents) {
                    covered += inst.principal.cents
                    inst.copy(status = InstallmentStatus.PAID)
                } else inst
            }
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = maxOf(0, remainingEffective(plan) - principalCents),
                    installmentsJson = installmentsToJson(insts)
                )
            )
        }
    }

    /** V5 公式层输入映射：贷款计划实体 → DTO（分期 JSON 解析只在此处一份） */
    fun v5PlanInput(plan: LoanPlanEntity): com.assetsking.ledger.V5PlanInput =
        com.assetsking.ledger.V5PlanInput(
            accountId = plan.accountId,
            remainingPrincipalCents = plan.remainingPrincipalCents,
            fallbackPrincipalCents = (plan.principalCents - plan.earlyRepaidCents).coerceAtLeast(0),
            annualRateBps = plan.annualRateBps,
            repaymentDay = plan.repaymentDay,
            status = plan.status,
            installments = jsonToInstallments(plan.installmentsJson).map {
                com.assetsking.ledger.V5InstallmentInput(
                    dueDateEpochDay = it.dueDateEpochDay,
                    principalCents = it.principal.cents,
                    interestCents = it.interest.cents,
                    feeCents = it.fee.cents,
                    isPaid = it.status == InstallmentStatus.PAID
                )
            }
        )

    /** 提前还款：只减本金、不当消费、不标普通期次；银行给出新计划后到贷款页更新 */
    suspend fun addLoanPrepayment(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(principalCents > 0)
        val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
        require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
        database.withTransaction {
            addTransaction(
                accountId = cashAccountId,
                amountCents = principalCents,
                type = TransactionType.LOAN_PREPAYMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                principalCents = principalCents,
                loanPlanId = planId
            )
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = maxOf(0, remainingEffective(plan) - principalCents),
                    earlyRepaidCents = plan.earlyRepaidCents + principalCents
                )
            )
        }
    }

    /** 提前结清：本金归零、全部期次标 PAID、计划 PAID_OFF、未来计划取消（V5 §36） */
    suspend fun settleLoanPlan(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        require(principalCents >= 0 && interestCents >= 0 && feeCents >= 0)
        require(principalCents + interestCents + feeCents > 0) { "结清金额必须大于 0" }
        val plan = requireNotNull(database.loanPlanDao().findById(planId)) { "贷款计划不存在" }
        require(principalCents <= remainingEffective(plan)) { "本金超过剩余本金" }
        database.withTransaction {
            addTransaction(
                accountId = cashAccountId,
                amountCents = principalCents + interestCents + feeCents,
                type = TransactionType.LOAN_PAYMENT,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = note,
                occurredAt = occurredAt,
                principalCents = principalCents,
                interestCents = interestCents,
                feeCents = feeCents,
                loanPlanId = planId
            )
            val allPaid = jsonToInstallments(plan.installmentsJson)
                .map { it.copy(status = InstallmentStatus.PAID) }
            database.loanPlanDao().upsert(
                plan.copy(
                    remainingPrincipalCents = 0,
                    installmentsJson = installmentsToJson(allPaid),
                    status = "PAID_OFF"
                )
            )
        }
    }

    // ── V5 月度负债锚点 / 年终奖 / 信用卡分期 ──

    val monthDebtAnchors: Flow<List<MonthDebtAnchorEntity>> = database.monthDebtAnchorDao().observeAll()

    /** 当月无锚点才建档（=当天 V5 总负债），从今天起记录、不回填历史（铁律 7） */
    suspend fun ensureMonthAnchor(totalDebtCents: Long) {
        val ym = YearMonth.now().toString()
        if (database.monthDebtAnchorDao().findByYearMonth(ym) == null) {
            database.monthDebtAnchorDao().upsert(MonthDebtAnchorEntity(ym, totalDebtCents))
        }
    }

    val windfalls: Flow<List<WindfallEntity>> = database.windfallDao().observeAll()

    suspend fun saveWindfall(windfall: WindfallEntity) {
        database.windfallDao().upsert(windfall)
    }

    suspend fun deleteWindfall(id: String) {
        database.windfallDao().deleteById(id)
    }

    /** 年终奖到账：状态流转 + 记一笔 INCOME 流水（到账后才算现金和收入，铁律 8） */
    suspend fun markWindfallReceived(id: String, actualCents: Long, cashAccountId: String) {
        require(actualCents > 0)
        val wf = requireNotNull(database.windfallDao().findById(id))
        require(wf.status == WindfallStatus.EXPECTED.name) { "只有预期中的年终奖才能标记到账" }
        database.withTransaction {
            addTransaction(
                accountId = cashAccountId,
                amountCents = actualCents,
                type = TransactionType.INCOME,
                category = TransactionCategory.UNCATEGORIZED.name,
                merchant = null,
                note = "${wf.name} 到账",
                loanPlanId = null
            )
            database.windfallDao().upsert(
                wf.copy(
                    status = WindfallStatus.RECEIVED.name,
                    receivedAmountCents = actualCents,
                    receivedAtEpochDay = LocalDate.now().toEpochDay()
                )
            )
        }
    }

    val cardInstallments: Flow<List<CreditCardInstallmentEntity>> =
        database.creditCardInstallmentDao().observeAll()

    suspend fun saveCardInstallment(installment: CreditCardInstallmentEntity) {
        database.creditCardInstallmentDao().upsert(installment)
    }

    suspend fun deleteCardInstallment(id: String) {
        database.creditCardInstallmentDao().deleteById(id)
    }

    // ── V5 现金流设置（SharedPreferences：设置不是记录，不进 DB）──

    private val _monthlyIncomeCents = MutableStateFlow(prefs.getLong("v5_monthly_income_cents", 0L))
    val monthlyIncomeCents: Flow<Long> = _monthlyIncomeCents

    fun setMonthlyIncomeCents(cents: Long) {
        _monthlyIncomeCents.value = cents
        prefs.edit().putLong("v5_monthly_income_cents", cents).apply()
    }

    private val _necessaryLivingCents = MutableStateFlow(prefs.getLong("v5_necessary_living_cents", 0L))
    val necessaryLivingCents: Flow<Long> = _necessaryLivingCents

    fun setNecessaryLivingCents(cents: Long) {
        _necessaryLivingCents.value = cents
        prefs.edit().putLong("v5_necessary_living_cents", cents).apply()
    }

    // ── 非必要消费分类（决定"自由消费"要扣哪些已花支出）──

    private val defaultOptionalCategories =
        setOf("ENTERTAINMENT", "SHOPPING", "OTHER", "UNCATEGORIZED")

    private val _optionalCategories = MutableStateFlow(
        prefs.getString("v5_optional_categories", null)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: defaultOptionalCategories
    )
    val optionalCategories: Flow<Set<String>> = _optionalCategories

    fun setOptionalCategories(categories: Set<String>) {
        _optionalCategories.value = categories
        prefs.edit().putString("v5_optional_categories", categories.joinToString(",")).apply()
    }

    // ── 通知来源白名单：只收「能扣钱」的 app，避免聊天/外卖/系统通知淹没待确认箱 ──

    private val _notificationWhitelist = MutableStateFlow(
        prefs.getString("notif_whitelist", null)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: DEFAULT_PAYMENT_PACKAGES
    )
    val notificationWhitelist: Flow<Set<String>> = _notificationWhitelist

    fun setNotificationWhitelist(packages: Set<String>) {
        _notificationWhitelist.value = packages
        prefs.edit().putString("notif_whitelist", packages.joinToString(",")).apply()
    }

    /** 监听服务在 onNotificationPosted 里同步调用，等不了 Flow */
    fun isWhitelisted(packageName: String): Boolean = packageName in _notificationWhitelist.value

    /** 自动发现的通知来源：包名 → 应用名。设置页据此列出可开关的清单 */
    private val _notificationSources = MutableStateFlow(readNotificationSources())
    val notificationSources: Flow<Map<String, String>> = _notificationSources

    private fun readNotificationSources(): Map<String, String> =
        prefs.getStringSet("notif_sources", emptySet()).orEmpty()
            .mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0) null else entry.substring(0, sep) to entry.substring(sep + 1)
            }.toMap()

    /**
     * 登记一个通知来源（不放行的也记）。已存在则不动。
     * ponytail: 上限 200 条，防止异常 app 刷爆 prefs；到顶后不再收新来源，够用了。
     */
    fun recordNotificationSource(packageName: String, label: String?) {
        val current = _notificationSources.value
        if (packageName in current || current.size >= 200) return
        val updated = current + (packageName to (label?.takeIf { it.isNotBlank() } ?: packageName))
        _notificationSources.value = updated
        prefs.edit()
            .putStringSet("notif_sources", updated.map { "${it.key}|${it.value}" }.toSet())
            .apply()
    }

    // ── Stats ──

    /** 给定时间范围内的所有流水，用于聚合统计 */
    suspend fun transactionsInRange(start: Long, end: Long): List<TransactionEntity> =
        database.transactionDao().findInRange(start, end)

    /** 所有流水（导出用） */
    suspend fun allTransactions(): List<TransactionEntity> =
        database.transactionDao().all()

    // ── Backup ──

    suspend fun exportCsvTransactions(): String = buildString {
        appendLine("日期,类型,分类,金额,商户,备注,账户")
        for (tx in allTransactions()) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(tx.occurredAt))
            val type = txTypeCsvLabel(tx.type)
            val catLabel = txCategoryCsvLabel(tx.category)
            val amount = "%.2f".format(tx.amountCents / 100.0)
            val merchant = tx.merchant?.replace(",", "，") ?: ""
            val note = tx.note?.replace(",", "，") ?: ""
            appendLine("$date,$type,$catLabel,$amount,$merchant,$note,${tx.accountId}")
        }
    }

    suspend fun exportAllData(): String {
        val accounts = database.accountDao().observeAll().let { flow ->
            // ponytail: 直接用 suspend 查询，不走 Flow
            allAccounts()
        }
        val txs = allTransactions()
        val transfers = allTransfers()
        val budgets = allBudgets()
        val plans = allLoanPlans()

        return buildString {
            appendLine("{")
            appendLine("""  "accounts": """)
            appendLine(accounts.joinToString(prefix = "[", postfix = "]") { a ->
                """{"id":"${a.id}","name":"${a.name}","type":"${a.type}","balanceCents":${a.balanceCents},"""
                    .plus(""""cardTail":${a.cardTail?.let{"\"$it\""} ?: "null"},"balanceStatus":"${a.balanceStatus}","""
                        .plus(""""lastCheckedAt":${a.lastCheckedAt ?: "null"}}"""))
            })
            appendLine(",")
            appendLine("""  "transactions": """)
            appendLine(txs.joinToString(prefix = "[", postfix = "]") { tx ->
                """{"id":"${tx.id}","accountId":"${tx.accountId}","amountCents":${tx.amountCents},"""
                    .plus(""""type":"${tx.type}","category":"${tx.category}","occurredAt":${tx.occurredAt},"""
                        .plus(""""merchant":${tx.merchant?.let{"\"$it\""} ?: "null"},"""
                            .plus(""""note":${tx.note?.let{"\"$it\""} ?: "null"},"""
                                .plus(""""status":"${tx.status}"}"""))))
            })
            appendLine(",")
            appendLine("""  "transfers": ${transfers.size}""")
            appendLine(",")
            appendLine("""  "budgets": ${budgets.size}""")
            appendLine(",")
            appendLine("""  "loanPlans": ${plans.size}""")
            appendLine("}")
        }
    }

    private suspend fun allAccounts(): List<AccountEntity> =
        database.accountDao().observeAll().let { it.first() }

    private suspend fun allTransfers(): List<TransferEntity> =
        database.transferDao().observeAll().let { it.first() }

    private suspend fun allBudgets(): List<BudgetEntity> =
        database.budgetDao().observeAll().let { it.first() }

    private suspend fun allLoanPlans(): List<LoanPlanEntity> =
        database.loanPlanDao().observeAll().let { it.first() }

    private fun txTypeCsvLabel(type: String): String = when (type) {
        "EXPENSE" -> "支出"
        "INCOME" -> "收入"
        "REFUND" -> "退款"
        "FEE" -> "手续费"
        "LOAN_DISBURSEMENT" -> "借款到账"
        "LOAN_PAYMENT" -> "贷款还款"
        "LOAN_PREPAYMENT" -> "提前还款"
        else -> type
    }

    private fun txCategoryCsvLabel(cat: String): String = when (cat) {
        "DINING" -> "餐饮"
        "TRANSPORT" -> "交通"
        "SHOPPING" -> "购物"
        "HOUSING" -> "居住"
        "UTILITIES" -> "水电"
        "MEDICAL" -> "医疗"
        "EDUCATION" -> "教育"
        "ENTERTAINMENT" -> "娱乐"
        "DIGITAL_SERVICES" -> "数字服务"
        "FINANCIAL_FEES" -> "手续费"
        "OTHER" -> "其他"
        else -> "未分类"
    }

    // ── Loan 分期 JSON 序列化（core-database 不能依赖 app，此处自持副本）──

    private fun remainingEffective(plan: LoanPlanEntity): Long =
        com.assetsking.ledger.effectiveRemainingPrincipalCents(
            plan.remainingPrincipalCents,
            (plan.principalCents - plan.earlyRepaidCents).coerceAtLeast(0),
            plan.status
        )

    private fun installmentsToJson(list: List<LoanInstallment>): String =
        JSONArray().apply {
            list.forEach { inst ->
                put(JSONObject().apply {
                    put("number", inst.number)
                    put("dueDateEpochDay", inst.dueDateEpochDay)
                    put("principal", inst.principal.cents)
                    put("interest", inst.interest.cents)
                    put("fee", inst.fee.cents)
                    put("status", inst.status.name)
                })
            }
        }.toString()

    private fun jsonToInstallments(json: String): List<LoanInstallment> =
        runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                LoanInstallment(
                    number = obj.getInt("number"),
                    dueDateEpochDay = obj.getLong("dueDateEpochDay"),
                    principal = Money(obj.getLong("principal")),
                    interest = Money(obj.getLong("interest")),
                    fee = Money(obj.getLong("fee")),
                    status = runCatching { InstallmentStatus.valueOf(obj.getString("status")) }
                        .getOrDefault(InstallmentStatus.UPCOMING)
                )
            }
        }.getOrDefault(emptyList())
}
