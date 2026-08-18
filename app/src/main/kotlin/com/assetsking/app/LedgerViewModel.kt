package com.assetsking.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.CustomCategoryEntity
import com.assetsking.database.RawNotificationEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.SnapshotEntity
import com.assetsking.database.TransferEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.ledger.DetectedRecurring
import com.assetsking.ledger.NecessaryLivingSuggestion
import com.assetsking.ledger.V5Metrics
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.usecase.AddAccountUseCase
import com.assetsking.usecase.GetV5MetricsUseCase
import com.assetsking.usecase.NotificationParser
import com.assetsking.usecase.ParsedNotification
import com.assetsking.usecase.ProcessPendingUseCase
import com.assetsking.usecase.RecordTransactionUseCase
import com.assetsking.usecase.RecordTransferUseCase
import com.assetsking.usecase.SeedAccountsUseCase
import com.assetsking.usecase.SpendPatternsUseCase
import com.assetsking.usecase.UpdateCategoryUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

enum class RecordMode(val label: String) {
    EXPENSE("支出"), INCOME("收入"), TRANSFER("转账/还款"), REFUND("退款"),
    LOAN_DISBURSEMENT("借款到账"), LOAN_PAYMENT("贷款还款")
}

data class PendingItem(
    val notification: RawNotificationEntity,
    val parsed: ParsedNotification
)

private data class BaseState(
    val accounts: List<AccountEntity>,
    val transactions: List<TransactionEntity>,
    val transfers: List<TransferEntity>,
    val count: Int,
    val pending: List<RawNotificationEntity>,
    val ignored: List<RawNotificationEntity>
)

data class LedgerUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val transfers: List<TransferEntity> = emptyList(),
    val unprocessedNotifications: Int = 0,
    val pendingItems: List<PendingItem> = emptyList(),
    val v5: V5Metrics? = null,    // null = 数据加载中
    /** 商户 → 最近一次使用的账户 id（REQ 账户对账 §18 默认账户推断） */
    val merchantLastAccount: Map<String, String> = emptyMap(),
    /** 已忽略的证据（判重/对冲/无法识别）：备注里带 kept=<id> 的是某待确认项的合并证据 */
    val ignoredItems: List<RawNotificationEntity> = emptyList()
)

class LedgerViewModel(
    private val seedAccounts: SeedAccountsUseCase,
    private val recordTransaction: RecordTransactionUseCase,
    private val recordTransfer: RecordTransferUseCase,
    private val addAccount: AddAccountUseCase,
    private val updateCategory: UpdateCategoryUseCase,
    getV5Metrics: GetV5MetricsUseCase,
    private val processPending: ProcessPendingUseCase,
    private val spendPatterns: SpendPatternsUseCase,
    private val repository: com.assetsking.database.LedgerRepository
) : ViewModel() {
    // V5 指标在 ViewModel 里算（一个 Flow 发射 → 一套新数字），严禁在 Composable 内现算
    val state = combine(
        repository.accounts,
        repository.transactions,
        repository.transfers,
        repository.unprocessedNotifications,
        repository.pendingNotifications
    ) { accounts, transactions, transfers, count, pending ->
        BaseState(accounts, transactions, transfers, count, pending, emptyList())
    }.combine(repository.ignoredNotifications) { base, ignored ->
        base.copy(ignored = ignored)
    }.flatMapLatest { base ->
        getV5Metrics().map { v5 ->
            LedgerUiState(
                accounts = base.accounts,
                transactions = base.transactions,
                transfers = base.transfers,
                unprocessedNotifications = base.count,
                pendingItems = base.pending.map { entity ->
                    PendingItem(entity, NotificationParser.parse(entity.content, entity.title))
                },
                v5 = v5,
                merchantLastAccount = base.transactions
                    .filter { !it.merchant.isNullOrBlank() }
                    .groupBy { it.merchant!! }
                    .mapValues { (_, txs) -> txs.maxByOrNull { it.occurredAt }!!.accountId },
                ignoredItems = base.ignored
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    val budgets: Flow<List<BudgetEntity>> = repository.budgets
    val loanPlans: Flow<List<LoanPlanEntity>> = repository.loanPlans
    val reimbursable: Flow<List<TransactionEntity>> = repository.reimbursableTransactions
    val recurringRules: Flow<List<RecurringRuleEntity>> = repository.recurringRules
    val snapshots: Flow<List<SnapshotEntity>> = repository.snapshots
    val customCategories: Flow<List<CustomCategoryEntity>> = repository.customCategories
    val cardInstallments: Flow<List<com.assetsking.database.CreditCardInstallmentEntity>> = repository.cardInstallments
    val windfalls: Flow<List<com.assetsking.database.WindfallEntity>> = repository.windfalls
    /** 一级/二级分类库（REQ 初始分类库） */
    val categories: Flow<List<com.assetsking.database.CategoryEntity>> = repository.categories
    /** 交易对象库（标准商户 + 别名 + 学习规则，REQ 商户库） */
    val merchants: Flow<List<com.assetsking.database.MerchantEntity>> = repository.merchants

    init {
        // 首次启动播种分类库；账户种子由既有流程负责，不动
        viewModelScope.launch { repository.seedDefaultCategoriesIfEmpty() }
    }
    val monthlyIncomeCents: Flow<Long> = repository.monthlyIncomeCents
    val necessaryLivingCents: Flow<Long> = repository.necessaryLivingCents
    val optionalCategories: Flow<Set<String>> = repository.optionalCategories
    /** 自动发现的通知来源：包名 → 应用名 */
    val notificationSources: Flow<Map<String, String>> = repository.notificationSources
    val notificationWhitelist: Flow<Set<String>> = repository.notificationWhitelist
    /** 最近一次原始证据入库时间（0 = 从未收到） */
    val lastReceivedAt: Flow<Long> = repository.lastReceivedAt

    // ── 从流水反推的两个建议（用户填不准的那两项）──

    private val _necessaryLivingSuggestion = MutableStateFlow<NecessaryLivingSuggestion?>(null)
    val necessaryLivingSuggestion: StateFlow<NecessaryLivingSuggestion?> = _necessaryLivingSuggestion

    private val _detectedRecurring = MutableStateFlow<List<DetectedRecurring>>(emptyList())
    val detectedRecurring: StateFlow<List<DetectedRecurring>> = _detectedRecurring

    /** 本月未分类支出：笔数 → 金额。未分类越多，必要生活建议越不准 */
    private val _uncategorized = MutableStateFlow(0 to 0L)
    val uncategorized: StateFlow<Pair<Int, Long>> = _uncategorized

    init {
        viewModelScope.launch {
            seedAccounts()
            repository.processRecurring()
            repository.saveTodaySnapshot()
            // 当月无锚点 → 用当前 V5 总负债建档（从今天起记录，不回填历史）
            val v5 = getV5Metrics().first()
            repository.ensureMonthAnchor(v5.totalDebtCents)
        }
        refreshSpendPatterns()
    }

    /** 重算建议。一次性扫全表，别放进 Flow 里跟着每笔流水重跑 */
    fun refreshSpendPatterns() {
        viewModelScope.launch {
            runCatching {
                _necessaryLivingSuggestion.value = spendPatterns.necessaryLiving()
                _detectedRecurring.value = spendPatterns.recurringCharges()
                _uncategorized.value = spendPatterns.uncategorizedThisMonth()
            }
        }
    }

    /**
     * 把识别出的固定扣款确认为周期账单。
     * isSubscription 标 true，进设置页「含订阅」合计；不会重复记账 ——
     * processRecurring 到点会先找有没有真实扣款，有就认领而不是再造一笔。
     */
    fun confirmDetectedRecurring(detected: DetectedRecurring) {
        viewModelScope.launch {
            val today = java.time.LocalDate.now()
            val next = generateSequence(java.time.YearMonth.from(today)) { it.plusMonths(1) }
                .map { ym -> ym.atDay(detected.dayOfMonth.coerceAtMost(ym.lengthOfMonth())) }
                .first { it.isAfter(today) }
            repository.saveRecurringRule(
                RecurringRuleEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    accountId = detected.accountId,
                    amountCents = detected.amountCents,
                    type = TransactionType.EXPENSE.name,
                    category = detected.category,
                    merchant = detected.merchant,
                    note = "自动识别：近${detected.occurrences}次固定扣款",
                    interval = "MONTHLY",
                    nextRunAt = next.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    isSubscription = true
                )
            )
            refreshSpendPatterns()
        }
    }

    fun categorize(merchant: String?, note: String?): TransactionCategory =
        repository.categorize(merchant, note)

    fun addAccount(name: String, type: AccountType, openingBalance: String, cardTail: String?, statementDay: Int? = null, dueDay: Int? = null, creditLimit: Long = 0) {
        val cents = openingBalance.toCentsOrNull(allowZero = true) ?: return
        viewModelScope.launch { addAccount(name, type, cents, cardTail, statementDay, dueDay, creditLimit) }
    }

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch { repository.updateAccount(account) }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch { repository.deleteAccount(accountId) }
    }

    fun updateTransactionCategory(id: String, category: TransactionCategory) {
        viewModelScope.launch { updateCategory(id, category) }
    }

    fun updateTransaction(
        id: String, amountCents: Long, type: TransactionType,
        category: String, merchant: String?, note: String?
    ) {
        viewModelScope.launch {
            repository.updateTransaction(id, amountCents, type, category, merchant, note)
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch { repository.deleteTransaction(id) }
    }

    fun saveBudget(budget: BudgetEntity) {
        viewModelScope.launch { repository.saveBudget(budget) }
    }

    fun deleteBudget(id: String) {
        viewModelScope.launch { repository.deleteBudget(id) }
    }

    fun saveLoanPlan(plan: LoanPlanEntity) {
        viewModelScope.launch { repository.saveLoanPlan(plan) }
    }

    fun deleteLoanPlan(id: String) {
        viewModelScope.launch { repository.deleteLoanPlan(id) }
    }

    fun toggleReimbursable(id: String, isReimbursable: Boolean) {
        viewModelScope.launch { repository.toggleReimbursable(id, isReimbursable) }
    }

    fun linkToRecurringRule(transactionId: String, ruleId: String?) {
        viewModelScope.launch { repository.linkToRecurringRule(transactionId, ruleId) }
    }

    fun addCustomCategory(name: String) {
        viewModelScope.launch { repository.addCustomCategory(name) }
    }

    fun deleteCustomCategory(name: String) {
        viewModelScope.launch { repository.deleteCustomCategory(name) }
    }

    fun saveRecurringRule(rule: RecurringRuleEntity) {
        viewModelScope.launch { repository.saveRecurringRule(rule) }
    }

    fun deleteRecurringRule(id: String) {
        viewModelScope.launch { repository.deleteRecurringRule(id) }
    }

    fun reconcileAccount(account: AccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(
                account.copy(
                    balanceStatus = "CONFIRMED",
                    lastCheckedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun addTransaction(
        accountId: String, amount: String, mode: RecordMode,
        category: String, merchant: String?, note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        isReimbursable: Boolean = false
    ) {
        val cents = amount.toCentsOrNull() ?: return
        val type = when (mode) {
            RecordMode.EXPENSE -> TransactionType.EXPENSE
            RecordMode.INCOME -> TransactionType.INCOME
            RecordMode.REFUND -> TransactionType.REFUND
            // 借款到账/贷款还款走专用方法（联动贷款计划），此路径不处理
            RecordMode.TRANSFER, RecordMode.LOAN_DISBURSEMENT, RecordMode.LOAN_PAYMENT -> return
        }
        viewModelScope.launch { recordTransaction(accountId, cents, type, categoryStr = category, merchant = merchant, note = note, occurredAt = occurredAt, isReimbursable = isReimbursable) }
    }

    // ── V5 借款与还款 ──

    /** 借款到账：现金+、关联计划剩余本金+；不是收入（铁律 1） */
    fun addLoanDisbursement(accountId: String, amount: String, planId: String?, note: String?, occurredAt: Long = System.currentTimeMillis()) {
        val cents = amount.toCentsOrNull() ?: return
        viewModelScope.launch {
            repository.addLoanDisbursement(accountId, cents, planId?.takeIf { it.isNotBlank() }, note, occurredAt)
        }
    }

    /** 贷款还款：本金/利息/费拆分，联动计划剩余本金与分期状态；不是消费（铁律 3） */
    fun addLoanPayment(
        accountId: String, planId: String,
        total: String, principal: String, interest: String, fee: String,
        note: String?, occurredAt: Long = System.currentTimeMillis()
    ) {
        val t = total.toCentsOrNull() ?: return
        val p = principal.toCentsOrNull(allowZero = true) ?: return
        val i = interest.toCentsOrNull(allowZero = true) ?: return
        val f = fee.toCentsOrNull(allowZero = true) ?: return
        viewModelScope.launch {
            repository.addLoanPayment(accountId, planId, t, p, i, f, note, occurredAt)
        }
    }

    // ── V5 年终奖 / 信用卡分期 / 现金流设置 ──

    fun saveWindfall(windfall: com.assetsking.database.WindfallEntity) {
        viewModelScope.launch { repository.saveWindfall(windfall) }
    }

    fun deleteWindfall(id: String) {
        viewModelScope.launch { repository.deleteWindfall(id) }
    }

    fun markWindfallReceived(id: String, actualCents: Long, cashAccountId: String) {
        viewModelScope.launch { repository.markWindfallReceived(id, actualCents, cashAccountId) }
    }

    fun saveCardInstallment(installment: com.assetsking.database.CreditCardInstallmentEntity) {
        viewModelScope.launch { repository.saveCardInstallment(installment) }
    }

    fun deleteCardInstallment(id: String) {
        viewModelScope.launch { repository.deleteCardInstallment(id) }
    }

    fun setMonthlyIncomeCents(cents: Long) {
        repository.setMonthlyIncomeCents(cents)
    }

    fun setNecessaryLivingCents(cents: Long) {
        repository.setNecessaryLivingCents(cents)
    }

    fun setOptionalCategories(categories: Set<String>) {
        repository.setOptionalCategories(categories)
    }

    /** 通知来源开关：只有白名单里的 app 会被读取入库 */
    fun setNotificationWhitelist(packages: Set<String>) {
        repository.setNotificationWhitelist(packages)
    }

    /** 提前还款：只减本金、不当消费、不标普通期次（铁律 3） */
    fun addLoanPrepayment(cashAccountId: String, planId: String, principalCents: Long, note: String?, occurredAt: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            repository.addLoanPrepayment(cashAccountId, planId, principalCents, note, occurredAt)
        }
    }

    /** 提前结清：本金归零、全期 PAID、计划 PAID_OFF（V5 §36） */
    fun settleLoanPlan(
        cashAccountId: String, planId: String,
        principalCents: Long, interestCents: Long, feeCents: Long,
        note: String?, occurredAt: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            repository.settleLoanPlan(cashAccountId, planId, principalCents, interestCents, feeCents, note, occurredAt)
        }
    }

    // ── 统一编辑器（M4）专用保存入口：带必要性/渠道/报销关联 ──

    fun saveEditorTransaction(
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        isReimbursable: Boolean = false,
        necessity: Boolean? = null,
        channel: String? = null
    ) {
        viewModelScope.launch {
            repository.addTransaction(
                accountId, amountCents, type, category, merchant, note,
                occurredAt = occurredAt, isReimbursable = isReimbursable,
                necessity = necessity, channel = channel
            )
        }
    }

    fun saveReimbursement(
        accountId: String,
        amountCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        expenseIds: List<String>
    ) {
        viewModelScope.launch { repository.addReimbursement(accountId, amountCents, note, occurredAt, expenseIds) }
    }

    fun addCategoryEntity(name: String, shortName: String, parentId: String?, iconKey: String, defaultNecessary: Boolean?) {
        viewModelScope.launch { repository.addCategory(name, shortName, parentId, iconKey, defaultNecessary) }
    }

    fun updateCategoryEntity(id: String, name: String?, shortName: String?, parentId: String?) {
        viewModelScope.launch { repository.updateCategory(id, name, shortName, parentId) }
    }

    fun archiveOrDeleteCategory(id: String) {
        viewModelScope.launch { repository.deleteCategory(id) }
    }

    fun mergeCategoryEntity(sourceId: String, targetId: String) {
        viewModelScope.launch { repository.mergeCategory(sourceId, targetId) }
    }

    fun addTransfer(fromAccountId: String, toAccountId: String, amount: String, note: String?, occurredAt: Long = System.currentTimeMillis()) {
        val cents = amount.toCentsOrNull() ?: return
        viewModelScope.launch { recordTransfer(fromAccountId, toAccountId, cents, note, occurredAt) }
    }

    fun deleteTransfer(id: String) {
        viewModelScope.launch { repository.deleteTransfer(id) }
    }

    // ── 通知处理 ──

    /** 批量解析 NEW 通知，触发去重和状态流转 */
    fun processNotifications() {
        viewModelScope.launch { processPending.invoke() }
    }

    /** 确认通知 → 创建交易+标记 LINKED + 学习规则 */
    fun confirmNotification(
        notificationId: String,
        accountId: String,
        amountCents: Long,
        type: TransactionType,
        category: String,
        merchant: String?,
        note: String?,
        bankBalanceCents: Long? = null,
        bankCardTail: String? = null,
        necessity: Boolean? = null,
        channel: String? = null
    ) {
        viewModelScope.launch {
            repository.confirmNotification(
                notificationId, accountId, amountCents, type, category, merchant, note,
                bankBalanceCents, bankCardTail, necessity, channel
            )
            repository.learnRule(merchant, accountId, type.name, category)
        }
    }

    /** 忽略通知 */
    fun ignoreNotification(notificationId: String) {
        viewModelScope.launch { repository.updateNotificationStatus(notificationId, "IGNORED") }
    }

    /** 用户确认/手动记账后学习：记住 商户→(账户,收支类型,分类) */
    fun learnRule(merchant: String?, accountId: String, type: String, category: String) {
        viewModelScope.launch { repository.learnRule(merchant, accountId, type, category) }
    }

    /** 拆分通知（REQ 归并§18）：把被误合并的证据恢复为独立待确认项 */
    fun splitNotification(notificationId: String) {
        viewModelScope.launch {
            repository.updateNotificationStatus(notificationId, "PENDING_CONFIRMATION")
            repository.updateNotificationNote(notificationId, "")
        }
    }

    companion object {
        fun factory(app: AssetsKingApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LedgerViewModel(
                        seedAccounts = app.seedAccounts,
                        recordTransaction = app.recordTransaction,
                        recordTransfer = app.recordTransfer,
                        addAccount = app.addAccount,
                        updateCategory = app.updateCategory,
                        getV5Metrics = app.getV5Metrics,
                        processPending = app.processPending,
                        spendPatterns = app.spendPatterns,
                        repository = app.repository
                    ) as T
            }
    }
}

private fun String.toCentsOrNull(allowZero: Boolean = false): Long? = runCatching {
    BigDecimal(trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}.getOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }
