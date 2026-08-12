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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetsking.app.LedgerViewModel
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CustomCategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.ui.theme.AssetsKingTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: LedgerViewModel, repository: LedgerRepository) {
    val state by model.state.collectAsStateWithLifecycle()
    val budgets by model.budgets.collectAsStateWithLifecycle(initialValue = emptyList<BudgetEntity>())
    val loanPlans by model.loanPlans.collectAsStateWithLifecycle(initialValue = emptyList<LoanPlanEntity>())
    val recurringRules by model.recurringRules.collectAsStateWithLifecycle(initialValue = emptyList<RecurringRuleEntity>())
    val customCategories by model.customCategories.collectAsStateWithLifecycle(initialValue = emptyList<CustomCategoryEntity>())
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    var showPending by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showReconciliation by remember { mutableStateOf(false) }
    val listenerEnabled = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)

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
                    onClick = { showSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (selectedTab) {
            0 -> HomeTab(
                padding = padding, state = state, listenerEnabled = listenerEnabled,
                context = context, model = model, searchQuery = searchQuery,
                editingAccount = editingAccount, editingTransaction = editingTransaction,
                onSearchChange = { searchQuery = it },
                onShowPending = { showPending = it },
                onEditAccount = { editingAccount = it },
                onEditTransaction = { editingTransaction = it },
                onShowReconciliation = { showReconciliation = true }
            )
            1 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                StatsScreen(repository = repository, budgets = budgets, recurringRules = recurringRules, accounts = state.accounts)
            }
            2 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                LoanScreen(
                    plans = loanPlans, accounts = state.accounts,
                    onSave = { model.saveLoanPlan(it) },
                    onDelete = { model.deleteLoanPlan(it) }
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
                    onDeleteCustomCategory = { model.deleteCustomCategory(it) }
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
            onSaveTransfer = { from, to, amount, note ->
                model.addTransfer(from, to, amount, note)
                showSheet = false
            },
            onAddAccount = { name, type, balance, card, stmtDay, dueDay, limit ->
                model.addAccount(name, type, balance, card, stmtDay, dueDay, limit)
            },
            onDismiss = { showSheet = false },
            customCategoryNames = customCategories.map { it.name }
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
