package com.assetsking.database

import android.content.SharedPreferences
import androidx.room.withTransaction
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
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
    private val categorizer = RuleBasedCategorizer()

    suspend fun seedKnownAccounts() {
        database.accountDao().insertAll(
            listOf(
                AccountEntity("cmb", "招商银行", AccountType.ASSET.name, 0),
                AccountEntity("nbcb", "宁波银行", AccountType.ASSET.name, 0),
                AccountEntity("cgb", "广发信用卡", AccountType.CREDIT.name, 0),
                AccountEntity("huabei", "花呗", AccountType.LOAN.name, 0)
            )
        )
    }

    fun categorize(merchant: String?, note: String? = null): TransactionCategory =
        categorizer.categorize(merchant, note)

    suspend fun saveRawNotification(notification: RawNotificationEntity) {
        database.rawNotificationDao().insert(notification)
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
        note: String?
    ) {
        require(amountCents > 0)
        database.withTransaction {
            addTransaction(accountId, amountCents, type, category, merchant, note)
            database.rawNotificationDao().updateStatus(notificationId, "LINKED")
        }
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
        database.accountDao().upsert(
            AccountEntity(
                id = UUID.randomUUID().toString(),
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
    }

    suspend fun updateAccount(account: AccountEntity) {
        database.accountDao().upsert(account)
    }

    suspend fun deleteAccount(accountId: String) {
        database.accountDao().deleteById(accountId)
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
            val account = requireNotNull(database.accountDao().find(old.accountId))
            // 冲销旧余额影响
            val oldDelta = balanceDelta(account.type, old.type, old.amountCents)
            // 应用新余额影响
            val newDelta = balanceDelta(account.type, type.name, amountCents)
            database.accountDao().upsert(account.copy(balanceCents = account.balanceCents - oldDelta + newDelta))
            database.transactionDao().update(id, amountCents, type.name, category, merchant, note)
        }
    }

    suspend fun deleteTransaction(id: String) {
        database.withTransaction {
            val tx = requireNotNull(database.transactionDao().findById(id))
            val account = requireNotNull(database.accountDao().find(tx.accountId))
            val delta = balanceDelta(account.type, tx.type, tx.amountCents)
            database.accountDao().upsert(account.copy(balanceCents = account.balanceCents - delta))
            database.transactionDao().deleteById(id)
        }
    }

    private fun balanceDelta(accountType: String, txType: String, amountCents: Long): Long {
        val at = AccountType.valueOf(accountType)
        val assetDelta = when (TransactionType.valueOf(txType)) {
            TransactionType.EXPENSE, TransactionType.FEE -> -amountCents
            TransactionType.INCOME, TransactionType.REFUND -> amountCents
        }
        return if (at == AccountType.ASSET) assetDelta else -assetDelta
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
        recurringRuleId: String? = null
    ) {
        require(amountCents > 0)
        database.withTransaction {
            val account = requireNotNull(database.accountDao().find(accountId))
            val accountType = AccountType.valueOf(account.type)
            val assetDelta = when (type) {
                TransactionType.EXPENSE, TransactionType.FEE -> -amountCents
                TransactionType.INCOME, TransactionType.REFUND -> amountCents
            }
            val actualDelta = if (accountType == AccountType.ASSET) assetDelta else -assetDelta
            database.accountDao().upsert(account.copy(balanceCents = account.balanceCents + actualDelta))
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
                    isReimbursable = isReimbursable,
                    recurringRuleId = recurringRuleId
                )
            )
        }
    }

    suspend fun addTransfer(fromAccountId: String, toAccountId: String, amountCents: Long, note: String?) {
        require(amountCents > 0)
        require(fromAccountId != toAccountId)
        database.withTransaction {
            val from = requireNotNull(database.accountDao().find(fromAccountId))
            val to = requireNotNull(database.accountDao().find(toAccountId))
            val fromType = AccountType.valueOf(from.type)
            val toType = AccountType.valueOf(to.type)
            val fromDelta = if (fromType == AccountType.ASSET) -amountCents else amountCents
            val toDelta = if (toType == AccountType.ASSET) amountCents else -amountCents
            database.accountDao().upsert(from.copy(balanceCents = from.balanceCents + fromDelta))
            database.accountDao().upsert(to.copy(balanceCents = to.balanceCents + toDelta))
            database.transferDao().insert(
                TransferEntity(
                    id = UUID.randomUUID().toString(),
                    fromAccountId = fromAccountId,
                    toAccountId = toAccountId,
                    amountCents = amountCents,
                    occurredAt = System.currentTimeMillis(),
                    note = note?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
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
        val all = allAccounts()
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

    // ── Goals ──

    val latestGoal: Flow<GoalEntity?> = database.goalDao().observeLatest()

    suspend fun saveGoal(goal: GoalEntity) { database.goalDao().upsert(goal) }

    suspend fun deleteGoal(id: String) { database.goalDao().deleteById(id) }

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

    /** 处理到期的周期性账单：插入流水 + 计算下次执行时间。循环补漏多周期。 */
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
                    cursor = nextRun(cursor, rule.interval)
                    inserted++
                }
                database.recurringRuleDao().upsert(rule.copy(nextRunAt = cursor))
            }
        }
        return inserted
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
}
