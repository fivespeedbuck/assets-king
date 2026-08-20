package com.assetsking.app.ui.screen

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import com.assetsking.ui.format.BigMoney
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.theme.ExpenseRed
import com.assetsking.ui.theme.IncomeGreen
import com.assetsking.ui.theme.PendingOrange
import com.assetsking.usecase.GetStatsUseCase
import com.assetsking.usecase.StatsData
import com.assetsking.usecase.UpcomingRepayment
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HomeGreen = IncomeGreen
private val HomeRed = ExpenseRed
private val HomeOrange = PendingOrange

/** 首页固定核心区 + 可配置模块（REQ 首页信息优先级/UI结构/可配置模块）。 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    moduleOrder: List<String>,
    onShowPending: () -> Unit,
    onShowReconciliation: () -> Unit,
    onGotoStats: () -> Unit,
    onGotoLoans: () -> Unit,
    onGotoBills: () -> Unit,
    onGotoReimbursement: () -> Unit,
    onEditAccount: (AccountEntity?) -> Unit
) {
    var showModuleLibrary by remember { mutableStateOf(false) }
    var showAssetAccounts by remember { mutableStateOf(false) }
    var showDebtAccounts by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("privacy_mode", false)) }
    val todayLabel = remember {
        DateTimeFormatter.ofPattern("M月d日 · E", Locale.CHINA).format(java.time.LocalDate.now())
    }

    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    fun money(cents: Long) = if (privacy) "••••" else formatMoney(cents)

    // 本月收支（REQ 首页信息优先级§6/收入§2-3）：实际收入只算 INCOME；支出扣已关联退款与已报销
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    val monthTxs = state.transactions.filter { it.occurredAt in monthStart..monthEnd }
    val monthIncome = monthTxs.filter { it.type == "INCOME" }.sumOf { it.amountCents }
    // 审核 BUG-3 修复：退款冲减需校验原消费在本月内，否则跨月退款把本月支出冲成虚低。
    val monthTxIds = monthTxs.mapTo(HashSet()) { it.id }
    val refundOffset = monthTxs.filter { it.type == "REFUND" && it.refundOfId != null && it.refundOfId in monthTxIds }.sumOf { it.amountCents }
    val reimbOffset = monthTxs.filter { it.type == "EXPENSE" }.sumOf { it.reimbursedCents }
    val monthExpense = (monthTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }.sumOf { it.amountCents } - refundOffset - reimbOffset).coerceAtLeast(0L)
    val monthBalance = monthIncome - monthExpense

    // 最近还款提醒（REQ 首页UI§5-7）
    val dueSoon = upcomingRepayments
    val dueTotal = dueSoon.sumOf { it.totalCents }
    val dueEarliest = dueSoon.minOfOrNull { it.dueDateEpochDay }
    val anyOverdue = dueSoon.any { it.overdue }

    // 模块顺序：用户拖过用保存的顺序，新启用/未排过的补到默认位置（REQ 首页可配置模块§3）
    val orderedModules = moduleOrder.filter { it in enabledModules } +
        enabledModules.filter { it !in moduleOrder }.sortedBy { defaultModuleIndex(it) }
    var dragOrder by remember(enabledModules) { mutableStateOf(orderedModules) }

    fun moveModule(draggedId: String, targetId: String) {
        val from = dragOrder.indexOfFirst { it == draggedId }
        val to = dragOrder.indexOfFirst { it == targetId }
        if (from < 0 || to < 0 || from == to) return
        val list = dragOrder.toMutableList().apply { add(to, removeAt(from)) }
        dragOrder = list
        model.reorderHomeModules(list)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 首页标题与日期胶囊保持独立层级，避免和总览金额争夺视觉焦点。
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "总览",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    todayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // ── 第一层：财务总览卡（REQ 首页UI§1-2/§17-18）──
        item {
            GlassCard(contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "资产概览",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                privacy = !privacy
                                prefs.edit().putBoolean("privacy_mode", privacy).apply()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                        ) {
                            Icon(
                                imageVector = if (privacy) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (privacy) "显示金额" else "隐藏金额",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeHeroMetric(
                            label = "总资产",
                            cents = state.v5?.availableCashCents ?: 0L,
                            hidden = privacy,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            onClick = { showAssetAccounts = true }
                        )
                        VerticalDivider(Modifier.height(66.dp))
                        HomeHeroMetric(
                            label = "总欠款",
                            cents = state.v5?.totalDebtCents ?: 0L,
                            hidden = privacy,
                            color = HomeRed,
                            modifier = Modifier.weight(1f),
                            onClick = { showDebtAccounts = true }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    // 本月收支一行（整行可点进统计，REQ 首页UI§18）
                    Row(
                        Modifier.fillMaxWidth().clickable { onGotoStats() }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeMetric("收入", money(monthIncome), HomeGreen, Modifier.weight(1f))
                        VerticalDivider(Modifier.height(30.dp))
                        HomeMetric("支出", money(monthExpense), HomeRed, Modifier.weight(1f))
                        VerticalDivider(Modifier.height(30.dp))
                        HomeMetric(
                            "结余",
                            if (monthBalance >= 0) money(monthBalance) else money(-monthBalance),
                            if (monthBalance >= 0) HomeGreen else HomeRed,
                            Modifier.weight(1f)
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
                needsReconciliationCount = state.accounts.filter {
                    it.balanceStatus == "DISCREPANCY" || System.currentTimeMillis() - (it.lastCheckedAt ?: 0L) > 7 * 24 * 60 * 60 * 1000L
                }.size,
                onShowReconciliation = onShowReconciliation,
                context = context
            )
        }

        if (state.pendingItems.isNotEmpty()) {
            item {
                PendingStatusCard(
                    pendingCount = state.pendingItems.size,
                    pendingNetCents = state.pendingItems.sumOf { p ->
                        val amt = p.parsed.amountCents ?: 0L
                        when (p.parsed.isExpense) { true -> -amt; false -> amt; null -> 0L }
                    },
                    onShowPending = onShowPending
                )
            }
        }

        // ── 可配置模块区（REQ 首页可配置模块 §1-10）──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showModuleLibrary = true }) { Text("＋ 添加模块", style = MaterialTheme.typography.labelMedium) }
            }
        }
        dragOrder.forEach { module ->
            item(key = "module-$module") {
                val dropTarget = remember(module) {
                    object : DragAndDropTarget {
                        var onItemDropped: ((String, String) -> Unit)? = null
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            val draggedId = event.toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString()
                            if (draggedId != null) onItemDropped?.invoke(draggedId, module)
                            return true
                        }
                    }
                }
                dropTarget.onItemDropped = { draggedId, targetId -> moveModule(draggedId, targetId) }
                Box(
                    Modifier
                        .dragAndDropSource {
                            detectTapGestures(onLongPress = {
                                startTransfer(
                                    DragAndDropTransferData(
                                        clipData = ClipData.newPlainText("module", module)
                                    )
                                )
                            })
                        }
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { it.mimeTypes().contains("text/plain") },
                            target = dropTarget
                        )
                ) {
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
                        onGotoBills = onGotoBills,
                        onGotoReimbursement = onGotoReimbursement
                    )
                }
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

private fun defaultModuleIndex(key: String): Int =
    LedgerRepository.defaultModuleOrder.indexOf(key).let { if (it < 0) 99 else it }

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
    onGotoBills: () -> Unit,
    onGotoReimbursement: () -> Unit
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

    GlassCard(Modifier.fillMaxWidth().clickable { when (key) { "recurring" -> onGotoBills(); "repayments" -> onGotoLoans(); "reimbursement" -> onGotoReimbursement(); else -> onGotoStats() } }) {
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
                    // 审核 BUG-3 修复：退款冲减需校验原消费在近 7 日内。
                    val weekTxIds = weekTxs.mapTo(HashSet()) { it.id }
                    val spend = (weekTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }.sumOf { it.amountCents }
                        - weekTxs.filter { it.type == "REFUND" && it.refundOfId != null && it.refundOfId in weekTxIds }.sumOf { it.amountCents }
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

/** 首页总览的等宽指标；金额严格单行，避免窄屏把小数挤成竖排。 */
@Composable
private fun HomeMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 首页最重要的两项金额等宽同级展示，避免金额被挤到卡片角落。 */
@Composable
private fun HomeHeroMetric(
    label: String,
    cents: Long,
    hidden: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (hidden) {
            Text(
                "••••",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
        } else {
            BigMoney(
                cents = cents,
                color = color,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

/** 待确认入口独立成卡，和金库监听状态保持清晰的层级关系。 */
@Composable
private fun PendingStatusCard(
    pendingCount: Int,
    pendingNetCents: Long,
    onShowPending: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onShowPending),
        contentPadding = Modifier
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(HomeOrange.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    tint = HomeOrange,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("待确认 $pendingCount 笔", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "净变化 ${if (pendingNetCents > 0) "+" else ""}${formatMoney(pendingNetCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onShowPending,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.background(HomeOrange.copy(alpha = 0.16f), RoundedCornerShape(50))
            ) {
                Text("处理", color = HomeOrange, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 金库状态卡（REQ 首页UI §19-22）：状态与监听详情入口。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VaultStatusCard(
    listenerStatus: ListenerStatus,
    lastReceivedAt: Long,
    needsReconciliationCount: Int,
    onShowReconciliation: () -> Unit,
    context: Context
) {
    // 重连补扫中（服务 onListenerConnected 里跑 SmsRescan 期间）
    val rescanning by AssetsNotificationListenerService.rescanning.collectAsStateWithLifecycle()
    val smsGranted = rememberSmsGranted()
    // 金库详情弹窗（审核 J-1 修复：入库状态点击区原为空实现，REQ 首页UI§19 要求可进入金库详情）
    var showDetail by remember { mutableStateOf(false) }

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
    val statusBadgeLabel = when {
        listenerStatus == ListenerStatus.DISABLED || listenerStatus == ListenerStatus.DISCONNECTED -> "中断"
        rescanning -> "恢复"
        !smsGranted -> "短信"
        else -> "正常"
    }
    val statusBadgeBackground = when {
        listenerStatus == ListenerStatus.DISABLED || listenerStatus == ListenerStatus.DISCONNECTED ->
            HomeRed.copy(alpha = 0.12f)
        rescanning || !smsGranted -> HomeOrange.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    GlassCard(contentPadding = Modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountBalance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(
                    Modifier.weight(1f).clickable { showDetail = true },
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            lastReceivedAt <= 0L -> "监听中 · 等待第一笔账目"
                            rescanning -> "恢复中 · 最近入库 ${formatTime(lastReceivedAt)}"
                            listenerStatus == ListenerStatus.OK -> "监听中 · 最近入库 ${formatTime(lastReceivedAt)}"
                            else -> "最近入库 ${formatTime(lastReceivedAt)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    statusBadgeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier
                        .background(statusBadgeBackground, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            gapHint?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (smsGranted) HomeOrange else HomeRed
                )
            }
            if (needsReconciliationCount > 0 || listenerStatus != ListenerStatus.OK) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (needsReconciliationCount > 0) {
                        TextButton(onClick = onShowReconciliation) {
                            Text("需核对 $needsReconciliationCount", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
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

    // 金库详情弹窗（REQ 首页UI§19「入库状态进入金库详情」）
    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("金库状态", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("监听状态：$statusLabel", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (lastReceivedAt > 0) "最近入库：${formatTime(lastReceivedAt)}" else "最近入库：等待第一笔账目",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (smsGranted) "短信兜底：已开启" else "短信兜底：未开启",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    gapHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (smsGranted) HomeOrange else HomeRed) }
                    Text(
                        "银行短信和支付通知先入库，你确认后才正式记账。监听中断期间的账目可靠短信兜底补收。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                if (listenerStatus != ListenerStatus.OK) {
                    TextButton(onClick = { openListenerSettings(context); showDetail = false }) {
                        Text(if (listenerStatus == ListenerStatus.DISABLED) "去开启" else "去重绑")
                    }
                } else {
                    TextButton(onClick = { showDetail = false }) { Text("知道了") }
                }
            },
            dismissButton = {}
        )
    }
}
