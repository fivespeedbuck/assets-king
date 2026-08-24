package com.assetsking.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.RawNotificationEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.SnapshotEntity
import com.assetsking.database.TransferEntity
import com.assetsking.database.TransactionEntity
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
import com.assetsking.usecase.UpdateCategoryUseCase
import com.assetsking.usecase.UpcomingRepayment
import com.assetsking.usecase.UpcomingRepaymentsUseCase
import com.assetsking.ui.privacy.PrivacyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch as coroutineLaunch
import java.math.BigDecimal
import java.math.RoundingMode

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

internal fun privacyDataWritesAllowed(): Boolean = !PrivacyMode.enabled

class LedgerViewModel(
    private val seedAccounts: SeedAccountsUseCase,
    private val recordTransaction: RecordTransactionUseCase,
    private val recordTransfer: RecordTransferUseCase,
    private val addAccount: AddAccountUseCase,
    private val updateCategory: UpdateCategoryUseCase,
    getV5Metrics: GetV5MetricsUseCase,
    private val processPending: ProcessPendingUseCase,
    private val repository: com.assetsking.database.LedgerRepository
) : ViewModel() {
    /** UI 发起的所有写操作统一经过这里；隐秘模式只读，系统监听仍可直接在仓储层入库。 */
    private fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job {
        if (!privacyDataWritesAllowed()) {
            return Job().also { it.complete() }
        }
        return this.coroutineLaunch(block = block)
    }

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
    val cardInstallments: Flow<List<com.assetsking.database.CreditCardInstallmentEntity>> = repository.cardInstallments
    val cardInstallmentAllocations: Flow<List<com.assetsking.database.CreditCardInstallmentAllocationEntity>> =
        repository.cardInstallmentAllocations
    val cardInstallmentSchedules: Flow<List<com.assetsking.database.CreditCardInstallmentScheduleEntity>> =
        repository.cardInstallmentSchedules
    val cardInstallmentPaymentMatches: Flow<List<com.assetsking.database.CreditCardInstallmentPaymentMatchEntity>> =
        repository.cardInstallmentPaymentMatches
    val windfalls: Flow<List<com.assetsking.database.WindfallEntity>> = repository.windfalls
    /** 一级/二级分类库（REQ 初始分类库） */
    val categories: Flow<List<com.assetsking.database.CategoryEntity>> = repository.categories
    /** 交易对象库（标准商户 + 别名 + 学习规则，REQ 商户库） */
    val merchants: Flow<List<com.assetsking.database.MerchantEntity>> = repository.merchants
    /** 最近还款提醒（REQ 首页§8）：到期前 3 天窗口 + 逾期 */
    val upcomingRepayments: Flow<List<UpcomingRepayment>> = UpcomingRepaymentsUseCase(repository).invoke()
    /** 首页已启用的可配置模块（REQ 首页可配置模块） */
    val enabledModules: Flow<Set<String>> = repository.enabledModules
    /** 自由开销额度（REQ 统计§12，初始 500 元/月） */
    val freeSpendingCents: Flow<Long> = repository.freeSpendingCents
    val customPaymentChannels: Flow<Set<String>> = repository.customPaymentChannels

    fun rememberPaymentChannel(channel: String) {
        if (!privacyDataWritesAllowed()) return
        repository.rememberPaymentChannel(channel)
    }

    fun setFreeSpendingCents(cents: Long) {
        if (!privacyDataWritesAllowed()) return
        repository.setFreeSpendingCents(cents)
    }

    /** 主题选择（REQ 主题§1：5 套，龙巢深色给 Codex） */
    val themeKey: Flow<String?> = repository.themeKey

    fun setThemeKey(key: String) {
        if (!privacyDataWritesAllowed()) return
        repository.setThemeKey(key)
    }

    fun setHomeModules(enabled: Set<String>) {
        if (!privacyDataWritesAllowed()) return
        repository.setHomeModules(enabled)
    }

    val homeModuleOrder: Flow<List<String>> = repository.moduleOrder

    fun reorderHomeModules(ordered: List<String>) {
        if (!privacyDataWritesAllowed()) return
        repository.reorderHomeModules(ordered)
    }

    fun setTransactionNecessity(id: String, necessity: Boolean?) {
        viewModelScope.launch { repository.setTransactionNecessity(id, necessity) }
    }

    fun setTransactionCategoryName(id: String, categoryName: String) {
        viewModelScope.launch { repository.setTransactionCategoryName(id, categoryName) }
    }

    init {
        // 首次启动播种分类库；账户种子由既有流程负责，不动
        viewModelScope.coroutineLaunch { repository.seedDefaultCategoriesIfEmpty() }
    }
    val monthlyIncomeCents: Flow<Long> = repository.monthlyIncomeCents
    /** 自动发现的通知来源：包名 → 应用名 */
    val notificationSources: Flow<Map<String, String>> = repository.notificationSources
    val notificationWhitelist: Flow<Set<String>> = repository.notificationWhitelist
    val smsSenderWhitelist: Flow<Set<String>> = repository.smsSenderWhitelist
    /** 最近一次原始证据入库时间（0 = 从未收到） */
    val lastReceivedAt: Flow<Long> = repository.lastReceivedAt

    init {
        viewModelScope.launch {
            seedAccounts()
            repository.processRecurring()
            repository.saveTodaySnapshot()
            // 当月无锚点 → 用当前 V5 总负债建档（从今天起记录，不回填历史）
            val v5 = getV5Metrics().first()
            repository.ensureMonthAnchor(v5.totalDebtCents)
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

    fun archiveAccount(accountId: String) {
        viewModelScope.launch { repository.archiveAccount(accountId) }
    }

    fun updateTransactionCategory(id: String, category: TransactionCategory) {
        viewModelScope.launch { updateCategory(id, category) }
    }

    fun updateTransaction(
        id: String, amountCents: Long, type: TransactionType,
        category: String, merchant: String?, note: String?,
        accountId: String, occurredAt: Long, necessity: Boolean?, channel: String?,
        isReimbursable: Boolean,
        refundOfId: String? = null
    ) {
        viewModelScope.coroutineLaunch {
            repository.updateTransaction(
                id, amountCents, type, category, merchant, note,
                accountId, occurredAt, necessity, channel, isReimbursable, refundOfId
            )
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

    fun createCardInstallment(
        draft: com.assetsking.database.CreditCardInstallmentDraft,
        onResult: (Result<String>) -> Unit = {}
    ) {
        viewModelScope.launch { onResult(runCatching { repository.createCardInstallment(draft) }) }
    }

    fun adjustCardInstallment(
        id: String,
        terms: com.assetsking.database.CreditCardInstallmentTerms,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch { onResult(runCatching { repository.adjustCardInstallmentTerms(id, terms) }) }
    }

    fun cancelCardInstallment(id: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch { onResult(runCatching { repository.deleteCardInstallment(id) }) }
    }

    fun confirmCardInstallmentPaymentMatch(
        transferId: String,
        scheduleId: String,
        principalCents: Long,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            onResult(runCatching {
                repository.confirmCardInstallmentPaymentMatch(transferId, scheduleId, principalCents)
            })
        }
    }

    fun confirmLoanPaymentNotification(
        notificationId: String,
        accountId: String,
        planId: String,
        totalCents: Long,
        principalCents: Long,
        interestCents: Long,
        feeCents: Long,
        note: String?,
        bankBalanceCents: Long?,
        bankCardTail: String?
    ) {
        viewModelScope.launch {
            repository.confirmLoanPaymentNotification(
                notificationId = notificationId,
                cashAccountId = accountId,
                planId = planId,
                totalCents = totalCents,
                principalCents = principalCents,
                interestCents = interestCents,
                feeCents = feeCents,
                note = note,
                bankBalanceCents = bankBalanceCents,
                bankCardTail = bankCardTail
            )
        }
    }

    fun setMonthlyIncomeCents(cents: Long) {
        if (!privacyDataWritesAllowed()) return
        repository.setMonthlyIncomeCents(cents)
    }

    fun setOptionalCategories(categories: Set<String>) {
        if (!privacyDataWritesAllowed()) return
        repository.setOptionalCategories(categories)
    }

    /** 通知来源开关：只有白名单里的 app 会被读取入库 */
    fun setNotificationWhitelist(packages: Set<String>) {
        if (!privacyDataWritesAllowed()) return
        repository.setNotificationWhitelist(packages)
    }

    /** 银行短信发送方白名单：实时接收与历史补扫共用。 */
    fun setSmsSenderWhitelist(senders: Set<String>) {
        if (!privacyDataWritesAllowed()) return
        repository.setSmsSenderWhitelist(senders)
    }

    /** 提前还款：本金减余额，手续费仅计入本次实际现金流，不标普通期次（铁律 3） */
    fun addLoanPrepayment(
        cashAccountId: String,
        planId: String,
        principalCents: Long,
        feeCents: Long,
        note: String?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            repository.addLoanPrepayment(cashAccountId, planId, principalCents, note, occurredAt, feeCents)
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
        channel: String? = null,
        refundOfId: String? = null
    ) {
        viewModelScope.launch {
            repository.addTransaction(
                accountId, amountCents, type, category, merchant, note,
                occurredAt = occurredAt, isReimbursable = isReimbursable,
                necessity = necessity, channel = channel, refundOfId = refundOfId
            )
        }
    }

    fun saveReimbursement(
        accountId: String,
        amountCents: Long,
        source: String?,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
        expenseIds: List<String>
    ) {
        viewModelScope.launch { repository.addReimbursement(accountId, amountCents, note, occurredAt, expenseIds, source) }
    }

    fun updateReimbursement(
        id: String,
        accountId: String,
        amountCents: Long,
        source: String?,
        note: String?,
        occurredAt: Long,
        expenseIds: List<String>
    ) {
        viewModelScope.launch {
            repository.updateReimbursement(id, accountId, amountCents, source, note, occurredAt, expenseIds)
        }
    }

    fun addCategoryEntity(name: String, shortName: String, parentId: String?, iconKey: String, defaultNecessary: Boolean?, kind: String = "EXPENSE") {
        viewModelScope.launch { repository.addCategory(name, shortName, parentId, iconKey, defaultNecessary, kind) }
    }

    fun updateCategoryEntity(id: String, name: String?, shortName: String?, parentId: String?, iconKey: String? = null) {
        viewModelScope.launch { repository.updateCategory(id, name, shortName, parentId, iconKey) }
    }

    fun archiveOrDeleteCategory(id: String) {
        viewModelScope.launch { repository.deleteCategory(id) }
    }

    fun mergeCategoryEntity(sourceId: String, targetId: String) {
        viewModelScope.launch { repository.mergeCategory(sourceId, targetId) }
    }

    fun reorderCategories(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderCategories(orderedIds) }
    }

    fun mergeMerchants(targetName: String, sourceNames: List<String>) {
        viewModelScope.launch { repository.mergeMerchants(targetName, sourceNames) }
    }

    fun deleteMerchantMapping(name: String) {
        viewModelScope.launch { repository.deleteMerchantMapping(name) }
    }

    fun confirmTransferPair(outId: String, inId: String, fromAccountId: String, toAccountId: String, amountCents: Long, note: String?) {
        viewModelScope.launch { repository.confirmTransferFromNotifications(outId, inId, fromAccountId, toAccountId, amountCents, note) }
    }

    fun confirmTransferNotification(
        notificationId: String,
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        feeCents: Long,
        note: String?
    ) {
        viewModelScope.launch {
            repository.confirmTransferFromNotification(
                notificationId, fromAccountId, toAccountId, amountCents, feeCents, note
            )
        }
    }

    fun updateLoanInstallment(planId: String, number: Int, dueDateEpochDay: Long?, principalCents: Long?, interestCents: Long?, feeCents: Long?, status: String?) {
        viewModelScope.launch { repository.updateLoanInstallment(planId, number, dueDateEpochDay, principalCents, interestCents, feeCents, status) }
    }

    suspend fun checkpointsFor(accountId: String) = repository.checkpointsFor(accountId)

    suspend fun adjustmentsFor(accountId: String) = repository.adjustmentsFor(accountId)

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
        val statusRevision = AssetsNotificationListenerService.captureRuntimeStatusRevision()
        viewModelScope.launch {
            runCatching { processPending.invoke() }
                .onFailure { AssetsNotificationListenerService.reportIngestionFailure(statusRevision) }
        }
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
        channel: String? = null,
        refundOfId: String? = null
    ) {
        viewModelScope.launch {
            repository.confirmNotification(
                notificationId, accountId, amountCents, type, category, merchant, note,
                bankBalanceCents, bankCardTail, necessity, channel, refundOfId
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
                        repository = app.repository
                    ) as T
            }
    }
}

private fun String.toCentsOrNull(allowZero: Boolean = false): Long? = runCatching {
    BigDecimal(trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}.getOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }
