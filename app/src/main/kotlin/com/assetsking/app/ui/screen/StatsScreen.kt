package com.assetsking.app.ui.screen

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerUiState
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.TransactionEntity
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.usecase.GetStatsUseCase
import com.assetsking.usecase.StatsData
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val StatsGreen = Color(0xFF66BB6A)
private val StatsRed = Color(0xFFE57373)

private val categoryPalette = listOf(
    Color(0xFF5C9CE6), Color(0xFF9B8AFB), Color(0xFFF2A93B), Color(0xFF6BCB8F),
    Color(0xFFE86E6E), Color(0xFF4ECDC4), Color(0xFFF28DB2), Color(0xFF8D6E63),
    Color(0xFF90A4AE), Color(0xFFFFB74D), Color(0xFF7986CB)
)

private fun catColor(index: Int): Color = categoryPalette[index % categoryPalette.size]

/**
 * 统计页三张核心大卡（REQ 统计与流水 §2-21）：
 * ①本月消费组成双层环形图（内圈一级占比、外圈必要/非必要构成，可下钻）
 * ②本月预算（必要预算 + 自由开销两条总进度 + 前 4 分类）
 * ③收支趋势（并列柱 + 结余折线，3/6/12 月切换）
 */
@Composable
fun StatsScreen(
    state: LedgerUiState,
    categories: List<CategoryEntity>,
    budgets: List<BudgetEntity>,
    repository: LedgerRepository,
    freeSpendingCents: Long
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var trendMonths by remember { mutableStateOf(3) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var drillCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var stats by remember { mutableStateOf<StatsData?>(null) }

    LaunchedEffect(Unit) { stats = GetStatsUseCase(repository).invoke() }

    val zone = ZoneId.systemDefault()
    val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val monthTxs = state.transactions.filter { it.occurredAt in monthStart..monthEnd }
    val refundOffset = monthTxs.filter { it.type == "REFUND" && it.refundOfId != null }
        .groupBy { it.refundOfId!! }.mapValues { (_, rs) -> rs.sumOf { it.amountCents } }

    // 消费净额（冲减退款/报销）
    fun netOf(tx: TransactionEntity): Long =
        (tx.amountCents - (refundOffset[tx.id] ?: 0L) - tx.reimbursedCents).coerceAtLeast(0L)

    val expenses = monthTxs.filter { it.type == "EXPENSE" }
    val monthIncome = monthTxs.filter { it.type == "INCOME" }.sumOf { it.amountCents }
    val monthExpense = expenses.sumOf { netOf(it) }

    // 一级分类消费（按 DB 分类名聚合）
    val topLevelTotals = expenses.groupBy { tx ->
        categories.firstOrNull { it.name == tx.category }?.parentId
            ?: categories.firstOrNull { it.id == tx.category }?.id
            ?: "other"
    }.mapValues { (_, txs) -> txs.sumOf { netOf(it) } }
        .entries.sortedByDescending { it.value }

    // 预算：必要已花 / 非必要已花
    val budgetSum = budgets.filter { it.month == month.toString() }.sumOf { it.monthlyLimitCents }
    val necessarySpent = expenses.filter { it.necessity == true }.sumOf { netOf(it) }
    val optionalSpent = expenses.filter { it.necessity == false }.sumOf { netOf(it) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── 月份切换（REQ 统计§21）──
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上月") }
                Text(
                    "${month.year}年${month.monthValue}月",
                    Modifier.clickable { showMonthPicker = true }.padding(horizontal = 8.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下月") }
            }
        }

        // ── ①本月消费组成（REQ 统计§14-16）──
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    val drill = drillCategory
                    if (drill != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("全部支出", Modifier.clickable { drillCategory = null }, color = MaterialTheme.colorScheme.primary)
                            Text(" ＞ ${drill.name}", fontWeight = FontWeight.Bold)
                        }
                        // 下钻：该一级分类的 必要/非必要 比例 + 二级构成（REQ 统计§15）
                        val children = categories.filter { it.parentId == drill.id }
                        val total = expenses.filter { categories.firstOrNull { c -> c.name == it.category }?.parentId == drill.id }.sumOf { netOf(it) }
                        val necessary = expenses.filter {
                            categories.firstOrNull { c -> c.name == it.category }?.parentId == drill.id && it.necessity == true
                        }.sumOf { netOf(it) }
                        val optional = (total - necessary).coerceAtLeast(0L)
                        DonutChart(
                            totalCents = total,
                            slices = listOf(necessary to StatsGreen, optional to StatsRed),
                            modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally)
                        )
                        Text("必要 ${formatMoney(necessary)} · 非必要 ${formatMoney(optional)}", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        children.forEach { child ->
                            val childTotal = expenses.filter { it.category == child.name }.sumOf { netOf(it) }
                            if (childTotal > 0) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(child.name)
                                    Text(formatMoney(childTotal))
                                }
                            }
                        }
                    } else {
                        Text("本月消费组成", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        val slices = topLevelTotals.mapIndexed { i, (parentId, total) ->
                            val parent = categories.firstOrNull { it.id == parentId }
                            Triple(parent?.name ?: "其他", total, catColor(i))
                        }
                        DonutChart(
                            totalCents = monthExpense,
                            slices = slices.map { (_, total, color) -> total to color },
                            modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(6.dp))
                        // 分类列表：金额/占比/非必要占比，点击下钻（REQ 统计§3/§14）
                        slices.forEach { (name, total, color) ->
                            val parent = categories.firstOrNull { it.name == name }
                            val nonNec = if (parent != null) expenses.filter {
                                categories.firstOrNull { c -> c.name == it.category }?.parentId == parent.id && it.necessity == false
                            }.sumOf { netOf(it) } else 0L
                            Row(
                                Modifier.fillMaxWidth().clickable { parent?.let { drillCategory = it } }.padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(10.dp).background(color, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(name, Modifier.weight(1f))
                                Text(
                                    "${formatMoney(total)} · ${if (monthExpense > 0) (total * 100 / monthExpense) else 0}%" +
                                        if (nonNec > 0) " · 非必要 ${nonNec * 100 / total.coerceAtLeast(1)}%" else "",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (slices.isEmpty()) Text("本月暂无消费", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── ②本月预算（REQ 统计§6/§17-18）──
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("本月预算", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    ProgressLine("必要预算", necessarySpent, budgetSum, StatsGreen)
                    ProgressLine("自由开销", optionalSpent, freeSpendingCents, StatsRed)
                    Spacer(Modifier.height(8.dp))
                    val budgetCats = budgets.filter { it.month == month.toString() }
                        .sortedByDescending { b -> expenses.filter { it.category == b.category }.sumOf { netOf(it) } }.take(4)
                    budgetCats.forEach { b ->
                        val spent = expenses.filter { it.category == b.category }.sumOf { netOf(it) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(b.category, style = MaterialTheme.typography.bodySmall)
                            Text("${formatMoney(spent)} / ${formatMoney(b.monthlyLimitCents)}", style = MaterialTheme.typography.bodySmall, color = if (spent > b.monthlyLimitCents) StatsRed else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ── ③收支趋势（REQ 统计§19-20）──
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("收支趋势", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Row {
                            listOf(3, 6, 12).forEach { n ->
                                FilterChip(selected = trendMonths == n, onClick = { trendMonths = n }, label = { Text("${n}月") }, modifier = Modifier.padding(horizontal = 2.dp))
                            }
                        }
                    }
                    val bars = stats?.monthlyBars?.takeLast(trendMonths) ?: emptyList()
                    if (bars.isNotEmpty()) {
                        TrendChart(bars)
                    } else {
                        Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(initial = month, onPick = { month = it; showMonthPicker = false }, onDismiss = { showMonthPicker = false })
    }
}

/** 环形图：分类占比环 + 中心总支出（REQ 统计§14；双层构成为下钻页的 必要/非必要 环） */
@Composable
private fun DonutChart(
    totalCents: Long,
    slices: List<Pair<Long, Color>>,
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f * 0.85f
            var start = -90f
            slices.forEach { (cents, color) ->
                if (cents <= 0 || totalCents <= 0) return@forEach
                val sweep = cents * 360f / totalCents
                drawArc(
                    color, start, sweep, false,
                    style = Stroke(width = size.minDimension * 0.14f),
                    size = Size(outer * 2, outer * 2),
                    topLeft = Offset(size.width / 2 - outer, size.height / 2 - outer)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("总支出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMoney(totalCents), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TrendChart(bars: List<com.assetsking.usecase.MonthlyBar>) {
    val max = bars.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents, 1L) } ?: 1L
    Column {
        bars.forEach { bar ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bar.month.substring(5) + "月", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                Box(Modifier.weight(1f).height(18.dp)) {
                    // 收入柱（绿）+ 支出柱（红）
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(bar.incomeCents.toFloat()).height(10.dp).background(StatsGreen, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(2.dp))
                        Box(Modifier.weight(bar.expenseCents.toFloat()).height(10.dp).background(StatsRed, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
                        Box(Modifier.weight((max - bar.incomeCents - bar.expenseCents).coerceAtLeast(0).toFloat()))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (bar.incomeCents - bar.expenseCents >= 0) "+${formatMoney(bar.incomeCents - bar.expenseCents)}" else "−${formatMoney(bar.expenseCents - bar.incomeCents)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bar.incomeCents - bar.expenseCents >= 0) StatsGreen else StatsRed
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("绿=收入 红=支出 右侧=结余/赤字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProgressLine(label: String, spent: Long, budget: Long, color: Color) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                "${formatMoney(spent)} / ${formatMoney(budget)} · ${if (budget > 0) spent * 100 / budget else 0}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (budget > 0 && spent > budget) StatsRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))) {
            val frac = if (budget > 0) (spent.toFloat() / budget).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).height(8.dp).background(color, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
        }
    }
}
