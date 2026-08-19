package com.assetsking.app.ui.screen

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerUiState
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.usecase.GetStatsUseCase
import com.assetsking.usecase.StatsData
import com.assetsking.usecase.UpcomingRepayment
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HomeGreen = Color(0xFF66BB6A)
private val HomeRed = Color(0xFFE57373)
private val HomeOrange = Color(0xFFFFB74D)

/** 首页固定核心区 + 可配置模块（REQ 首页信息优先级/UI结构/可配置模块）。 */
@Composable
fun HomeTab(
    padding: PaddingValues,
    state: LedgerUiState,
    listenerStatus: ListenerStatus,
    lastReceivedAt: Long,
    context: Context,
    model: LedgerViewModel,
    repository: LedgerRepository,
    budgets: List<BudgetEntity>,
    recurringRules: List<RecurringRuleEntity>,
    upcomingRepayments: List<UpcomingRepayment>,
    enabledModules: Set<String>,
    onShowPending: () -> Unit,
    onShowReconciliation: () -> Unit,
    onGotoStats: () -> Unit,
    onGotoLoans: () -> Unit,
    onGotoBills: () -> Unit,
    onEditAccount: (AccountEntity?) -> Unit
) {
    var showModuleLibrary by remember { mutableStateOf(false) }
    var showAssetAccounts by remember { mutableStateOf(false) }
    var showDebtAccounts by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("privacy_mode", false)) }

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    fun money(cents: Long) = if (privacy) "••••" else formatMoney(cents)

    // 本月收支（REQ 首页信息优先级§6/收入§2-3）：实际收入只算 INCOME；支出扣已关联退款与已报销
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    val monthTxs = state.transactions.filter { it.occurredAt in monthStart..monthEnd }
    val monthIncome = monthTxs.filter { it.type == "INCOME" }.sumOf { it.amountCents }
    val refundOffset = monthTxs.filter { it.type == "REFUND" && it.refundOfId != null }.sumOf { it.amountCents }
    val reimbOffset = monthTxs.filter { it.type == "EXPENSE" }.sumOf { it.reimbursedCents }
    val monthExpense = (monthTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }.sumOf { it.amountCents } - refundOffset - reimbOffset).coerceAtLeast(0L)
    val monthBalance = monthIncome - monthExpense

    // 最近还款提醒（REQ 首页UI§5-7）
    val dueSoon = upcomingRepayments
    val dueTotal = dueSoon.sumOf { it.totalCents }
    val dueEarliest = dueSoon.minOfOrNull { it.dueDateEpochDay }
    val anyOverdue = dueSoon.any { it.overdue }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 第一层：财务总览卡（REQ 首页UI§1-2/§17-18）──
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 总资产 / 总欠款 左右并列
                        Column(Modifier.weight(1f).clickable { showAssetAccounts = true }) {
                            Text("总资产", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(state.v5?.availableCashCents ?: 0L), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f).clickable { showDebtAccounts = true }, horizontalAlignment = Alignment.End) {
                            Text("总欠款", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(state.v5?.totalDebtCents ?: 0L), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = HomeRed)
                        }
                        // 隐私开关（REQ 首页UI§9）
                        TextButton(onClick = {
                            privacy = !privacy
                            prefs.edit().putBoolean("privacy_mode", privacy).apply()
                        }) { Text(if (privacy) "显示金额" else "隐藏金额", style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    // 本月收支一行（整行可点进统计，REQ 首页UI§18）
                    Row(
                        Modifier.fillMaxWidth().clickable { onGotoStats() }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("收入 ${money(monthIncome)}", color = HomeGreen, style = MaterialTheme.typography.bodyMedium)
                        Text("支出 ${money(monthExpense)}", color = HomeRed, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "结余 ${if (monthBalance >= 0) money(monthBalance) else money(-monthBalance)}",
                            color = if (monthBalance >= 0) HomeGreen else HomeRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // 最近还款提醒（REQ 首页UI§5-7）：到期前 3 天窗口或逾期
                    if (dueSoon.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onGotoLoans() }.background(
                                if (anyOverdue) HomeRed.copy(alpha = 0.12f) else HomeOrange.copy(alpha = 0.16f),
                                RoundedCornerShape(8.dp)
                            ).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (anyOverdue) "已逾期" else "即将还款",
                                fontWeight = FontWeight.Bold,
                                color = if (anyOverdue) HomeRed else HomeOrange
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "${dueSoon.size} 笔 合计 ${money(dueTotal)}" + (dueEarliest?.let { " · 最近 ${DateTimeFormatter.ofPattern("M月d日", Locale.CHINA).format(java.time.LocalDate.ofEpochDay(it))}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // ── 第二层：金库状态卡（REQ 首页UI§3/§19-22）──
        item {
            VaultStatusCard(
                listenerStatus = listenerStatus,
                lastReceivedAt = lastReceivedAt,
                pendingCount = state.pendingItems.size,
                pendingNetCents = state.pendingItems.sumOf { p ->
                    val amt = p.parsed.amountCents ?: 0L
                    when (p.parsed.isExpense) { true -> -amt; false -> amt; null -> 0L }
                },
                needsReconciliationCount = state.accounts.filter {
                    it.balanceStatus == "DISCREPANCY" || System.currentTimeMillis() - (it.lastCheckedAt ?: 0L) > 7 * 24 * 60 * 60 * 1000L
                }.size,
                onShowPending = onShowPending,
                onShowReconciliation = onShowReconciliation,
                context = context
            )
        }

        // ── 可配置模块区（REQ 首页可配置模块 §1-10）──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showModuleLibrary = true }) { Text("＋ 添加模块", style = MaterialTheme.typography.labelMedium) }
            }
        }
        enabledModules.toList().sortedBy { moduleOrder(it) }.forEach { module ->
            item(key = "module-$module") {
                HomeModuleCard(
                    key = module,
                    state = state,
                    budgets = budgets,
                    recurringRules = recurringRules,
                    upcomingRepayments = upcomingRepayments,
                    repository = repository,
                    money = ::money,
                    onGotoStats = onGotoStats,
                    onGotoLoans = onGotoLoans,
                    onGotoBills = onGotoBills
                )
            }
        }
    }

    if (showModuleLibrary) {
        ModuleLibraryDialog(
            enabled = enabledModules,
            onToggle = { module -> model.setHomeModules(enabledModules + module) },
            onRemove = { module -> model.setHomeModules(enabledModules - module) },
            onDismiss = { showModuleLibrary = false }
        )
    }
    if (showAssetAccounts) {
        AccountListDialog(
            title = "资产账户",
            accounts = state.accounts.filter { it.type == AccountType.ASSET.name && !it.archived },
            onEdit = { showAssetAccounts = false; onEditAccount(it) },
            onDismiss = { showAssetAccounts = false }
        )
    }
    if (showDebtAccounts) {
        AccountListDialog(
            title = "欠款账户",
            accounts = state.accounts.filter { it.type != AccountType.ASSET.name && !it.archived },
            onEdit = { showDebtAccounts = false; onEditAccount(it) },
            onDismiss = { showDebtAccounts = false }
        )
    }
}

private fun moduleOrder(key: String): Int = listOf("budget", "reimbursement", "recurring", "week", "ranking", "trend", "accounts", "repayments").indexOf(key).let { if (it < 0) 99 else it }

/** 8 个可配置模块（REQ 首页可配置模块§5），全部可下钻。 */
@Composable
private fun HomeModuleCard(
    key: String,
    state: LedgerUiState,
    budgets: List<BudgetEntity>,
    recurringRules: List<RecurringRuleEntity>,
    upcomingRepayments: List<UpcomingRepayment>,
    repository: LedgerRepository,
    money: (Long) -> String,
    onGotoStats: () -> Unit,
    onGotoLoans: () -> Unit,
    onGotoBills: () -> Unit
) {
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    val monthTxs = state.transactions.filter { it.occurredAt in monthStart..monthEnd }
    val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
    val weekTxs = state.transactions.filter { it.occurredAt >= sevenDaysAgo }

    var stats by remember { mutableStateOf<StatsData?>(null) }
    LaunchedEffect(Unit) {
        if (key == "trend") stats = GetStatsUseCase(repository).invoke()
    }

    GlassCard(Modifier.fillMaxWidth().clickable { when (key) { "recurring" -> onGotoBills(); "repayments" -> onGotoLoans(); else -> onGotoStats() } }) {
        Column(Modifier.padding(14.dp)) {
            when (key) {
                "budget" -> {
                    val budgetSum = budgets.filter { it.month == YearMonth.now().toString() }.sumOf { it.monthlyLimitCents }
                    val necessarySpent = monthTxs.filter { it.type == "EXPENSE" && it.necessity == true }.sumOf { it.amountCents }
                    Text("本月预算", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("必要消费 ${money(necessarySpent)} / ${money(budgetSum)}", style = MaterialTheme.typography.bodyMedium)
                    Text("自由开销额度见设置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "reimbursement" -> {
                    val pending = state.transactions.filter { it.isReimbursable && it.reimbursedCents < it.amountCents }
                    Text("待报销", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${pending.size} 笔 · 待报销 ${money(pending.sumOf { it.amountCents - it.reimbursedCents })}", style = MaterialTheme.typography.bodyMedium)
                }
                "recurring" -> {
                    val now = System.currentTimeMillis()
                    val due = recurringRules.filter { it.isActive && it.nextRunAt in now..monthEnd }
                    Text("本月待扣", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${due.size} 笔 · 待扣 ${money(due.sumOf { it.amountCents })}", style = MaterialTheme.typography.bodyMedium)
                }
                "week" -> {
                    val spend = (weekTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }.sumOf { it.amountCents }
                        - weekTxs.filter { it.type == "REFUND" && it.refundOfId != null }.sumOf { it.amountCents }
                        - weekTxs.filter { it.type == "EXPENSE" }.sumOf { it.reimbursedCents }).coerceAtLeast(0L)
                    Text("近 7 日支出", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(money(spend), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = HomeRed)
                }
                "ranking" -> {
                    val refundOffset = monthTxs.filter { it.type == "REFUND" && it.refundOfId != null }.groupBy { it.refundOfId!! }.mapValues { (_, rs) -> rs.sumOf { it.amountCents } }
                    val top = monthTxs.filter { it.type == "EXPENSE" }
                        .groupBy { it.category }
                        .map { (cat, txs) -> cat to txs.sumOf { (it.amountCents - (refundOffset[it.id] ?: 0L) - it.reimbursedCents).coerceAtLeast(0L) } }
                        .sortedByDescending { it.second }.take(3)
                    Text("分类排行", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    top.forEach { (cat, cents) ->
                        Text("${cat.ifEmpty { "待分类" }}  ${money(cents)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (top.isEmpty()) Text("暂无记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "trend" -> {
                    Text("月度趋势", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    stats?.monthlyBars?.takeLast(3)?.forEach { bar ->
                        Text("${bar.month}  收 ${money(bar.incomeCents)}  支 ${money(bar.expenseCents)}", style = MaterialTheme.typography.bodySmall)
                    } ?: Text("加载中…", style = MaterialTheme.typography.bodySmall)
                }
                "accounts" -> {
                    Text("分账户余额", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    state.accounts.filter { it.type == AccountType.ASSET.name && !it.archived }.take(4).forEach { a ->
                        Text("${a.name}  ${money(a.balanceCents)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "repayments" -> {
                    val paid = monthTxs.filter { it.type == "LOAN_PAYMENT" }.sumOf { it.amountCents }
                    Text("本月还款", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (upcomingRepayments.isNotEmpty()) {
                        Text("待还 ${upcomingRepayments.size} 笔 ${money(upcomingRepayments.sumOf { it.totalCents })}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("已还 ${money(paid)}", style = MaterialTheme.typography.bodySmall, color = HomeGreen)
                }
            }
        }
    }
}

@Composable
private fun ModuleLibraryDialog(
    enabled: Set<String>,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val all = listOf(
        "budget" to "本月预算", "reimbursement" to "待报销", "recurring" to "周期扣款",
        "week" to "近 7 日支出", "ranking" to "分类排行", "trend" to "月度趋势",
        "accounts" to "分账户余额", "repayments" to "本月还款"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("首页模块") },
        text = {
            Column {
                all.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = key in enabled,
                            onCheckedChange = { if (it) onToggle(key) else onRemove(key) }
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {}
    )
}

@Composable
private fun AccountListDialog(
    title: String,
    accounts: List<AccountEntity>,
    onEdit: (AccountEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                accounts.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(a) }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(a.name)
                        Text(formatMoney(if (a.type == AccountType.ASSET.name) a.balanceCents else a.balanceCents), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {}
    )
}

/** 金库状态卡（REQ 首页UI §19-22）：三个完整点击区 + 净变化。 */
@Composable
private fun VaultStatusCard(
    listenerStatus: ListenerStatus,
    lastReceivedAt: Long,
    pendingCount: Int,
    pendingNetCents: Long,
    needsReconciliationCount: Int,
    onShowPending: () -> Unit,
    onShowReconciliation: () -> Unit,
    context: Context
) {
    // 重连补扫中（服务 onListenerConnected 里跑 SmsRescan 期间）
    val rescanning by AssetsNotificationListenerService.rescanning.collectAsStateWithLifecycle()
    val smsGranted = rememberSmsGranted()

    // REQ 监听§15/§21：通知使用权缺失红「自动记账已中断」；仅短信缺失橙「短信补扫未开启」；
    // 断开红「入库暂时中断」；重连补扫中「恢复中」
    val (statusLabel, statusColor) = when {
        listenerStatus == ListenerStatus.DISABLED -> "自动记账已中断" to HomeRed
        listenerStatus == ListenerStatus.DISCONNECTED -> "入库暂时中断" to HomeRed
        rescanning -> "恢复中 · 补扫中" to MaterialTheme.colorScheme.primary
        !smsGranted -> "短信补扫未开启" to HomeOrange
        else -> "金库正常" to MaterialTheme.colorScheme.primary
    }
    // 漏收窗口（REQ 监听§21）：掉线期间的账目靠短信兜底补收，文案随权限状态变化
    val gapHint = when {
        listenerStatus == ListenerStatus.DISCONNECTED && smsGranted ->
            "漏收窗口已开启：短信兜底将补收掉线期间账目"
        listenerStatus == ListenerStatus.DISCONNECTED && !smsGranted ->
            "短信兜底未开启：掉线期间账目可能漏记"
        else -> null
    }
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("金库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(statusLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = statusColor)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (lastReceivedAt > 0) "最近入库 ${formatTime(lastReceivedAt)}" else "等待第一笔账目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { }
            )
            gapHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (smsGranted) HomeOrange else HomeRed
                )
            }
            if (pendingCount > 0 || needsReconciliationCount > 0 || listenerStatus != ListenerStatus.OK) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pendingCount > 0) {
                        Text(
                            "待确认 $pendingCount · 净变化 ${if (pendingNetCents > 0) "+" else ""}${formatMoney(pendingNetCents)}",
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
}
