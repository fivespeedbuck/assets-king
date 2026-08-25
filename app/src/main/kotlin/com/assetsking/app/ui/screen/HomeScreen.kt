package com.assetsking.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.app.R
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.WindfallEntity
import com.assetsking.model.AccountType
import com.assetsking.ui.theme.AssetsKingTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    model: LedgerViewModel,
    repository: LedgerRepository,
    privacyEnabled: Boolean,
    onTogglePrivacy: () -> Unit
) {
    val state by model.state.collectAsStateWithLifecycle()
    val budgets by model.budgets.collectAsStateWithLifecycle(initialValue = emptyList<BudgetEntity>())
    val loanPlans by model.loanPlans.collectAsStateWithLifecycle(initialValue = emptyList<LoanPlanEntity>())
    val recurringRules by model.recurringRules.collectAsStateWithLifecycle(initialValue = emptyList<RecurringRuleEntity>())
    val categories by model.categories.collectAsStateWithLifecycle(initialValue = emptyList<com.assetsking.database.CategoryEntity>())
    val merchants by model.merchants.collectAsStateWithLifecycle(initialValue = emptyList<com.assetsking.database.MerchantEntity>())
    val reimbursable by model.reimbursable.collectAsStateWithLifecycle(initialValue = emptyList<TransactionEntity>())
    val freeSpendingCents by model.freeSpendingCents.collectAsStateWithLifecycle(initialValue = 50_000L)
    val customPaymentChannels by model.customPaymentChannels.collectAsStateWithLifecycle(initialValue = emptySet())
    val windfalls by model.windfalls.collectAsStateWithLifecycle(initialValue = emptyList<WindfallEntity>())
    val cardInstallments by model.cardInstallments.collectAsStateWithLifecycle(initialValue = emptyList<CreditCardInstallmentEntity>())
    val cardInstallmentAllocations by model.cardInstallmentAllocations.collectAsStateWithLifecycle(initialValue = emptyList())
    val cardInstallmentSchedules by model.cardInstallmentSchedules.collectAsStateWithLifecycle(initialValue = emptyList())
    val cardInstallmentPaymentMatches by model.cardInstallmentPaymentMatches.collectAsStateWithLifecycle(initialValue = emptyList())
    val monthlyIncomeCents by model.monthlyIncomeCents.collectAsStateWithLifecycle(initialValue = 0L)
    val notificationSources by model.notificationSources.collectAsStateWithLifecycle(initialValue = emptyMap<String, String>())
    val notificationWhitelist by model.notificationWhitelist.collectAsStateWithLifecycle(initialValue = emptySet<String>())
    val smsSenderWhitelist by model.smsSenderWhitelist.collectAsStateWithLifecycle(initialValue = emptySet<String>())
    val lastReceivedAt by model.lastReceivedAt.collectAsStateWithLifecycle(initialValue = 0L)
    val deletedTransactions by model.deletedTransactions.collectAsStateWithLifecycle(initialValue = emptyList<TransactionEntity>())
    val deletedTransfers by model.deletedTransfers.collectAsStateWithLifecycle(initialValue = emptyList<com.assetsking.database.TransferEntity>())
    val context = LocalContext.current
    var showPendingBox by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editorPendingItem by remember { mutableStateOf<PendingItem?>(null) }
    var editorInitialLoanPlanId by remember { mutableStateOf<String?>(null) }
    var showBills by remember { mutableStateOf(false) }
    var showReimbursement by remember { mutableStateOf(false) }
    var addingAccountType by remember { mutableStateOf<AccountType?>(null) }
    var editingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var accountDetail by remember { mutableStateOf<AccountEntity?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var detailTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var settingsAtRoot by remember { mutableStateOf(true) }
    // 只有“从页面内容进入另一个主区”的动作记录来源；用户直接点底部导航仍视为切换一级页面。
    // 这样统计下钻→查看流水、首页卡片→统计/贷款都能按返回手势回到来源页。
    var previousTab by remember { mutableStateOf<Int?>(null) }
    var showReconciliation by remember { mutableStateOf(false) }
    // 统计页下钻流水（REQ 统计§3/§7/§20）：月份+分类带进流水页筛选，消费一次后清空
    var txDrillMonth by remember { mutableStateOf<java.time.YearMonth?>(null) }
    var txDrillCategory by remember { mutableStateOf<String?>(null) }
    var txDrillStart by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var txDrillEnd by remember { mutableStateOf<java.time.LocalDate?>(null) }
    val listenerStatus = rememberListenerStatus()
    val navSurface = MaterialTheme.colorScheme.surfaceContainer
    val navOutline = MaterialTheme.colorScheme.outlineVariant
    val navPrimary = MaterialTheme.colorScheme.primary

    BackHandler(
        enabled = showEditor || showPendingBox || showBills || showReimbursement || accountDetail != null ||
            detailTransaction != null || previousTab != null
    ) {
        when {
            showEditor -> {
                showEditor = false
                editorPendingItem = null
                editingTransaction = null
                editorInitialLoanPlanId = null
            }
            detailTransaction != null -> detailTransaction = null
            accountDetail != null -> accountDetail = null
            showPendingBox -> showPendingBox = false
            showReimbursement -> showReimbursement = false
            showBills -> showBills = false
            previousTab != null -> {
                selectedTab = previousTab!!
                previousTab = null
            }
        }
    }

    fun openTabFromContent(tab: Int) {
        if (selectedTab != tab) {
            previousTab = selectedTab
            selectedTab = tab
        }
    }
    val navPrimaryContainer = MaterialTheme.colorScheme.primaryContainer
    val navOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    drawLine(
                        color = navOutline,
                        start = Offset(0f, strokeWidth / 2f),
                        end = Offset(size.width, strokeWidth / 2f),
                        strokeWidth = strokeWidth
                    )
                },
                containerColor = navSurface,
                tonalElevation = 0.dp
            ) {
                listOf("首页", "统计", "流水", "贷款", "设置").forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = {
                            selectedTab = idx
                            previousTab = null
                        },
                        // 贴底扁平导航（REQ 视觉§8）：当前项小底块+主题色，其余中性次要色
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = navPrimary,
                            selectedTextColor = navPrimary,
                            unselectedIconColor = navOnSurfaceVariant,
                            unselectedTextColor = navOnSurfaceVariant,
                            indicatorColor = Color.Transparent
                        ),
                        // Outlined/AutoMirrored 线性图标+中文，不用 Emoji（REQ 视觉§4）
                        icon = {
                            Box(contentAlignment = Alignment.Center) {
                                if (selectedTab == idx) {
                                    Box(
                                        Modifier
                                            .size(28.dp)
                                            .background(
                                                navPrimaryContainer,
                                                RoundedCornerShape(8.dp)
                                            )
                                    )
                                }
                                Icon(
                                    when (idx) {
                                        0 -> Icons.Outlined.Home
                                        1 -> Icons.Outlined.BarChart
                                        2 -> Icons.AutoMirrored.Outlined.ReceiptLong
                                        3 -> Icons.Outlined.CreditCard
                                        else -> Icons.Outlined.Settings
                                    },
                                    modifier = Modifier.size(20.dp),
                                    contentDescription = label
                                )
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            // 手动记账入口仅在流水页；贷款新增收进该页标题行，避免 FAB 遮挡长列表。
            if (selectedTab == 2 && !privacyEnabled) {
                FloatingActionButton(
                    onClick = {
                        editorPendingItem = null
                        editingTransaction = null
                        editorInitialLoanPlanId = null
                        showEditor = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Icon(Icons.Filled.Add, contentDescription = "新增") }
            }
        },
        containerColor = if (privacyEnabled) Color.Transparent else MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { padding ->
        when (selectedTab) {
            0 -> HomeTab(
                padding = padding, state = state, listenerStatus = listenerStatus,
                lastReceivedAt = lastReceivedAt,
                context = context,
                budgets = budgets, categories = categories, recurringRules = recurringRules,
                loanPlans = loanPlans,
                cardInstallments = cardInstallments,
                cardInstallmentSchedules = cardInstallmentSchedules,
                freeSpendingCents = freeSpendingCents,
                privacyEnabled = privacyEnabled,
                onTogglePrivacy = onTogglePrivacy,
                onShowPending = {
                    model.processNotifications()
                    showPendingBox = true
                },
                onShowReconciliation = { showReconciliation = true },
                onGotoStats = { openTabFromContent(1) },
                onGotoLoans = { openTabFromContent(3) },
                onGotoBills = { showBills = true },
                onGotoReimbursement = { showReimbursement = true },
                onEditAccount = { accountDetail = it },
                onAddAccount = { if (!privacyEnabled) addingAccountType = it }
            )
            1 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                StatsScreen(
                    state = state,
                    categories = categories,
                    budgets = budgets,
                    repository = repository,
                    freeSpendingCents = freeSpendingCents,
                    onGotoTransactions = { m, cat ->
                        txDrillMonth = m
                        txDrillCategory = cat
                        openTabFromContent(2)
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
                        if (!privacyEnabled) {
                            editorPendingItem = null
                            editingTransaction = null
                            editorInitialLoanPlanId = null
                            showEditor = true
                        }
                    },
                    onEditTransaction = { transaction ->
                        if (!privacyEnabled) {
                            val type = runCatching { com.assetsking.model.TransactionType.valueOf(transaction.type) }.getOrNull()
                            if (type != null && isOrdinaryEditableTransaction(type)) {
                                editorPendingItem = null
                                editingTransaction = transaction
                                editorInitialLoanPlanId = null
                                showEditor = true
                            } else {
                                detailTransaction = transaction
                            }
                        }
                    },
                    initialFilterMonth = txDrillMonth,
                    initialFilterCategory = txDrillCategory,
                    initialFilterStart = txDrillStart,
                    initialFilterEnd = txDrillEnd,
                    onDrillConsumed = {
                        txDrillMonth = null
                        txDrillCategory = null
                        txDrillStart = null
                        txDrillEnd = null
                    }
                )
            }
            3 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                LoanScreen(
                    plans = loanPlans, accounts = state.accounts,
                    onSave = { model.saveLoanPlan(it) },
                    onDelete = { model.deleteLoanPlan(it) },
                    onAddLoanAccount = { if (!privacyEnabled) addingAccountType = AccountType.LOAN },
                    onAddCreditAccount = { if (!privacyEnabled) addingAccountType = AccountType.CREDIT },
                    v5 = state.v5,
                    cardInstallments = cardInstallments,
                    cardInstallmentAllocations = cardInstallmentAllocations,
                    cardInstallmentSchedules = cardInstallmentSchedules,
                    cardInstallmentPaymentMatches = cardInstallmentPaymentMatches,
                    transfers = state.transfers,
                    onCreateInstallment = { draft, callback -> model.createCardInstallment(draft, callback) },
                    onAdjustInstallment = { id, terms, callback -> model.adjustCardInstallment(id, terms, callback) },
                    onCancelInstallment = { id, callback -> model.cancelCardInstallment(id, callback) },
                    onConfirmInstallmentPayment = { transferId, scheduleId, principalCents, callback ->
                        model.confirmCardInstallmentPaymentMatch(transferId, scheduleId, principalCents, callback)
                    },
                    onOpenCreditAccount = { account -> if (!privacyEnabled) accountDetail = account },
                    transactions = state.transactions,
                    onRecordPayment = { plan ->
                        if (!privacyEnabled) {
                            editorPendingItem = null
                            editingTransaction = null
                            editorInitialLoanPlanId = plan.id
                            showEditor = true
                        }
                    },
                    onPrepay = { cashId, planId, principalCents, feeCents, note ->
                        model.addLoanPrepayment(cashId, planId, principalCents, feeCents, note)
                    },
                    onSettle = { cashId, planId, principalCents, interestCents, feeCents, note ->
                        model.settleLoanPlan(cashId, planId, principalCents, interestCents, feeCents, note)
                    },
                    onUpdateInstallment = { planId, number, dueDay, p, i, f, st ->
                        model.updateLoanInstallment(planId, number, dueDay, p, i, f, st)
                    }
                )
            }
            4 -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                SettingsScreen(
                    budgets = budgets,
                    categories = categories,
                    repository = repository,
                    accounts = state.accounts,
                    onSaveBudget = { model.saveBudget(it) },
                    onDeleteBudget = { model.deleteBudget(it) },
                    monthlyIncomeCents = monthlyIncomeCents,
                    onSetMonthlyIncome = { model.setMonthlyIncomeCents(it) },
                    listenerStatus = listenerStatus,
                    lastReceivedAt = lastReceivedAt,
                    notificationSources = notificationSources,
                    notificationWhitelist = notificationWhitelist,
                    onSetNotificationWhitelist = { model.setNotificationWhitelist(it) },
                    smsSenderWhitelist = smsSenderWhitelist,
                    onSetSmsSenderWhitelist = { model.setSmsSenderWhitelist(it) },
                    freeSpendingCents = freeSpendingCents,
                    onSetFreeSpending = { model.setFreeSpendingCents(it) },
                    deletedTransactions = deletedTransactions,
                    deletedTransfers = deletedTransfers,
                    onRestoreTransaction = { id, callback -> model.restoreTransactionFromTrash(id, callback) },
                    onPermanentlyDeleteTransaction = { id, callback -> model.permanentlyDeleteTransactionFromTrash(id, callback) },
                    onRestoreTransfer = { id, callback -> model.restoreTransferFromTrash(id, callback) },
                    onPermanentlyDeleteTransfer = { id, callback -> model.permanentlyDeleteTransferFromTrash(id, callback) },
                    onRootStateChanged = { settingsAtRoot = it }
                )
                if (settingsAtRoot && privacyEnabled) {
                    // 不再叠加第二枚小徽记；透明点击区覆盖隐秘背景原有的大徽记。
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.90f)
                            .aspectRatio(1f)
                            .padding(bottom = 96.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTogglePrivacy
                            )
                    )
                } else if (settingsAtRoot) {
                    // 非隐秘设置页复用同一枚大徽记：低透明紫色背景，同时作为进入入口。
                    Image(
                        painter = painterResource(R.drawable.ic_privacy_emblem_fog),
                        contentDescription = "进入隐秘模式",
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(Color(0xFF7257B6)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.90f)
                            .aspectRatio(1f)
                            .padding(bottom = 96.dp)
                            .graphicsLayer { alpha = 0.10f }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTogglePrivacy
                            )
                    )
                }
            }
        }
    }

    addingAccountType?.let { initialType ->
        AddAccountSheet(
            initialType = initialType,
            onAddAccount = { name, type, balance, card, stmtDay, dueDay, limit ->
                model.addAccount(name, type, balance, card, stmtDay, dueDay, limit)
            },
            onDismiss = { addingAccountType = null }
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
            onOpenTransaction = { transaction ->
                if (!privacyEnabled) {
                    editorPendingItem = null
                    editingTransaction = transaction
                    editorInitialLoanPlanId = null
                    showEditor = true
                }
            },
            onBack = { showBills = false }
        )
    }

    // 报销栏目（REQ 报销§2/§6）：首页待报销模块点击进完整列表
    if (showReimbursement) {
        ReimbursementScreen(
            transactions = state.transactions,
            onOpenTransaction = { transaction ->
                if (!privacyEnabled) {
                    editorPendingItem = null
                    editingTransaction = transaction
                    editorInitialLoanPlanId = null
                    showEditor = true
                }
            },
            onBack = { showReimbursement = false }
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
                if (!privacyEnabled) {
                    editorPendingItem = item
                    editingTransaction = null
                    editorInitialLoanPlanId = null
                    showEditor = true
                }
            },
            onBack = { showPendingBox = false }
        )
    }

    // 统一编辑器（M4）：手动记账与待确认复用，覆盖在最上层
    if (showEditor && !privacyEnabled) {
        TransactionEditorScreen(
            pendingItem = editorPendingItem,
            editingTransaction = editingTransaction,
            initialLoanPlanId = editorInitialLoanPlanId,
            accounts = state.accounts,
            categories = categories,
            merchants = merchants,
            loanPlans = loanPlans,
            transactions = state.transactions,
            reimbursableTxs = outstandingReimbursements(reimbursable),
            merchantLastAccount = state.merchantLastAccount,
            savedPaymentChannels = customPaymentChannels,
            ignoredItems = state.ignoredItems,
            viewModel = model,
            repository = repository,
            onDone = {
                showEditor = false
                editorPendingItem = null
                editingTransaction = null
                editorInitialLoanPlanId = null
            },
            onBack = {
                showEditor = false
                editorPendingItem = null
                editingTransaction = null
                editorInitialLoanPlanId = null
            }
        )
    }

    if (!privacyEnabled) editingAccount?.let { account ->
        EditAccountSheet(
            account = account,
            onSave = {
                model.updateAccount(it)
                accountDetail = it
                editingAccount = null
            },
            onArchive = {
                model.archiveAccount(it)
                editingAccount = null
                accountDetail = null
            },
            onDismiss = { editingAccount = null }
        )
    }

    // 账户详情页（REQ 账户对账§1-3/§8/§12-13）
    accountDetail?.let { account ->
        AccountDetailScreen(
            account = account,
            viewModel = model,
            transactions = state.transactions,
            statementRemainingCents = state.v5?.cardRemainingDueByCard?.get(account.id)
                ?: account.statementOriginalDueCents,
            onOpenTransaction = { transaction -> if (!privacyEnabled) detailTransaction = transaction },
            onEdit = { if (!privacyEnabled) editingAccount = account },
            onReconcile = { if (!privacyEnabled) showReconciliation = true },
            onBack = { accountDetail = null }
        )
    }

    if (!privacyEnabled) detailTransaction?.let { tx ->
        ManagedTransactionDetailSheet(
            transaction = tx,
            accountName = state.accounts.firstOrNull { it.id == tx.accountId }?.name.orEmpty(),
            onDismiss = { detailTransaction = null }
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
