package com.assetsking.app.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetsking.app.LedgerUiState
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.RecordMode
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.EmptyState
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.SectionHeader
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.accountTypeLabel
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.theme.CardShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: LedgerViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val listenerEnabled = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资产大王", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text("＋", style = MaterialTheme.typography.headlineSmall)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Notification permission prompt
            if (!listenerEnabled) {
                item {
                    GlassCard {
                        Text("自动记账尚未开启", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "开启通知读取后，即使从最近任务划掉界面，系统仍可继续投递通知。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("去开启") }
                    }
                }
            }

            // Net worth overview
            item {
                GlassCard {
                    Text(
                        text = "当前净资产",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatMoney(state.overview.netWorth),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "资产 ${formatMoney(state.overview.totalAssets)}  ·  待还 ${formatMoney(state.overview.totalDebts)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.unprocessedNotifications > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "待处理通知 ${state.unprocessedNotifications} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Accounts section
            item { SectionHeader("账户") }
            items(state.accounts, key = { it.id }) { account ->
                AccountRow(account = account)
            }

            // Transactions section
            item { SectionHeader("最近流水") }
            if (state.transactions.isEmpty()) {
                item { EmptyState("还没有流水，点右下角 ＋ 开始。") }
            } else {
                items(state.transactions.take(20), key = { it.id }) { tx ->
                    val accountName = state.accounts
                        .firstOrNull { it.id == tx.accountId }
                        ?.name.orEmpty()
                    TransactionRow(
                        transaction = tx,
                        accountName = accountName,
                        onCategoryChange = { id, cat -> model.updateTransactionCategory(id, cat) }
                    )
                }
            }
        }
    }

    if (showSheet) {
        NewRecordSheet(
            state = state,
            categorize = model::categorize,
            onSaveTransaction = { aid, amount, mode, cat, merchant, note ->
                model.addTransaction(aid, amount, mode, cat, merchant, note)
                showSheet = false
            },
            onSaveTransfer = { from, to, amount, note ->
                model.addTransfer(from, to, amount, note)
                showSheet = false
            },
            onAddAccount = { name, type, balance, card ->
                model.addAccount(name, type, balance, card)
            },
            onDismiss = { showSheet = false }
        )
    }
}

// ── Domain-specific thin wrappers ──

@Composable
private fun AccountRow(account: AccountEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(account.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            Text(
                accountTypeLabel(account.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatMoney(account.balanceCents),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun TransactionRow(
    transaction: TransactionEntity,
    accountName: String,
    onCategoryChange: (String, TransactionCategory) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val category = runCatching { TransactionCategory.valueOf(transaction.category) }
        .getOrDefault(TransactionCategory.UNCATEGORIZED)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    transaction.merchant ?: categoryLabel(category),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "$accountName · ${transaction.type} · ${formatTime(transaction.occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                formatMoney(transaction.amountCents),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                categoryLabel(category),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { menuExpanded = true }
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                TransactionCategory.entries.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(categoryLabel(cat)) },
                        onClick = { menuExpanded = false; onCategoryChange(transaction.id, cat) }
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}

// ── New Record Sheet ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewRecordSheet(
    state: LedgerUiState,
    categorize: (String?, String?) -> TransactionCategory,
    onSaveTransaction: (String, String, RecordMode, TransactionCategory, String?, String?) -> Unit,
    onSaveTransfer: (String, String, String, String?) -> Unit,
    onAddAccount: (String, AccountType, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(0) }
    val accounts = state.accounts

    Sheet(title = "记一笔", onDismiss = onDismiss) {
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("记录", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Text("加账户", modifier = Modifier.padding(12.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        when (activeTab) {
            0 -> RecordTab(accounts, categorize, onSaveTransaction, onSaveTransfer)
            1 -> AddAccountTab { name, type, balance, tail ->
                onAddAccount(name, type, balance, tail)
                onDismiss()
            }
        }
    }
}

@Composable
private fun RecordTab(
    accounts: List<AccountEntity>,
    categorize: (String?, String?) -> TransactionCategory,
    onSaveTransaction: (String, String, RecordMode, TransactionCategory, String?, String?) -> Unit,
    onSaveTransfer: (String, String, String, String?) -> Unit
) {
    var mode by remember { mutableStateOf(RecordMode.EXPENSE) }
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var accountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var targetId by remember(accounts) { mutableStateOf(accounts.drop(1).firstOrNull()?.id ?: accounts.firstOrNull()?.id.orEmpty()) }
    var category by remember { mutableStateOf(TransactionCategory.UNCATEGORIZED) }
    var categoryWasChosen by remember { mutableStateOf(false) }

    LaunchedEffect(merchant, note) {
        if (!categoryWasChosen) category = categorize(merchant, note)
    }

    ChipRow(
        items = RecordMode.entries,
        selected = mode,
        onSelected = { mode = it },
        label = { it.label }
    )

    Spacer(Modifier.height(8.dp))
    FormField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = "金额", isAmount = true)

    Spacer(Modifier.height(8.dp))
    Text(
        if (mode == RecordMode.TRANSFER) "转出账户" else "账户",
        fontWeight = FontWeight.Medium
    )
    ChipRow(
        items = accounts,
        selected = accounts.firstOrNull { it.id == accountId } ?: return@RecordTab,
        onSelected = { accountId = it.id },
        label = { it.name },
        id = { it.id }
    )

    if (mode == RecordMode.TRANSFER) {
        Spacer(Modifier.height(8.dp))
        Text("转入账户", fontWeight = FontWeight.Medium)
        ChipRow(
            items = accounts,
            selected = accounts.firstOrNull { it.id == targetId } ?: return@RecordTab,
            onSelected = { targetId = it.id },
            label = { it.name },
            id = { it.id }
        )
    } else {
        Spacer(Modifier.height(8.dp))
        FormField(value = merchant, onValueChange = { merchant = it }, label = "商户/来源")

        if (mode == RecordMode.EXPENSE || mode == RecordMode.REFUND) {
            Spacer(Modifier.height(8.dp))
            Text("分类（自动识别，可修改）", fontWeight = FontWeight.Medium)
            ChipRow(
                items = TransactionCategory.entries,
                selected = category,
                onSelected = { category = it; categoryWasChosen = true },
                label = { categoryLabel(it) }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    FormField(value = note, onValueChange = { note = it }, label = "备注（可选）")

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            if (mode == RecordMode.TRANSFER) onSaveTransfer(accountId, targetId, amount, note)
            else onSaveTransaction(accountId, amount, mode, category, merchant, note)
        },
        enabled = amount.toDoubleOrNull()?.let { it > 0 } == true &&
                accountId.isNotBlank() &&
                (mode != RecordMode.TRANSFER || targetId != accountId),
        modifier = Modifier.fillMaxWidth()
    ) { Text("保存") }
}

@Composable
private fun AddAccountTab(
    onAddAccount: (String, AccountType, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.ASSET) }
    var balance by remember { mutableStateOf("") }
    var cardNumber by remember { mutableStateOf("") }

    val typeItems = AccountType.entries.toList()
    ChipRow(
        items = typeItems,
        selected = type,
        onSelected = { type = it },
        label = { t ->
            when (t) {
                AccountType.ASSET -> "资产（储蓄/借记卡）"
                AccountType.CREDIT -> "信用卡"
                AccountType.LOAN -> "贷款"
            }
        }
    )

    Spacer(Modifier.height(8.dp))
    FormField(value = name, onValueChange = { name = it }, label = "账户名称")

    Spacer(Modifier.height(8.dp))
    FormField(value = balance, onValueChange = { balance = it.filter { c -> c.isDigit() || c == '.' } }, label = "当前余额（可选，可为 0）", isAmount = true)

    Spacer(Modifier.height(8.dp))
    FormField(value = cardNumber, onValueChange = { cardNumber = it.filter { c -> c.isDigit() } }, label = "卡号末四位（可选）")

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            val openingBalance = balance.ifBlank { "0" }
            onAddAccount(name, type, openingBalance, cardNumber.ifBlank { null })
        },
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("添加账户") }
}
