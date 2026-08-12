package com.assetsking.app.ui.screen

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerUiState
import com.assetsking.app.LedgerViewModel
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.ui.component.EmptyState
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.SectionHeader
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime

@Composable
fun HomeTab(
    padding: PaddingValues,
    state: LedgerUiState,
    listenerEnabled: Boolean,
    context: Context,
    model: LedgerViewModel,
    searchQuery: String,
    editingAccount: AccountEntity?,
    editingTransaction: TransactionEntity?,
    onSearchChange: (String) -> Unit,
    onShowPending: (Boolean) -> Unit,
    onEditAccount: (AccountEntity?) -> Unit,
    onEditTransaction: (TransactionEntity?) -> Unit,
    onShowReconciliation: () -> Unit = {}
) {
    var txFilter by remember { mutableStateOf("ALL") }
    val reconciliationIntervalMs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        .getLong("reconciliation_interval_ms", 7 * 24 * 60 * 60 * 1000L) // default 7 days
    val overdueAccounts = state.accounts.filter { account ->
        val lastChecked = account.lastCheckedAt ?: 0L
        System.currentTimeMillis() - lastChecked > reconciliationIntervalMs
    }
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
                        "开启通知读取后，支付通知自动入账。",
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
                        text = "待处理通知 ${state.unprocessedNotifications} 条 — 点击处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable {
                            model.processNotifications()
                            onShowPending(true)
                        }
                    )
                }
                if (state.pendingItems.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "待确认 ${state.pendingItems.size} 条 — 点击查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onShowPending(true) }
                    )
                }
            }
        }

        // Reconciliation reminder
        if (overdueAccounts.isNotEmpty()) {
            item {
                GlassCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("该对账了", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "${overdueAccounts.size}个账户超过${reconciliationIntervalMs / (24 * 60 * 60 * 1000)}天未对账",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = onShowReconciliation) { Text("去对账") }
                    }
                }
            }
        }

        // Accounts section
        item { SectionHeader("账户") }
        items(state.accounts, key = { it.id }) { account ->
            Column {
                AccountRow(account = account, onClick = { onEditAccount(account) })
                // 信用卡本期应还 / 未出账单
                if (account.type == com.assetsking.model.AccountType.CREDIT.name && account.statementDay != null) {
                    val now = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = now
                    val today = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    val stmtDay = account.statementDay!!
                    // 计算上个出账日和本出账日
                    cal.set(java.util.Calendar.DAY_OF_MONTH, stmtDay)
                    if (today < stmtDay) cal.add(java.util.Calendar.MONTH, -1)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
                    val lastStmt = cal.timeInMillis
                    cal.add(java.util.Calendar.MONTH, 1)
                    val thisStmt = cal.timeInMillis
                    val stmtTxs = state.transactions.filter { tx -> tx.accountId == account.id && tx.occurredAt in lastStmt..thisStmt }
                    val stmtAmount = stmtTxs.filter { it.type == "EXPENSE" }.sumOf { it.amountCents }
                    val unbilledTxs = state.transactions.filter { tx -> tx.accountId == account.id && tx.occurredAt >= thisStmt }
                    val unbilledAmount = unbilledTxs.filter { it.type == "EXPENSE" }.sumOf { it.amountCents }
                    // 本期已还 = 本出账周期内转入该信用卡的金额
                    val repaid = state.transfers
                        .filter { it.toAccountId == account.id && it.occurredAt in lastStmt..thisStmt }
                        .sumOf { it.amountCents }
                    if (stmtAmount > 0 || unbilledAmount > 0 || repaid > 0) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (stmtAmount > 0) Text("本期应还 ${formatMoney(stmtAmount)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            if (repaid > 0) Text("本期已还 ${formatMoney(repaid)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF66BB6A))
                            if (unbilledAmount > 0) Text("未出账单 ${formatMoney(unbilledAmount)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Reimbursable section
        val reimbursableTxs = state.transactions.filter { it.isReimbursable }
        if (reimbursableTxs.isNotEmpty()) {
            item { SectionHeader("待报销（${reimbursableTxs.size}笔 · ${formatMoney(reimbursableTxs.sumOf { it.amountCents })}）") }
            items(reimbursableTxs, key = { it.id }) { tx ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tx.merchant ?: "未知", fontWeight = FontWeight.Medium)
                        Text(formatTime(tx.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatMoney(tx.amountCents), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { model.toggleReimbursable(tx.id, false) }) { Text("✓ 已报销") }
                }
            }
        }

        // Transactions section
        item { SectionHeader("最近流水") }
        item {
            FormField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = "搜索流水（商户/备注）"
            )
        }
        item {
            com.assetsking.ui.component.ChipRow(
                items = listOf("ALL", "EXPENSE", "INCOME"),
                selected = txFilter,
                onSelected = { txFilter = it },
                label = { when (it) { "ALL" -> "全部"; "EXPENSE" -> "支出"; "INCOME" -> "收入"; else -> it } },
                id = { it }
            )
        }
        val filteredTxs = state.transactions
            .let { if (txFilter == "ALL") it else it.filter { tx -> tx.type == txFilter || (txFilter == "INCOME" && tx.type == "REFUND") } }
            .let { if (searchQuery.isBlank()) it else it.filter { tx ->
                (tx.merchant ?: "").contains(searchQuery, ignoreCase = true) ||
                (tx.note ?: "").contains(searchQuery, ignoreCase = true)
            } }
            .take(20)
        if (filteredTxs.isEmpty()) {
            item { EmptyState(if (searchQuery.isBlank()) "还没有流水，点右下角 ＋ 开始。" else "未找到匹配的流水") }
        } else {
            items(filteredTxs, key = { it.id }) { tx ->
                val accountName = state.accounts
                    .firstOrNull { it.id == tx.accountId }
                    ?.name.orEmpty()
                TransactionRow(
                    transaction = tx,
                    accountName = accountName,
                    onCategoryChange = { id, cat -> model.updateTransactionCategory(id, cat) },
                    onClick = { onEditTransaction(tx) }
                )
            }
        }
    }
}
