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
    val windfalls by model.windfalls.collectAsStateWithLifecycle(initialValue = emptyList<WindfallEntity>())
    val cardInstallments by model.cardInstallments.collectAsStateWithLifecycle(initialValue = emptyList<CreditCardInstallmentEntity>())
    val monthlyIncomeCents by model.monthlyIncomeCents.collectAsStateWithLifecycle(initialValue = 0L)
    val necessaryLivingCents by model.necessaryLivingCents.collectAsStateWithLifecycle(initialValue = 0L)
    val optionalCategories by model.optionalCategories.collectAsStateWithLifecycle(initialValue = emptySet<String>())
    val notificationSources by model.notificationSources.collectAsStateWithLifecycle(initialValue = emptyMap<String, String>())
    val notificationWhitelist by model.notificationWhitelist.collectAsStateWithLifecycle(initialValue = emptySet<String>())
    val necessaryLivingSuggestion by model.necessaryLivingSuggestion.collectAsStateWithLifecycle()
    val detectedRecurring by model.detectedRecurring.collectAsStateWithLifecycle()
    val uncategorized by model.uncategorized.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    var recordInitialMode by remember { mutableStateOf(RecordMode.EXPENSE) }
    var recordInitialPlanId by remember { mutableStateOf<String?>(null) }
    var showPending by remember { mutableStateOf(false) }
    var showWindfall by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showReconciliation by remember { mutableStateOf(false) }
    val listenerStatus = rememberListenerStatus()

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("首页", "统计", "贷款", "设置").forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Text(when (idx) { 0 -> "🏠"; 1 -> "📊"; 2 -> "💳"; else -> "⚙️" }) },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = {
                        recordInitialMode = RecordMode.EXPENSE
                        recordInitialPlanId = null
                        showSheet = true
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
                context = context, model = model, searchQuery = searchQuery,
                editingAccount = editingAccount, editingTransaction = editingTransaction,
                onSearchChange = { searchQuery = it },
                onShowPending = { showPending = it },
                onEditAccount = { editingAccount = it },
                onEditTransaction = { editingTransaction = it },
                onShowReconciliation = { showReconciliation = true },
                onShowWindfall = { showWindfall = true }
            )
            1 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                StatsScreen(repository = repository, budgets = budgets, recurringRules = recurringRules, accounts = state.accounts, v5 = state.v5)
            }
            2 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                LoanScreen(
                    plans = loanPlans, accounts = state.accounts,
                    onSave = { model.saveLoanPlan(it) },
                    onDelete = { model.deleteLoanPlan(it) },
                    v5 = state.v5,
                    cardInstallments = cardInstallments,
                    onSaveInstallment = { model.saveCardInstallment(it) },
                    onDeleteInstallment = { model.deleteCardInstallment(it) },
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
                    }
                )
            }
            3 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
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
                    necessaryLivingCents = necessaryLivingCents,
                    onSetMonthlyIncome = { model.setMonthlyIncomeCents(it) },
                    onSetNecessaryLiving = { model.setNecessaryLivingCents(it) },
                    optionalCategories = optionalCategories,
                    onSetOptionalCategories = { model.setOptionalCategories(it) },
                    listenerStatus = listenerStatus,
                    notificationSources = notificationSources,
                    notificationWhitelist = notificationWhitelist,
                    onSetNotificationWhitelist = { model.setNotificationWhitelist(it) },
                    necessaryLivingSuggestion = necessaryLivingSuggestion,
                    detectedRecurring = detectedRecurring,
                    uncategorized = uncategorized,
                    onConfirmDetectedRecurring = { model.confirmDetectedRecurring(it) },
                    onRefreshSpendPatterns = { model.refreshSpendPatterns() }
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

    if (showWindfall) {
        WindfallSheet(
            windfalls = windfalls,
            accounts = state.accounts,
            currentTotalDebtCents = state.v5?.totalDebtCents ?: 0L,
            onSave = { model.saveWindfall(it) },
            onDelete = { model.deleteWindfall(it) },
            onMarkReceived = { id, actualCents, cashAccountId ->
                model.markWindfallReceived(id, actualCents, cashAccountId)
            },
            onDismiss = { showWindfall = false }
        )
    }

    if (showPending) {
        PendingSheet(
            items = state.pendingItems,
            accounts = state.accounts,
            viewModel = model,
            onDismiss = { showPending = false }
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
            onSave = { id, amountCents, type, category, merchant, note ->
                model.updateTransaction(id, amountCents, type, category, merchant, note)
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
