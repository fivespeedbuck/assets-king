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
    viewModel: LedgerViewModel
) {
    val sorted = rules.sortedBy { it.nextRunAt }
    val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "本月固定账单：扣了自动打勾，没扣的直接在这里确认入账。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(sorted.size, key = { sorted[it].id }) { idx ->
            val rule = sorted[idx]
            val claimed = transactions.any { tx ->
                tx.recurringRuleId == rule.id &&
                    kotlin.math.abs(tx.occurredAt - rule.nextRunAt) <= 15L * 24 * 60 * 60 * 1000
            }
            val match = pendingItems.firstOrNull { pi ->
                val amt = pi.parsed.amountCents ?: return@firstOrNull false
                pi.parsed.isExpense != false &&
                    kotlin.math.abs(amt - rule.amountCents) * 100 <= rule.amountCents * 15
            }
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
                                "${formatMoney(rule.amountCents)} · 下次 ${fmt.format(Date(rule.nextRunAt))}",
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
                    }
                    when {
                        claimed -> Text("✓ 已扣", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
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
