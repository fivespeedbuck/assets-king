package com.assetsking.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCompactAmount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.database.AccountEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.theme.RecurringDebitOrange
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 账单页：本月的固定支出一条一条摆出来。
 * 扣了 = 有流水认领到这条规则（确认时自动挂、或规则自动记）→ 打勾；
 * 没扣且有匹配的待确认通知 → 一键确认；没扣也没通知 → 待扣。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    rules: List<RecurringRuleEntity>,
    transactions: List<TransactionEntity>,
    pendingItems: List<PendingItem>,
    accounts: List<AccountEntity>,
    viewModel: LedgerViewModel,
    onOpenTransaction: (TransactionEntity) -> Unit,
    onBack: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val sorted = rules
        .filter { it.type == TransactionType.EXPENSE.name }
        .sortedBy { it.nextRunAt }
    val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
    val zone = ZoneId.systemDefault()
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val monthSummary = recurringDebitMonthSummary(sorted, transactions, monthStart, monthEnd)
    val claimedTxs = monthSummary.claimedTransactions
    val pendingRules = monthSummary.pendingRules
    var showRules by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("周期账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                GlassCard {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.weight(1.15f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("本月已扣", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text(
                                if (privacyEnabled) privacyFakeCompactAmount(2701) else formatMoney(claimedTxs.sumOf { it.amountCents }),
                                color = RecurringDebitOrange,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                        }
                        Row(
                            modifier = Modifier.weight(0.85f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("待扣", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text(
                                if (privacyEnabled) privacyFakeCompactAmount(2702) else formatMoney(pendingRules.sumOf { it.amountCents }),
                                color = RecurringDebitOrange,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showRules = !showRules }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showRules) "收起扣款规则" else "扣款规则")
                }
            }
            if (showRules) {
                item {
                    RecurringRulesSection(
                        rules = sorted,
                        accounts = accounts,
                        onSave = viewModel::saveRecurringRule,
                        onDelete = viewModel::deleteRecurringRule,
                        fixedType = TransactionType.EXPENSE
                    )
                }
            }
            item { Text("本月待扣", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (pendingRules.isEmpty()) {
                item { Text("本月暂无待扣", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(pendingRules, key = { it.id }) { rule ->
                RecurringRuleStatusCard(rule, transactions, pendingItems, accounts, viewModel, fmt)
            }
            item { Text("本月已扣记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (claimedTxs.isEmpty()) {
                item { Text("本月暂无已扣流水", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(claimedTxs, key = { it.id }) { tx ->
                val rule = sorted.firstOrNull { it.id == tx.recurringRuleId }
                GlassCard {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenTransaction(tx) }.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (tx.merchant ?: rule?.merchant)?.let { if (privacyEnabled) privacyObfuscatedText(it, 2720 + tx.id.hashCode()) else it } ?: "周期扣款",
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.LineThrough
                            )
                            Text(
                                "计划 ${if (privacyEnabled) privacyFakeAmount(2721 + tx.id.hashCode()) else formatMoney(rule?.amountCents ?: tx.amountCents)} · 周期扣款",
                                style = MaterialTheme.typography.labelMedium,
                                color = RecurringDebitOrange,
                                textDecoration = TextDecoration.LineThrough
                            )
                            val accountName = accounts.firstOrNull { it.id == tx.accountId }?.name
                            Text(
                                "${accountName?.let { if (privacyEnabled) privacyObfuscatedText(it, 2722 + tx.id.hashCode()) else it } ?: "?"} · ${if (privacyEnabled) privacyFakeDateTime(2723 + tx.id.hashCode()) else fmt.format(Date(tx.occurredAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(if (privacyEnabled) privacyFakeAmount(2724 + tx.id.hashCode()) else formatMoney(tx.amountCents), color = RecurringDebitOrange, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Icon(Icons.Filled.CheckCircle, contentDescription = "已扣", tint = RecurringDebitOrange)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun RecurringRuleStatusCard(
    rule: RecurringRuleEntity,
    transactions: List<TransactionEntity>,
    pendingItems: List<PendingItem>,
    accounts: List<AccountEntity>,
    viewModel: LedgerViewModel,
    fmt: SimpleDateFormat
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyIndex = rule.id.hashCode()
    val claimedTx = transactions.firstOrNull { tx ->
        tx.recurringRuleId == rule.id &&
            kotlin.math.abs(tx.occurredAt - rule.nextRunAt) <= 15L * 24 * 60 * 60 * 1000
    }
    val match = pendingItems.firstOrNull { pi ->
        val amt = pi.parsed.amountCents ?: return@firstOrNull false
        pi.parsed.isExpense != false && kotlin.math.abs(amt - rule.amountCents) * 100 <= rule.amountCents * 15
    }
    val needsVerify = claimedTx == null && rule.nextRunAt < System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val deviatingMonths = (1..3).map { YearMonth.now().minusMonths(it.toLong()) }.count { month ->
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val tx = transactions.firstOrNull { it.recurringRuleId == rule.id && it.occurredAt in start until end }
        tx != null && kotlin.math.abs(tx.amountCents - rule.amountCents) * 100 > rule.amountCents * 20
    }
    GlassCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.merchant?.let { if (privacyEnabled) privacyObfuscatedText(it, 2740 + privacyIndex) else it } ?: "未命名", fontWeight = FontWeight.Medium)
                        Text(
                            "${accounts.firstOrNull { it.id == rule.accountId }?.name?.let { if (privacyEnabled) privacyObfuscatedText(it, 2741 + privacyIndex) else it } ?: "?"} · " +
                                "预计 ${if (privacyEnabled) privacyFakeAmount(2742 + privacyIndex) else formatMoney(rule.amountCents)}" +
                                (if (claimedTx != null && claimedTx.amountCents != rule.amountCents)
                                    " · 实际 ${if (privacyEnabled) privacyFakeAmount(2743 + privacyIndex) else formatMoney(claimedTx.amountCents)}" else "") +
                                " · 下次 ${if (privacyEnabled) privacyFakeDateTime(2744 + privacyIndex) else fmt.format(Date(rule.nextRunAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (match != null && claimedTx == null) {
                            Text(
                                "检测到待确认通知 ${if (privacyEnabled) privacyFakeAmount(2745 + privacyIndex) else formatMoney(match.parsed.amountCents ?: 0L)}（${match.parsed.merchant?.let { if (privacyEnabled) privacyObfuscatedText(it, 2746 + privacyIndex) else it } ?: "未知商户"}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (deviatingMonths == 3) {
                            Text(
                                "近 3 月实际扣款均明显偏离预计，建议调整金额",
                                style = MaterialTheme.typography.labelSmall,
                                color = RecurringDebitOrange
                            )
                        }
                        if (claimedTx != null) {
                            TextButton(
                                onClick = { viewModel.linkToRecurringRule(claimedTx.id, null) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) { Text("取消关联", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    when {
                        claimedTx != null -> Text("已扣", color = RecurringDebitOrange, fontWeight = FontWeight.Bold)
                        needsVerify -> Text("待核实", color = RecurringDebitOrange, fontWeight = FontWeight.Bold)
                        match != null -> Button(onClick = {
                            val parsed = match.parsed
                            val hint = parsed.bankHint
                            val account = hint?.let { h ->
                                accounts.firstOrNull { it.name.contains(h) || h.contains(it.name) }
                            } ?: accounts.firstOrNull { it.id == rule.accountId } ?: accounts.firstOrNull()
                            account?.let {
                                viewModel.confirmNotification(
                                    notificationId = match.notification.id,
                                    accountId = it.id,
                                    amountCents = parsed.amountCents ?: return@let,
                                    type = TransactionType.valueOf(rule.type),
                                    category = rule.category,
                                    merchant = rule.merchant,
                                    note = null,
                                    bankBalanceCents = parsed.balanceCents,
                                    bankCardTail = parsed.cardTail
                                )
                            }
                        }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = RecurringDebitOrange)) { Text("确认") }
                        else -> Text("待扣", color = RecurringDebitOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }
}
