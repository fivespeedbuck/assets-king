package com.assetsking.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetsking.app.LedgerUiState
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.TransactionEntity
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatMoneyCompact
import com.assetsking.ui.theme.ExpenseRed
import com.assetsking.ui.theme.IncomeGreen
import com.assetsking.ui.theme.ThemePrimaryGreen
import com.assetsking.usecase.GetStatsUseCase
import com.assetsking.usecase.StatsData
import java.time.YearMonth
import java.time.ZoneId

private val StatsGreen = IncomeGreen
private val StatsRed = ExpenseRed

private val categoryPalette = listOf(
    ThemePrimaryGreen, Color(0xFFF2A93B), Color(0xFF5C9CE6), Color(0xFF6BCB8F),
    Color(0xFF90A4AE), Color(0xFF8D6E63), Color(0xFF4ECDC4), Color(0xFF6B7A75)
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
    freeSpendingCents: Long,
    onGotoTransactions: (YearMonth, String?) -> Unit
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var trendMonths by remember { mutableStateOf(3) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var drillCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var stats by remember { mutableStateOf<StatsData?>(null) }
    var showAllBudgets by remember { mutableStateOf(false) }
    // 趋势卡选中月（REQ 统计§20）：默认本月；点柱切换顶部数字，再点一次下钻流水
    var selectedBar by remember { mutableStateOf(YearMonth.now()) }

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

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 月份切换（REQ 统计§21）──
        item {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上月") }
                Text(
                    "${month.year}年${month.monthValue}月",
                    Modifier.clickable { showMonthPicker = true }.padding(horizontal = 8.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(Icons.Outlined.CalendarToday, contentDescription = "选择月份", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月") }
            }
        }

        // ── ①本月消费组成（REQ 统计§14-16）──
        item {
            GlassCard(contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
                    val drill = drillCategory
                    if (drill != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("全部支出", Modifier.clickable { drillCategory = null }, color = MaterialTheme.colorScheme.primary)
                            Text(" ＞ ${drill.name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            // 下钻流水（REQ 统计§3/§15）：带月份+分类跳流水页筛选
                            TextButton(onClick = { onGotoTransactions(month, drill.name) }) { Text("查看流水", style = MaterialTheme.typography.labelSmall) }
                        }
                        // 下钻：该一级分类的 必要/非必要 比例 + 二级构成（REQ 统计§15）
                        val children = categories.filter { it.parentId == drill.id }
                        val total = expenses.filter { categories.firstOrNull { c -> c.name == it.category }?.parentId == drill.id }.sumOf { netOf(it) }
                        val necessary = expenses.filter {
                            categories.firstOrNull { c -> c.name == it.category }?.parentId == drill.id && it.necessity == true
                        }.sumOf { netOf(it) }
                        val optional = (total - necessary).coerceAtLeast(0L)
                        val drillIndex = topLevelTotals.indexOfFirst { it.key == drill.id }.coerceAtLeast(0)
                        val drillColor = catColor(drillIndex)
                        val drillOptionalColor = drillColor.copy(alpha = 0.55f)
                        DonutChart(
                            totalCents = total,
                            slices = listOf(necessary to drillColor, optional to drillOptionalColor),
                            modifier = Modifier.size(164.dp).align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(0.68f)
                                .align(Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val necessaryColor = if (necessary > 0L) StatsGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            val optionalColor = if (optional > 0L) StatsRed else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("必要", color = necessaryColor, style = MaterialTheme.typography.bodyMedium)
                                Text(formatMoney(necessary), color = necessaryColor, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("非必要", color = optionalColor, style = MaterialTheme.typography.bodyMedium)
                                Text(formatMoney(optional), color = optionalColor, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Column(Modifier.fillMaxWidth(0.84f).align(Alignment.CenterHorizontally)) {
                            children.forEach { child ->
                                val childExpenses = expenses.filter { it.category == child.name }
                                val childNecessary = childExpenses.filter { it.necessity == true }.sumOf { netOf(it) }
                                val childOptional = childExpenses.filter { it.necessity != true }.sumOf { netOf(it) }
                                val childTotal = childNecessary + childOptional
                                if (childTotal > 0) {
                                    val childTextColor = when {
                                        childOptional == 0L -> StatsGreen
                                        childNecessary == 0L -> StatsRed
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(7.dp).background(drillColor, CircleShape))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            child.name,
                                            color = childTextColor,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (childNecessary > 0L && childOptional > 0L) {
                                            Text(formatMoney(childNecessary), color = StatsGreen, style = MaterialTheme.typography.labelSmall)
                                            Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                            Text(formatMoney(childOptional), color = StatsRed, style = MaterialTheme.typography.labelSmall)
                                        } else {
                                            Text(
                                                formatMoney(childTotal),
                                                color = if (childNecessary > 0L) StatsGreen else StatsRed,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("本月消费组成", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "点击分类看流水",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        val slices = topLevelTotals.mapIndexed { i, (parentId, total) ->
                            val parent = categories.firstOrNull { it.id == parentId }
                            Triple(parent?.name ?: "其他", total, catColor(i))
                        }
                        // 双层环（REQ 统计§14）：内圈一级占比、外圈显示各一级分类内部的必要/非必要构成
                        // 审核 J-2 修复：外圈原只按全局必要/非必要/默认三段，与「各一级分类内部构成」口径不符。
                        // 改为每个一级分类在外圈拆成必要(实色)/非必要(半透明)两段，顺序与内圈一致。
                        val outerSlices = topLevelTotals.mapIndexed { i, (parentId, _) ->
                            val color = catColor(i)
                            val sub = expenses.filter { tx ->
                                (categories.firstOrNull { it.name == tx.category }?.parentId
                                    ?: categories.firstOrNull { it.id == tx.category }?.id
                                    ?: "other") == parentId
                            }
                            val nec = sub.filter { it.necessity == true }.sumOf { netOf(it) }
                            val opt = sub.filter { it.necessity != true }.sumOf { netOf(it) } // 非必要 + 默认
                            listOfNotNull(
                                if (nec > 0) nec to color else null,
                                if (opt > 0) opt to color.copy(alpha = 0.4f) else null
                            )
                        }.flatten()
                        DonutChart(
                            totalCents = monthExpense,
                            slices = slices.map { (_, total, color) -> total to color },
                            outerSlices = outerSlices,
                            sliceLabels = slices.map { it.first },
                            onSliceClick = { index ->
                                val parentId = topLevelTotals.getOrNull(index)?.key ?: return@DonutChart
                                categories.firstOrNull { it.id == parentId }?.let { drillCategory = it }
                            },
                            modifier = Modifier.fillMaxWidth().height(184.dp)
                        )
                        CompositionLegend()
                        Spacer(Modifier.height(7.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 报销单独显示（REQ 报销§5）：本月报销到账已从消费/预算冲减，不计普通收入
                        val monthReimbursed = monthTxs.filter { it.type == "REIMBURSEMENT" }.sumOf { it.amountCents }
                        if (monthReimbursed > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "本月报销到账 ${formatMoney(monthReimbursed)}（已从消费冲减）",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatsGreen,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 分类列表：金额/占比/非必要占比，点击下钻（REQ 统计§3/§14）
                        slices.forEach { (name, total, color) ->
                            val parent = categories.firstOrNull { it.name == name }
                            val nonNec = if (parent != null) expenses.filter {
                                categories.firstOrNull { c -> c.name == it.category }?.parentId == parent.id && it.necessity == false
                            }.sumOf { netOf(it) } else 0L
                            CategoryStatRow(
                                name = name,
                                amountCents = total,
                                nonNecessaryCents = nonNec,
                                totalMonthCents = monthExpense,
                                color = color,
                                onClick = { parent?.let { drillCategory = it } }
                            )
                        }
                        if (slices.isEmpty()) {
                            Text(
                                "本月暂无消费",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            val top = slices.first()
                            val topCategory = categories.firstOrNull { it.name == top.first }
                            val topNonNecessary = topCategory?.let { category ->
                                expenses.filter {
                                    categories.firstOrNull { c -> c.name == it.category }?.parentId == category.id && it.necessity == false
                                }.sumOf { netOf(it) }
                            } ?: 0L
                            InsightCard(
                                text = "${top.first}本月最大支出，其中非必要消费 ${formatMoneyCompact(topNonNecessary)}。"
                            )
                        }
                    }
                }
            }
        }

        // ── ②本月预算（REQ 统计§6/§17-18）──
        item {
            GlassCard(contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
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
                    // 查看全部（REQ 统计§17-18）：完整预算页，未消费分类也显示 0%
                    TextButton(
                        onClick = { showAllBudgets = true },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("查看全部", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        // ── ③收支趋势（REQ 统计§19-20）──
        item {
            GlassCard(contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
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
                        // 选中月顶部数字（REQ 统计§5/§20）：默认本月，点击柱切换，再点一次下钻流水
                        val sel = bars.firstOrNull { it.month == selectedBar.toString() } ?: bars.last()
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Text("${selectedBar.monthValue}月 收入 ${formatMoney(sel.incomeCents)}", color = StatsGreen, style = MaterialTheme.typography.labelMedium)
                            Text("支出 ${formatMoney(sel.expenseCents)}", color = StatsRed, style = MaterialTheme.typography.labelMedium)
                            Text("结余 ${formatMoney(sel.incomeCents - sel.expenseCents)}", color = if (sel.incomeCents >= sel.expenseCents) StatsGreen else StatsRed, style = MaterialTheme.typography.labelMedium)
                        }
                        TrendChart(
                            bars = bars,
                            selectedMonth = selectedBar,
                            onBarClick = { ym ->
                                if (ym == selectedBar) onGotoTransactions(ym, null)  // 再点一次下钻流水
                                else selectedBar = ym
                            }
                        )
                        Text("点柱选月 · 再点一次进流水", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (showAllBudgets) {
        // 完整预算页（REQ 统计§18）：所有已设预算，未消费显示 0%
        AlertDialog(
            onDismissRequest = { showAllBudgets = false },
            title = { Text("${month.year}年${month.monthValue}月 全部预算") },
            text = {
                val monthBudgets = budgets.filter { it.month == month.toString() }
                Column {
                    if (monthBudgets.isEmpty()) {
                        Text("本月未设分类预算", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    monthBudgets.forEach { b ->
                        val spent = expenses.filter { it.category == b.category }.sumOf { netOf(it) }
                        ProgressLine(b.category, spent, b.monthlyLimitCents, StatsGreen)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAllBudgets = false }) { Text("关闭") } },
            dismissButton = {}
        )
    }
}

@Composable
private fun CompositionLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = StatsGreen, label = "必要")
        Spacer(Modifier.width(18.dp))
        LegendItem(color = StatsGreen.copy(alpha = 0.35f), label = "非必要")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 18.dp, height = 6.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 分类排行：数字摘要 + 必要/非必要分段条，点击仍沿用原有下钻行为。 */
@Composable
private fun CategoryStatRow(
    name: String,
    amountCents: Long,
    nonNecessaryCents: Long,
    totalMonthCents: Long,
    color: Color,
    onClick: () -> Unit
) {
    val share = if (totalMonthCents > 0) (amountCents * 100 / totalMonthCents).coerceIn(0, 100) else 0
    val nonNecessary = nonNecessaryCents.coerceIn(0, amountCents)
    val necessary = (amountCents - nonNecessary).coerceAtLeast(0L)
    val necessaryFraction = if (amountCents > 0) necessary.toFloat() / amountCents else 0f
    val nonNecessaryFraction = if (amountCents > 0) nonNecessary.toFloat() / amountCents else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$share%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(18.dp))
            Text(formatMoneyCompact(amountCents), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 6.dp)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
        ) {
            if (necessaryFraction > 0f) {
                Box(
                    Modifier
                        .weight(necessaryFraction)
                        .fillMaxHeight()
                        .background(color, RoundedCornerShape(3.dp))
                )
            }
            if (nonNecessaryFraction > 0f) {
                Box(
                    Modifier
                        .weight(nonNecessaryFraction)
                        .fillMaxHeight()
                        .background(color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                )
            }
        }
        if (nonNecessary > 0L) {
            Text(
                "非必要 ${nonNecessary * 100 / amountCents.coerceAtLeast(1)}%",
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 3.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall,
                color = StatsRed
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun InsightCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Lightbulb, contentDescription = "消费洞察", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 环形图：内圈占比环 + 可选外圈构成环 + 中心总支出（REQ 统计§14 双层同屏） */
@Composable
private fun DonutChart(
    totalCents: Long,
    slices: List<Pair<Long, Color>>,
    modifier: Modifier = Modifier,
    outerSlices: List<Pair<Long, Color>> = emptyList(),
    sliceLabels: List<String> = emptyList(),
    onSliceClick: ((Int) -> Unit)? = null
) {
    val ringTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val calloutColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val calloutStyle = TextStyle(color = calloutColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val callouts = if (totalCents > 0L) {
        slices.indices
            .filter { index -> slices[index].first.toDouble() / totalCents.toDouble() >= 0.05 && sliceLabels.getOrNull(index) != null }
            .sortedByDescending { slices[it].first }
            .take(4)
            .sorted()
            .mapNotNull { index ->
                sliceLabels.getOrNull(index)?.let { label -> index to textMeasurer.measure(label, style = calloutStyle) }
            }
    } else emptyList()
    val interactiveModifier = if (onSliceClick != null && totalCents > 0) {
        modifier.pointerInput(slices, totalCents) {
            detectTapGestures { tap ->
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val dx = tap.x - centerX
                val dy = tap.y - centerY
                val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                val dim = minOf(size.width, size.height).toFloat()
                if (radius !in dim * 0.27f..dim * 0.5f) return@detectTapGestures
                val angle = ((Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 90.0 + 360.0) % 360.0).toFloat()
                var start = 0f
                slices.forEachIndexed { index, (cents, _) ->
                    val sweep = cents * 360f / totalCents
                    if (angle >= start && angle < start + sweep) {
                        onSliceClick(index)
                        return@detectTapGestures
                    }
                    start += sweep
                }
            }
        }
    } else modifier
    Box(interactiveModifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val dim = size.minDimension
            fun ring(radius: Float, strokeW: Float, segs: List<Pair<Long, Color>>) {
                val ringSize = Size(radius * 2, radius * 2)
                val ringTopLeft = Offset(size.width / 2 - radius, size.height / 2 - radius)
                drawArc(
                    ringTrackColor,
                    -90f,
                    360f,
                    false,
                    style = Stroke(width = strokeW),
                    size = ringSize,
                    topLeft = ringTopLeft
                )
                var start = -90f
                segs.forEach { (cents, color) ->
                    if (cents <= 0 || totalCents <= 0) return@forEach
                    val sweep = cents * 360f / totalCents
                    drawArc(
                        color, start, sweep, false,
                        style = Stroke(width = strokeW),
                        size = ringSize,
                        topLeft = ringTopLeft
                    )
                    start += sweep
                }
            }
            if (outerSlices.isNotEmpty()) {
                ring(dim / 2f * 0.92f, dim * 0.07f, outerSlices)   // 外圈：必要/非必要构成
                ring(dim / 2f * 0.72f, dim * 0.12f, slices)        // 内圈：一级占比
            } else {
                ring(dim / 2f * 0.85f, dim * 0.14f, slices)
            }
            if (callouts.isNotEmpty() && totalCents > 0) {
                class CalloutGeometry(
                    val index: Int,
                    val layout: androidx.compose.ui.text.TextLayoutResult,
                    val start: Offset,
                    val bend: Offset,
                    val right: Boolean,
                    var lineY: Float
                )
                val center = Offset(size.width / 2f, size.height / 2f)
                val geometries = callouts.map { (index, layout) ->
                    val before = slices.take(index).sumOf { it.first }
                    val middleAngle = before * 360f / totalCents + slices[index].first * 180f / totalCents - 90f
                    val radians = Math.toRadians(middleAngle.toDouble())
                    val cos = kotlin.math.cos(radians).toFloat()
                    val sin = kotlin.math.sin(radians).toFloat()
                    val start = Offset(center.x + cos * dim * 0.46f, center.y + sin * dim * 0.46f)
                    val bend = Offset(center.x + cos * dim * 0.54f, center.y + sin * dim * 0.54f)
                    CalloutGeometry(index, layout, start, bend, cos >= 0f, bend.y)
                }
                listOf(false, true).forEach { rightSide ->
                    val side = geometries.filter { it.right == rightSide }.sortedBy { it.lineY }
                    val gap = 18.dp.toPx()
                    var cursor = 8.dp.toPx()
                    side.forEach { item ->
                        item.lineY = maxOf(item.lineY, cursor).coerceAtMost(size.height - 8.dp.toPx())
                        cursor = item.lineY + gap
                    }
                    val overflow = (side.lastOrNull()?.lineY ?: 0f) - (size.height - 8.dp.toPx())
                    if (overflow > 0f) side.forEach { it.lineY -= overflow }
                }
                geometries.forEach { item ->
                    val edgePadding = 8.dp.toPx()
                    val labelGap = 5.dp.toPx()
                    val labelX = if (item.right) size.width - edgePadding - item.layout.size.width else edgePadding
                    val lineEndX = if (item.right) labelX - labelGap else labelX + item.layout.size.width + labelGap
                    val end = Offset(lineEndX, item.lineY)
                    drawLine(calloutColor, item.start, item.bend, strokeWidth = 1.dp.toPx())
                    drawLine(calloutColor, item.bend, end, strokeWidth = 1.dp.toPx())
                    drawText(
                        textMeasurer = textMeasurer,
                        text = sliceLabels[item.index],
                        topLeft = Offset(
                            labelX,
                            (item.lineY - item.layout.size.height / 2f).coerceIn(0f, size.height - item.layout.size.height)
                        ),
                        style = calloutStyle
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("总支出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMoneyCompact(totalCents), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 组合图（REQ 统计§19）：并列柱（绿收入/红支出）+ 结余折线（蓝，中轴为零线）；点柱选月（REQ 统计§7/§20） */
@Composable
private fun TrendChart(
    bars: List<com.assetsking.usecase.MonthlyBar>,
    selectedMonth: YearMonth,
    onBarClick: (YearMonth) -> Unit
) {
    val max = bars.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents, 1L) } ?: 1L
    val balanceMax = bars.maxOfOrNull { kotlin.math.abs(it.incomeCents - it.expenseCents).coerceAtLeast(1L) } ?: 1L
    val balanceColor = Color(0xFF5C9CE6)
    val zeroLineColor = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val months = bars.map { bar -> YearMonth.of(bar.month.substring(0, 4).toInt(), bar.month.substring(5).toInt()) }
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(170.dp)
                .pointerInput(bars) {
                    detectTapGestures { off ->
                        val idx = (off.x / (size.width / bars.size.coerceAtLeast(1))).toInt().coerceIn(0, months.size - 1)
                        onBarClick(months[idx])
                    }
                }
        ) {
            val chartH = size.height - 26f
            val n = bars.size.coerceAtLeast(1)
            val groupW = size.width / n
            val barW = (groupW * 0.26f).coerceIn(2f, 18.dp.toPx())
            // 零线
            drawLine(
                zeroLineColor,
                Offset(0f, chartH / 2),
                Offset(size.width, chartH / 2),
                strokeWidth = 1f
            )
            val balancePts = ArrayList<Offset>(bars.size)
            bars.forEachIndexed { i, bar ->
                val cx = groupW * i + groupW / 2
                // 选中月高亮背景
                if (months[i] == selectedMonth) {
                    drawRect(
                        highlightColor,
                        topLeft = Offset(cx - groupW / 2, 0f),
                        size = Size(groupW, chartH)
                    )
                }
                val inH = chartH * (bar.incomeCents.toFloat() / max)
                val exH = chartH * (bar.expenseCents.toFloat() / max)
                drawRect(
                    color = StatsGreen,
                    topLeft = Offset(cx - barW - 1.dp.toPx(), chartH - inH),
                    size = Size(barW, inH)
                )
                drawRect(
                    color = StatsRed,
                    topLeft = Offset(cx + 1.dp.toPx(), chartH - exH),
                    size = Size(barW, exH)
                )
                val bal = (bar.incomeCents - bar.expenseCents).toFloat()
                balancePts.add(Offset(cx, chartH / 2 - bal / balanceMax * (chartH / 2 - 10f)))
            }
            // 结余折线 + 端点
            for (i in 0 until balancePts.size - 1) {
                drawLine(balanceColor, balancePts[i], balancePts[i + 1], strokeWidth = 2.dp.toPx())
            }
            balancePts.forEach { pt -> drawCircle(balanceColor, radius = 3.dp.toPx(), center = pt) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            months.forEachIndexed { i, ym ->
                Text(
                    "${ym.monthValue}月",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ym == selectedMonth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (ym == selectedMonth) FontWeight.Bold else null,
                    modifier = Modifier.clickable { onBarClick(ym) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("绿=收入 红=支出 蓝线=结余/赤字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
