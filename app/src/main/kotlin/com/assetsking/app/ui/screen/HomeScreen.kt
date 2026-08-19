package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.app.RecordMode
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.CustomCategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.WindfallEntity
import com.assetsking.ui.theme.AssetsKingTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: LedgerViewModel, repository: LedgerRepository) {
    val state by model.state.collectAsStateWithLifecycle()
    val budgets by model.budgets.collectAsStateWithLifecycle(initialValue = emptyList<BudgetEntity>())
    val loanPlans by model.loanPlans.collectAsStateWithLifecycle(initialValue = emptyList<LoanPlanEntity>())
    val recurringRules by model.recurringRules.collectAsStateWithLifecycle(initialValue = emptyList<RecurringRuleEntity>())
    val customCategories by model.customCategories.collectAsStateWithLifecycle(initialValue = emptyList<CustomCategoryEntity>())
    val categories by model.categories.collectAsStateWithLifecycle(initialValue = emptyList<com.assetsking.database.CategoryEntity>())
    val merchants by model.merchants.collectAsStateWithLifecycle(initialValue = emptyList<com.assetsking.database.MerchantEntity>())
    val reimbursable by model.reimbursable.collectAsStateWithLifecycle(initialValue = emptyList<TransactionEntity>())
    val upcomingRepayments by model.upcomingRepayments.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledModules by model.enabledModules.collectAsStateWithLifecycle(initialValue = emptySet<String>())
    val moduleOrder by model.homeModuleOrder.collectAsStateWithLifecycle(initialValue = emptyList<String>())
    val freeSpendingCents by model.freeSpendingCents.collectAsStateWithLifecycle(initialValue = 50_000L)
    val themeKey by model.themeKey.collectAsStateWithLifecycle(initialValue = null)
    val windfalls by model.windfalls.collectAsStateWithLifecycle(initialValue = emptyList<WindfallEntity>())
    val cardInstallments by model.cardInstallments.collectAsStateWithLifecycle(initialValue = emptyList<CreditCardInstallmentEntity>())
    val monthlyIncomeCents by model.monthlyIncomeCents.collectAsStateWithLifecycle(initialValue = 0L)
    val notificationSources by model.notificationSources.collectAsStateWithLifecycle(initialValue = emptyMap<String, String>())
    val notificationWhitelist by model.notificationWhitelist.collectAsStateWithLifecycle(initialValue = emptySet<String>())
    val lastReceivedAt by model.lastReceivedAt.collectAsStateWithLifecycle(initialValue = 0L)
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    var recordInitialMode by remember { mutableStateOf(RecordMode.EXPENSE) }
    var recordInitialPlanId by remember { mutableStateOf<String?>(null) }
    var showPending by remember { mutableStateOf(false) }
    var showPendingBox by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editorPendingItem by remember { mutableStateOf<PendingItem?>(null) }
    var showBills by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showReconciliation by remember { mutableStateOf(false) }
    // 统计页下钻流水（REQ 统计§3/§7/§20）：月份+分类带进流水页筛选，消费一次后清空
    var txDrillMonth by remember { mutableStateOf<java.time.YearMonth?>(null) }
    var txDrillCategory by remember { mutableStateOf<String?>(null) }
    // 贷款页悬浮＋脉冲（REQ 贷款页§16）
    var loanAddPulse by remember { mutableStateOf(0) }
    val listenerStatus = rememberListenerStatus()

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("首页", "统计", "账单", "贷款", "设置").forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Text(when (idx) { 0 -> "🏠"; 1 -> "📊"; 2 -> "📅"; 3 -> "💳"; else -> "⚙️" }) },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            // 手动记账入口在流水页（REQ 流水§6），不占首页；贷款新增用贷款页右下角悬浮＋（REQ 贷款页§16）
            if (selectedTab == 2 || selectedTab == 3) {
                FloatingActionButton(
                    onClick = {
                        if (selectedTab == 2) {
                            editorPendingItem = null
                            showEditor = true
                        } else {
                            loanAddPulse++
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (selectedTab) {
            0 -> HomeTab(
                padding = padding, state = state, listenerStatus = listenerStatus,
                lastReceivedAt = lastReceivedAt,
                context = context, model = model, repository = repository,
                budgets = budgets, recurringRules = recurringRules,
                upcomingRepayments = upcomingRepayments,
                enabledModules = enabledModules,
                moduleOrder = moduleOrder,
                onShowPending = { showPendingBox = true },
                onShowReconciliation = { showReconciliation = true },
                onGotoStats = { selectedTab = 1 },
                onGotoLoans = { selectedTab = 3 },
                onGotoBills = { showBills = true },
                onEditAccount = { editingAccount = it }
            )
            1 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                StatsScreen(
                    state = state,
                    categories = categories,
                    budgets = budgets,
                    repository = repository,
                    freeSpendingCents = freeSpendingCents,
                    onGotoTransactions = { m, cat ->
                        txDrillMonth = m; txDrillCategory = cat; selectedTab = 2
                    }
                )
            }
            2 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                TransactionsScreen(
                    state = state,
                    categories = categories,
                    merchants = merchants,
                    model = model,
                    onOpenEditor = {
                        editorPendingItem = null
                        showEditor = true
                    },
                    onEditTransaction = { editingTransaction = it },
                    initialFilterMonth = txDrillMonth,
                    initialFilterCategory = txDrillCategory,
                    onDrillConsumed = { txDrillMonth = null; txDrillCategory = null }
                )
            }
            3 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                LoanScreen(
                    plans = loanPlans, accounts = state.accounts,
                    onSave = { model.saveLoanPlan(it) },
                    onDelete = { model.deleteLoanPlan(it) },
                    v5 = state.v5,
                    cardInstallments = cardInstallments,
                    onSaveInstallment = { model.saveCardInstallment(it) },
                    onDeleteInstallment = { model.deleteCardInstallment(it) },
                    transactions = state.transactions,
                    onRecordPayment = { plan ->
                        recordInitialMode = RecordMode.LOAN_PAYMENT
                        recordInitialPlanId = plan.id
                        showSheet = true
                    },
                    onPrepay = { cashId, planId, principalCents, note ->
                        model.addLoanPrepayment(cashId, planId, principalCents, note)
                    },
                    onSettle = { cashId, planId, principalCents, interestCents, feeCents, note ->
                        model.settleLoanPlan(cashId, planId, principalCents, interestCents, feeCents, note)
                    },
                    onUpdateInstallment = { planId, number, p, i, f, st ->
                        model.updateLoanInstallment(planId, number, p, i, f, st)
                    },
                    addPulse = loanAddPulse
                )
            }
            4 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                SettingsScreen(
                    budgets = budgets, repository = repository,
                    recurringRules = recurringRules, accounts = state.accounts,
                    customCategories = customCategories,
                    onSaveBudget = { model.saveBudget(it) },
                    onDeleteBudget = { model.deleteBudget(it) },
                    onSaveRecurring = { model.saveRecurringRule(it) },
                    onDeleteRecurring = { model.deleteRecurringRule(it) },
                    onAddCustomCategory = { model.addCustomCategory(it) },
                    onDeleteCustomCategory = { model.deleteCustomCategory(it) },
                    monthlyIncomeCents = monthlyIncomeCents,
                    onSetMonthlyIncome = { model.setMonthlyIncomeCents(it) },
                    listenerStatus = listenerStatus,
                    notificationSources = notificationSources,
                    notificationWhitelist = notificationWhitelist,
                    onSetNotificationWhitelist = { model.setNotificationWhitelist(it) },
                    windfalls = windfalls,
                    currentTotalDebtCents = state.v5?.totalDebtCents ?: 0L,
                    onSaveWindfall = { model.saveWindfall(it) },
                    onDeleteWindfall = { model.deleteWindfall(it) },
                    onMarkWindfallReceived = { id, actualCents, cashAccountId ->
                        model.markWindfallReceived(id, actualCents, cashAccountId)
                    },
                    freeSpendingCents = freeSpendingCents,
                    onSetFreeSpending = { model.setFreeSpendingCents(it) },
                    themeKey = themeKey,
                    onSetTheme = { model.setThemeKey(it) }
                )
            }
        }
    }

    // ── Global Sheets ──
    if (showSheet) {
        NewRecordSheet(
            state = state,
            categorize = model::categorize,
            onSaveTransaction = { aid, amount, mode, catStr, merchant, note, occurredAt, isReimbursable ->
                model.addTransaction(aid, amount, mode, catStr, merchant, note, occurredAt, isReimbursable)
                showSheet = false
            },
            onSaveTransfer = { from, to, amount, note, occurredAt ->
                model.addTransfer(from, to, amount, note, occurredAt)
                showSheet = false
            },
            onSaveLoanDisbursement = { aid, amount, planId, note, occurredAt ->
                model.addLoanDisbursement(aid, amount, planId, note, occurredAt)
                showSheet = false
            },
            onSaveLoanPayment = { aid, planId, total, principal, interest, fee, note, occurredAt ->
                model.addLoanPayment(aid, planId, total, principal, interest, fee, note, occurredAt)
                showSheet = false
            },
            onAddAccount = { name, type, balance, card, stmtDay, dueDay, limit ->
                model.addAccount(name, type, balance, card, stmtDay, dueDay, limit)
            },
            onDismiss = { showSheet = false },
            loanPlans = loanPlans,
            customCategoryNames = customCategories.map { it.name },
            initialMode = recordInitialMode,
            initialPlanId = recordInitialPlanId
        )
    }

    // 待确认箱全屏页（REQ 待确认箱 UI）：覆盖在 Scaffold 之上
    // 周期账单页（不占底部导航，从首页「本月待扣」模块进入，REQ 导航§3-4）
    if (showBills) {
        BillsScreen(
            rules = recurringRules,
            transactions = state.transactions,
            pendingItems = state.pendingItems,
            accounts = state.accounts,
            viewModel = model,
            onBack = { showBills = false }
        )
    }

    if (showPendingBox) {
        PendingBoxScreen(
            items = state.pendingItems,
            accounts = state.accounts,
            merchantLastAccount = state.merchantLastAccount,
            viewModel = model,
            lastReceivedAt = lastReceivedAt,
            listenerStatus = listenerStatus,
            onOpenEditor = { item ->
                editorPendingItem = item
                showEditor = true
            },
            onBack = { showPendingBox = false }
        )
    }

    // 统一编辑器（M4）：手动记账与待确认复用，覆盖在最上层
    if (showEditor) {
        TransactionEditorScreen(
            pendingItem = editorPendingItem,
            accounts = state.accounts,
            categories = categories,
            merchants = merchants,
            loanPlans = loanPlans,
            transactions = state.transactions,
            reimbursableTxs = reimbursable,
            merchantLastAccount = state.merchantLastAccount,
            ignoredItems = state.ignoredItems,
            viewModel = model,
            repository = repository,
            onDone = { showEditor = false },
            onBack = { showEditor = false }
        )
    }

    editingAccount?.let { account ->
        EditAccountSheet(
            account = account,
            onSave = { model.updateAccount(it); editingAccount = null },
            onDelete = { model.deleteAccount(it); editingAccount = null },
            onDismiss = { editingAccount = null }
        )
    }

    editingTransaction?.let { tx ->
        EditTransactionSheet(
            transaction = tx,
            accountName = state.accounts.firstOrNull { it.id == tx.accountId }?.name.orEmpty(),
            accounts = state.accounts,
            onSave = { id, amountCents, type, category, merchant, note, accountId, occurredAt, necessity ->
                model.updateTransaction(id, amountCents, type, category, merchant, note, accountId, occurredAt, necessity)
                editingTransaction = null
            },
            onDelete = { model.deleteTransaction(it); editingTransaction = null },
            onDismiss = { editingTransaction = null },
            recurringRules = recurringRules,
            onLinkToRule = { txId, ruleId -> model.linkToRecurringRule(txId, ruleId) },
            customCategoryNames = customCategories.map { it.name }
        )
    }

    if (showReconciliation) {
        ReconciliationSheet(
            accounts = state.accounts,
            onReconcile = { model.reconcileAccount(it) },
            onDismiss = { showReconciliation = false }
        )
    }
}
