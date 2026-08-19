package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.database.AccountEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
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
@Composable
fun BillsScreen(
    rules: List<RecurringRuleEntity>,
    transactions: List<TransactionEntity>,
    pendingItems: List<PendingItem>,
    accounts: List<AccountEntity>,
    viewModel: LedgerViewModel,
    onBack: () -> Unit = {}
) {
    val sorted = rules.sortedBy { it.nextRunAt }
    val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != {}) {
                androidx.compose.material3.TextButton(onClick = onBack) { Text("← 返回") }
            }
            Text("周期账单", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp))
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            // 本月已扣/待扣汇总（REQ 导航§3）
            // 审核 J-3 修复：按自然月统计（原按 nextRunAt±15 天窗口，月初/月末会跨月，与「本月」口径不符）。
            val zone0 = ZoneId.systemDefault()
            val monthStart0 = YearMonth.now().atDay(1).atStartOfDay(zone0).toInstant().toEpochMilli()
            val monthEnd0 = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(zone0).toInstant().toEpochMilli() - 1
            val claimedTxs = transactions.filter { tx ->
                tx.occurredAt in monthStart0..monthEnd0 &&
                    tx.recurringRuleId != null && sorted.any { it.id == tx.recurringRuleId }
            }
            val pendingSum = sorted.filter { rule ->
                rule.nextRunAt in monthStart0..monthEnd0 &&
                    transactions.none { tx -> tx.recurringRuleId == rule.id && tx.occurredAt in monthStart0..monthEnd0 }
            }.sumOf { it.amountCents }
            GlassCard {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("本月已扣 ${formatMoney(claimedTxs.sumOf { it.amountCents })}", color = Color(0xFF66BB6A), fontWeight = FontWeight.Medium)
                    Text("待扣 ${formatMoney(pendingSum)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
        items(sorted.size, key = { sorted[it].id }) { idx ->
            val rule = sorted[idx]
            val claimedTx = transactions.firstOrNull { tx ->
                tx.recurringRuleId == rule.id &&
                    kotlin.math.abs(tx.occurredAt - rule.nextRunAt) <= 15L * 24 * 60 * 60 * 1000
            }
            val claimed = claimedTx != null
            val match = pendingItems.firstOrNull { pi ->
                val amt = pi.parsed.amountCents ?: return@firstOrNull false
                pi.parsed.isExpense != false &&
                    kotlin.math.abs(amt - rule.amountCents) * 100 <= rule.amountCents * 15
            }
            // 超过应扣日仍未匹配 → 待核实，不认定逾期也不自动造流水（REQ 导航§11）
            val needsVerify = !claimed && rule.nextRunAt < System.currentTimeMillis()
            // 连续三个月实际明显偏离预计 → 提示调整，不自动改（REQ 导航§9）
            val zone = ZoneId.systemDefault()
            val deviatingMonths = (1..3).map { YearMonth.now().minusMonths(it.toLong()) }.count { m ->
                val start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val tx = transactions.firstOrNull {
                    it.recurringRuleId == rule.id && it.occurredAt in start until end
                }
                tx != null && kotlin.math.abs(tx.amountCents - rule.amountCents) * 100 > rule.amountCents * 20
            }
            val showDeviation = deviatingMonths == 3
            GlassCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.merchant ?: "未命名", fontWeight = FontWeight.Medium)
                        Text(
                            "${accounts.firstOrNull { it.id == rule.accountId }?.name ?: "?"} · " +
                                "预计 ${formatMoney(rule.amountCents)}" +
                                (if (claimedTx != null && claimedTx.amountCents != rule.amountCents)
                                    " · 实际 ${formatMoney(claimedTx.amountCents)}" else "") +
                                " · 下次 ${fmt.format(Date(rule.nextRunAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (match != null && !claimed) {
                            Text(
                                "检测到待确认通知 ${formatMoney(match.parsed.amountCents ?: 0L)}（${match.parsed.merchant ?: "未知商户"}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (showDeviation) {
                            Text(
                                "近 3 月实际扣款均明显偏离预计，建议调整金额",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFB74D)
                            )
                        }
                        if (claimed && claimedTx != null) {
                            // 取消关联（REQ 导航§7）：流水保留，只摘掉周期账单归属
                            TextButton(
                                onClick = { viewModel.linkToRecurringRule(claimedTx.id, null) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) { Text("取消关联", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    when {
                        claimed -> Text("✓ 已扣", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
                        needsVerify -> Text("待核实", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
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
                        }) { Text("确认") }
                        else -> Text("待扣", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }
}
