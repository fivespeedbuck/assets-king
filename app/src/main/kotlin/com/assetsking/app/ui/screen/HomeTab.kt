package com.assetsking.app.ui.screen

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
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
import com.assetsking.database.TransferEntity
import com.assetsking.ledger.V5Metrics
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.EmptyState
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.SectionHeader
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime

private val Green = Color(0xFF66BB6A)

/** 最近流水统一行：流水或转账（transfers 表）二选一 */
private data class RecentRow(val tx: TransactionEntity?, val transfer: TransferEntity?) {
    val time: Long get() = tx?.occurredAt ?: transfer?.occurredAt ?: 0L
}

@Composable
fun HomeTab(
    padding: PaddingValues,
    state: LedgerUiState,
    listenerStatus: ListenerStatus,
    lastReceivedAt: Long,
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
    var transferToDelete by remember { mutableStateOf<TransferEntity?>(null) }
    val reconciliationIntervalMs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        .getLong("reconciliation_interval_ms", 7 * 24 * 60 * 60 * 1000L) // default 7 days
    val overdueAccounts = state.accounts.filter { account ->
        val lastChecked = account.lastCheckedAt ?: 0L
        System.currentTimeMillis() - lastChecked > reconciliationIntervalMs
    }
    val v5 = state.v5
    // Box 只是给删除确认 AlertDialog 一个挂点，列表本身不动
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 金库状态（REQ §19-22）：入库状态 + 最近入库时间 + 待确认/需核对三个点击区
        item {
            VaultStatusCard(
                listenerStatus = listenerStatus,
                lastReceivedAt = lastReceivedAt,
                pendingCount = state.pendingItems.size,
                needsReconciliationCount = overdueAccounts.size,
                onShowPending = { onShowPending(true) },
                onShowReconciliation = onShowReconciliation,
                context = context
            )
        }

        // ── V5 还债驾驶舱 ──
        if (v5 != null) {
            item { V5DashboardCard(v5, state, model, onShowPending) }
            item { V5SixGrid(v5) }
            item { V5FutureCard(v5) }
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
        item { SectionHeader("账户", modifier = Modifier.padding(horizontal = 4.dp)) }
        item {
            GlassCard {
                state.accounts.forEach { account ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        AccountRow(account = account, onClick = { onEditAccount(account) })
                        // V5：信用卡剩余应还（统一口径：原始账单−已还）/ Pending
                        val remainingDue = v5?.cardRemainingDueByCard?.get(account.id) ?: 0L
                        if (account.type == com.assetsking.model.AccountType.CREDIT.name &&
                            (remainingDue > 0 || account.pendingCents > 0)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (remainingDue > 0) {
                                    Text("本期待还 ${formatMoney(remainingDue)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    if (account.statementOriginalDueCents > remainingDue) {
                                        Text("账单 ${formatMoney(account.statementOriginalDueCents)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (account.pendingCents > 0) Text("Pending ${formatMoney(account.pendingCents)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // Reimbursable section
        val reimbursableTxs = state.transactions.filter { it.isReimbursable }
        if (reimbursableTxs.isNotEmpty()) {
            item { SectionHeader("待报销（${reimbursableTxs.size}笔 · ${formatMoney(reimbursableTxs.sumOf { it.amountCents })}）", modifier = Modifier.padding(horizontal = 4.dp)) }
            item {
                GlassCard {
                    reimbursableTxs.forEach { tx ->
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
            }
        }

        // Transactions section
        item { SectionHeader("最近流水", modifier = Modifier.padding(horizontal = 4.dp)) }
        item {
            GlassCard {
                FormField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    label = "搜索流水（商户/备注）"
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    items = listOf("ALL", "EXPENSE", "INCOME"),
                    selected = txFilter,
                    onSelected = { txFilter = it },
                    label = { when (it) { "ALL" -> "全部"; "EXPENSE" -> "支出"; "INCOME" -> "收入"; else -> it } },
                    id = { it }
                )
            }
        }
        val accountNames = state.accounts.associate { it.id to it.name }
        // 转账存 transfers 表，与流水按时间合并展示；筛选只在「全部」时包含转账
        val filteredRows = buildList {
            state.transactions.forEach { tx ->
                if (txFilter == "ALL" || tx.type == txFilter || (txFilter == "INCOME" && tx.type == "REFUND"))
                    add(RecentRow(tx, null))
            }
            if (txFilter == "ALL") state.transfers.forEach { add(RecentRow(null, it)) }
        }.filter { row ->
            if (searchQuery.isBlank()) true
            else row.tx?.let { (it.merchant ?: "").contains(searchQuery, ignoreCase = true) || (it.note ?: "").contains(searchQuery, ignoreCase = true) }
                ?: (row.transfer?.note ?: "").contains(searchQuery, ignoreCase = true)
        }.sortedByDescending { it.time }.take(20)
        if (filteredRows.isEmpty()) {
            item { GlassCard { EmptyState(if (searchQuery.isBlank()) "还没有流水，点右下角 ＋ 开始。" else "未找到匹配的流水") } }
        } else {
            // 流水保持 items()：20 条也让 LazyColumn 自己回收，且每条独立卡片点击区域更清楚
            items(filteredRows, key = { it.tx?.id ?: "transfer-${it.transfer?.id}" }) { row ->
                val tx = row.tx
                GlassCard(contentPadding = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (tx != null) {
                        TransactionRow(
                            transaction = tx,
                            accountName = accountNames[tx.accountId].orEmpty(),
                            onCategoryChange = { id, cat -> model.updateTransactionCategory(id, cat) },
                            onClick = { onEditTransaction(tx) }
                        )
                    } else {
                        val tf = requireNotNull(row.transfer)
                        TransferRow(
                            fromName = accountNames[tf.fromAccountId].orEmpty(),
                            toName = accountNames[tf.toAccountId].orEmpty(),
                            amountCents = tf.amountCents,
                            occurredAt = tf.occurredAt,
                            note = tf.note,
                            onClick = { transferToDelete = tf }
                        )
                    }
                }
            }
        }
    }
    }
    // 转账删除确认：回滚两边余额
    transferToDelete?.let { tf ->
        val fromName = state.accounts.firstOrNull { it.id == tf.fromAccountId }?.name.orEmpty()
        val toName = state.accounts.firstOrNull { it.id == tf.toAccountId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { transferToDelete = null },
            title = { Text("删除这笔转账？") },
            text = { Text("$fromName → $toName ${formatMoney(tf.amountCents)}\n两边账户余额会按原样回滚。") },
            confirmButton = {
                TextButton(onClick = {
                    model.deleteTransfer(tf.id)
                    transferToDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { transferToDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 金库状态卡：入库状态 + 最近入库时间 + 待确认/需核对三个点击区（REQ §19-22）。 */
@Composable
private fun VaultStatusCard(
    listenerStatus: ListenerStatus,
    lastReceivedAt: Long,
    pendingCount: Int,
    needsReconciliationCount: Int,
    onShowPending: () -> Unit,
    onShowReconciliation: () -> Unit,
    context: Context
) {
    val (statusLabel, statusColor) = when (listenerStatus) {
        ListenerStatus.OK -> "金库正常" to Green
        ListenerStatus.DISCONNECTED -> "入库中断" to MaterialTheme.colorScheme.error
        ListenerStatus.DISABLED -> "尚未开启" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    GlassCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("金库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(statusLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = statusColor)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (lastReceivedAt > 0) "最近入库 ${formatTime(lastReceivedAt)}" else "等待第一笔账目",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pendingCount > 0 || needsReconciliationCount > 0 || listenerStatus != ListenerStatus.OK) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pendingCount > 0) {
                    Text(
                        "待确认 $pendingCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onShowPending() }
                    )
                }
                if (needsReconciliationCount > 0) {
                    Text(
                        "需核对 $needsReconciliationCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onShowReconciliation() }
                    )
                }
                if (listenerStatus != ListenerStatus.OK) {
                    TextButton(onClick = { openListenerSettings(context) }) {
                        Text(if (listenerStatus == ListenerStatus.DISABLED) "去开启" else "去重绑")
                    }
                }
            }
        }
    }
}

// ── V5 驾驶舱组件 ──

@Composable
private fun V5DashboardCard(
    v5: V5Metrics,
    state: LedgerUiState,
    model: LedgerViewModel,
    onShowPending: (Boolean) -> Unit
) {
    GlassCard {
        Text(
            text = "当前总负债",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = formatMoney(v5.totalDebtCents),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(6.dp))
        // 可用现金：与总负债成对出现——「欠多少 / 手上有多少」一眼可比
        Text(
            text = "可用现金 ${formatMoney(v5.availableCashCents)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (v5.availableCashCents > 0) Green else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        val breakdown = buildString {
            append("信用卡 ${formatMoney(v5.cardDebtCents)}")
            if (v5.loanAccountDebtCents > 0) append(" · 贷款账户 ${formatMoney(v5.loanAccountDebtCents)}")
            if (v5.loanPlanDebtCents > 0) append(" · 贷款计划 ${formatMoney(v5.loanPlanDebtCents)}")
            if (v5.accruedInterestCents > 0) append(" · 已发生利息 ${formatMoney(v5.accruedInterestCents)}")
        }
        Text(
            text = breakdown,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        when {
            v5.anchorTotalDebtCents == null -> Text(
                "今日起建档 · 下月起计算净降债",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            v5.netDebtReductionCents > 0 -> Text(
                "本月净降债 +${formatMoney(v5.netDebtReductionCents)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Green
            )
            v5.netDebtReductionCents < 0 -> Text(
                "本月负债净增 ${formatMoney(-v5.netDebtReductionCents)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            else -> Text(
                "本月负债持平",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${v5TrendLabel(v5.trend)} · 阶段：${v5StageLabel(v5.stage)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (v5.needsAttention) {
            Spacer(Modifier.height(4.dp))
            Text(
                "注意：负债没有在下降",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
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

@Composable
private fun V5SixGrid(v5: V5Metrics) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("本月必还", formatMoney(v5.mustRepayCents), MaterialTheme.colorScheme.error, Modifier.weight(1f))
            MetricCell(
                "本月还剩",
                if (v5.monthlySurvivalGapCents < 0) "缺 ${formatMoney(-v5.monthlySurvivalGapCents)}" else "+${formatMoney(v5.monthlySurvivalGapCents)}",
                if (v5.monthlySurvivalGapCents < 0) MaterialTheme.colorScheme.error else Green,
                Modifier.weight(1f)
            )
            MetricCell(
                "每月能剩",
                if (v5.stableDebtCoverageCents >= 0) "+${formatMoney(v5.stableDebtCoverageCents)}" else "缺 ${formatMoney(-v5.stableDebtCoverageCents)}",
                if (v5.stableDebtCoverageCents >= 0) Green else MaterialTheme.colorScheme.error,
                Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("本月能花", formatMoney(v5.freeSpendingCents), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            MetricCell("今天能花", formatMoney(v5.dailySafeSpendCents), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            MetricCell(
                "今天已花",
                formatMoney(v5.todayOptionalSpentCents),
                if (v5.todayOptionalSpentCents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                Modifier.weight(1f)
            )
        }
        if (v5.optionalSpentCents > 0) {
            Text(
                "本月非必要消费已花 ${formatMoney(v5.optionalSpentCents)}（已计入上方缺口）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (v5.freeSpendingCents == 0L && v5.monthlySurvivalGapCents < 0) {
            Text(
                "本月存在资金缺口，建议暂停非必要消费",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricCell(title: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, contentPadding = Modifier.padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun V5FutureCard(v5: V5Metrics) {
    GlassCard {
        Text("未来应还与预测", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("未来7天应还 ${formatMoney(v5.due7DaysCents)} · 未来30天应还 ${formatMoney(v5.due30DaysCents)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            v5.projectedPayoffMonths?.let { "按当前轨迹预计 ${it} 个月后清债" } ?: "按当前轨迹 24 个月内无法清债",
            style = MaterialTheme.typography.bodySmall,
            color = if (v5.projectedPayoffMonths != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}
