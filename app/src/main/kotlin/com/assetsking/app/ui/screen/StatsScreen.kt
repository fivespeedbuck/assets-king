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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.ledger.V5Metrics
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.usecase.GetStatsUseCase
import com.assetsking.usecase.StatsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StatsScreen(
    repository: LedgerRepository,
    budgets: List<BudgetEntity>,
    recurringRules: List<RecurringRuleEntity>,
    accounts: List<AccountEntity>,
    v5: V5Metrics? = null
) {
    var stats by remember { mutableStateOf<StatsData?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            stats = GetStatsUseCase(repository).invoke()
        }
    }

    val data = stats ?: return
    val snapshots by repository.snapshots.collectAsStateWithLifecycle(initialValue = emptyList())

    // Cash flow prediction
    val now = System.currentTimeMillis()
    val thirtyDaysLater = now + 30 * 24 * 60 * 60 * 1000L
    val upcoming = recurringRules
        .filter { it.isActive && it.nextRunAt in now..thirtyDaysLater }
        .sortedBy { it.nextRunAt }
    val predictedIncome = upcoming.filter { it.type == "INCOME" || it.type == "REFUND" }.sumOf { it.amountCents }
    val predictedExpense = upcoming.filter { it.type == "EXPENSE" }.sumOf { it.amountCents }
    val currentNetWorth = accounts.sumOf {
        if (it.type == AccountType.ASSET.name) it.balanceCents else -it.balanceCents
    }
    val projectedNet = currentNetWorth + predictedIncome - predictedExpense

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── V5 本月现金流 ──
        if (v5 != null) {
            item {
                GlassCard {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("本月现金流", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本月收入", style = MaterialTheme.typography.bodyMedium)
                        Text("+${formatMoney(v5.incomeActualCents)}", fontWeight = FontWeight.Medium, color = Color(0xFF66BB6A))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本月必须还款", style = MaterialTheme.typography.bodyMedium)
                        Text("-${formatMoney(v5.mustRepayCents)}", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("新增借款（不是收入）", style = MaterialTheme.typography.bodyMedium)
                        Text("+${formatMoney(v5.newBorrowingCents)}", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本月净降债", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (v5.netDebtReductionCents >= 0) "+${formatMoney(v5.netDebtReductionCents)}" else formatMoney(v5.netDebtReductionCents),
                            fontWeight = FontWeight.Medium,
                            color = if (v5.netDebtReductionCents > 0) Color(0xFF66BB6A) else MaterialTheme.colorScheme.error
                        )
                    }
                    Text("资金缺口 = 收入 − 必要生活 − 必须还款", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
            }
        }

        // Prediction card
        item {
            GlassCard {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("未来30天预测", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (upcoming.isEmpty()) {
                    Text("暂无即将到期的周期性账单", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    for (rule in upcoming) {
                        val account = accounts.firstOrNull { it.id == rule.accountId }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${rule.merchant ?: "周期性账单"} · ${account?.name ?: "?"}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                            Text(
                                if (rule.type == "INCOME") "+${formatMoney(rule.amountCents)}" else "-${formatMoney(rule.amountCents)}",
                                color = if (rule.type == "INCOME") Color(0xFF66BB6A) else Color(0xFFEF5350),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("目前净资产", style = MaterialTheme.typography.bodyMedium)
                    Text(formatMoney(currentNetWorth), fontWeight = FontWeight.Medium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("预计收入", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF66BB6A))
                    Text("+${formatMoney(predictedIncome)}", color = Color(0xFF66BB6A), fontWeight = FontWeight.Medium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("预计支出", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEF5350))
                    Text("-${formatMoney(predictedExpense)}", color = Color(0xFFEF5350), fontWeight = FontWeight.Medium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("预计余额", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        formatMoney(projectedNet),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (projectedNet >= 0) Color(0xFF66BB6A) else Color(0xFFEF5350)
                    )
                }
            }
            }
        }

        // Spend alerts
        val today = java.time.LocalDate.now()
        val daysRemaining = today.lengthOfMonth() - today.dayOfMonth + 1
        val alerts = budgets.mapNotNull { budget ->
            val cat = runCatching { TransactionCategory.valueOf(budget.category) }.getOrNull() ?: return@mapNotNull null
            val spent = data.categorySlices.firstOrNull { it.category == cat }?.totalCents ?: 0L
            val daysPast = today.dayOfMonth - 1
            if (daysPast <= 0) return@mapNotNull null
            val dailyRate = spent.toDouble() / daysPast
            val projected = (dailyRate * today.lengthOfMonth()).toLong()
            if (projected > budget.monthlyLimitCents) {
                Triple(cat, projected, projected - budget.monthlyLimitCents)
            } else null
        }
        if (alerts.isNotEmpty()) {
            item {
                GlassCard {
                    Text("消费预警", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(0xFFEF5350))
                    Spacer(Modifier.height(8.dp))
                    alerts.forEach { (cat, projected, over) ->
                        Text(
                            "⚠ ${categoryLabel(cat)}：预计本月 ${formatMoney(projected)}，超预算 ${formatMoney(over)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Snapshot curve
        if (snapshots.isNotEmpty()) {
            item {
                GlassCard {
                    Text("资产快照（最近）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    snapshots.take(30).forEach { snap ->
                        val date = java.time.LocalDate.ofEpochDay(snap.dateEpochDay)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(date.toString(), style = MaterialTheme.typography.bodySmall)
                            Text(formatMoney(snap.netWorth), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // Category breakdown
        item {
            GlassCard {
                Text("支出分类（本月）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (data.categorySlices.isEmpty()) {
                    Text("暂无支出数据", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    val total = data.categorySlices.sumOf { it.totalCents }.toFloat()
                    data.categorySlices.forEach { slice ->
                        val pct = if (total > 0) slice.totalCents / total else 0f
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(categoryLabel(slice.category), style = MaterialTheme.typography.bodyMedium)
                                Text("${formatMoney(slice.totalCents)} (${(pct * 100).toInt()}%)", style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Budget progress
        if (budgets.isNotEmpty()) {
            item {
                GlassCard {
                    Text("预算执行（本月）", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    budgets.forEach { budget ->
                        val cat = runCatching { TransactionCategory.valueOf(budget.category) }.getOrDefault(TransactionCategory.UNCATEGORIZED)
                        val label = runCatching { TransactionCategory.valueOf(budget.category) }.getOrNull()?.let { categoryLabel(it) } ?: budget.category
                        val spent = data.categorySlices.firstOrNull { it.category == cat }?.totalCents ?: 0L
                        val pct = if (budget.monthlyLimitCents > 0) (spent.toFloat() / budget.monthlyLimitCents).coerceIn(0f, 1.5f) else 0f
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text("${formatMoney(spent)} / ${formatMoney(budget.monthlyLimitCents)}", style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { pct.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = if (pct > 1f) Color(0xFFEF5350) else Color(0xFF66BB6A),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Monthly summary
        item {
            GlassCard {
                Text("月度收支", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                data.monthlyBars.reversed().forEach { bar ->
                    IncomeExpenseRow(bar.month, bar.incomeCents, bar.expenseCents)
                }
            }
        }

        // Yearly summary
        item {
            GlassCard {
                Text("年度收支", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                data.yearlyBars.forEach { bar ->
                    IncomeExpenseRow(bar.year, bar.incomeCents, bar.expenseCents)
                }
            }
        }
    }
}

/** 月度/年度收支共用一行：标签 + 收/支/净额 */
@Composable
private fun IncomeExpenseRow(label: String, incomeCents: Long, expenseCents: Long) {
    val net = incomeCents - expenseCents
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("收 ${formatMoney(incomeCents)}", color = Color(0xFF66BB6A), style = MaterialTheme.typography.bodySmall)
            Text("支 ${formatMoney(expenseCents)}", color = Color(0xFFEF5350), style = MaterialTheme.typography.bodySmall)
            Text(
                if (net >= 0) "+${formatMoney(net)}" else formatMoney(net),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
