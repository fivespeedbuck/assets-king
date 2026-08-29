package com.assetsking.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.assetsking.app.LedgerUiState
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.TransactionEntity
import com.assetsking.app.ui.privacy.LocalPrivacyChaosFrame
import com.assetsking.app.ui.privacy.animatePrivacyValue
import com.assetsking.app.ui.privacy.privacyFakeIndex
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakePercent
import com.assetsking.app.ui.privacy.privacyFakeYearMonth
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.IconLibrary
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatMoneyCompact
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.privacy.PRIVACY_MASK
import com.assetsking.ui.privacy.PrivacyMode
import com.assetsking.ui.theme.ExpenseRed
import com.assetsking.ui.theme.IncomeGreen
import com.assetsking.ui.theme.ReimbursementYellow
import com.assetsking.ui.theme.BalanceBlue
import com.assetsking.ui.theme.RepaymentPurple
import com.assetsking.ui.theme.UiTokens
import com.assetsking.ui.theme.DeficitRed
import com.assetsking.ui.theme.PendingOrange
import com.assetsking.ui.theme.cashBalanceColor
import com.assetsking.usecase.GetStatsUseCase
import com.assetsking.usecase.StatsData
import com.assetsking.usecase.cashFlowSummary
import java.time.YearMonth
import java.time.ZoneId

private val StatsGreen = IncomeGreen
private val StatsRed = ExpenseRed
internal const val DEFAULT_TREND_MONTHS = 6
private const val TREND_PLOT_LEFT_DP = 44

private val categoryPalette = listOf(
    Color(0xFF49BE98), // 薄荷糖
    Color(0xFFEEAD49), // 杏桃糖
    Color(0xFF649EE2), // 晴空糖
    Color(0xFFED7A70), // 珊瑚糖
    Color(0xFF9A7DD2), // 葡萄糖
    Color(0xFF52B9C1), // 青柠糖
    Color(0xFFE47DAA), // 草莓糖
    Color(0xFFF79A69)  // 蜜桃糖
)

private fun catColor(index: Int): Color = categoryPalette[index % categoryPalette.size]
private val privacyIconKeys = listOf(
    "home", "restaurant", "directions-bus", "pets", "shopping-cart",
    "sports-esports", "local-hospital", "school", "flight", "more-horiz"
)

/**
 * 统计页三张核心大卡（REQ 统计与流水 §2-21）：
 * ①本月消费组成双层环形图（内圈一级占比、外圈必要/非必要构成，可下钻）
 * ②本月预算（必要预算 + 自由开销两条总进度 + 前 4 分类）
 * ③收支与还款趋势（收入/结余组成柱 + 支出/还款组成柱，3/6/12 月切换）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(
    state: LedgerUiState,
    categories: List<CategoryEntity>,
    budgets: List<BudgetEntity>,
    repository: LedgerRepository,
    freeSpendingCents: Long,
    onGotoTransactions: (YearMonth, String?) -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    var month by remember { mutableStateOf(YearMonth.now()) }
    var trendMonths by remember { mutableStateOf(DEFAULT_TREND_MONTHS) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var drillCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    BackHandler(enabled = drillCategory != null) { drillCategory = null }
    var stats by remember { mutableStateOf<StatsData?>(null) }
    var showBudgetDetails by remember { mutableStateOf(false) }
    // 趋势卡选中月：默认本月；图表点击只切换月份，避免窄柱二次点击误触跳转。
    var selectedBar by remember { mutableStateOf(YearMonth.now()) }

    LaunchedEffect(Unit) { stats = GetStatsUseCase(repository).invoke() }

    val zone = ZoneId.systemDefault()
    val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val monthTxs = state.transactions.filter { it.status == "CONFIRMED" && it.occurredAt in monthStart..monthEnd }
    val refundOffset = monthTxs.filter { it.type == "REFUND" && it.refundOfId != null }
        .groupBy { it.refundOfId!! }.mapValues { (_, rs) -> rs.sumOf { it.amountCents } }

    // 消费净额（冲减退款/报销）
    fun netOf(tx: TransactionEntity): Long =
        (tx.amountCents - (refundOffset[tx.id] ?: 0L) - tx.reimbursedCents).coerceAtLeast(0L)

    val expenses = monthTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }
    val monthCashFlow = cashFlowSummary(
        transactions = monthTxs,
        transfers = state.transfers.filter { it.occurredAt in monthStart..monthEnd },
        accounts = state.accounts
    )
    val monthIncome = monthCashFlow.incomeCents
    val monthExpense = monthCashFlow.expenseCents
    val monthRepayment = monthCashFlow.repaymentCents

    // 一级分类消费（按 DB 分类名聚合）
    val topLevelTotals = expenses.groupBy { tx ->
        categories.firstOrNull { it.name == tx.category }?.parentId
            ?: categories.firstOrNull { it.id == tx.category }?.id
            ?: "other"
    }.mapValues { (_, txs) -> txs.sumOf { netOf(it) } }
        .filterValues { it > 0L }
        .entries.sortedByDescending { it.value }

    // 预算：必要已花 / 非必要已花
    val budgetSum = necessaryBudgetCents(budgets, categories, month.toString())
    val monthSpending = monthSpendingBreakdown(state.transactions, categories, monthStart, monthEnd)
    val necessarySpent = monthSpending.necessaryCents
    val optionalSpent = monthSpending.optionalCents

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = UiTokens.PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)
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
                    if (privacyEnabled) privacyFakeYearMonth(400) else "${month.year}年${month.monthValue}月",
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
                            Text(
                                " ＞ ${if (privacyEnabled) privacyObfuscatedText(drill.name, 401) else drill.name}",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            // 下钻流水（REQ 统计§3/§15）：带月份+分类跳流水页筛选
                            StatsHeaderActionButton("查看流水") { onGotoTransactions(month, drill.name) }
                        }
                        // 下钻：该一级分类的 必要/非必要 比例 + 二级构成（REQ 统计§15）
                        val children = categories.filter { it.parentId == drill.id }
                        val total = expenses.filter { categories.firstOrNull { c -> c.name == it.category }?.parentId == drill.id }.sumOf { netOf(it) }
                        val necessary = expenses.filter {
                            categories.firstOrNull { c -> c.name == it.category }?.parentId == drill.id && effectiveNecessary(it, categories)
                        }.sumOf { netOf(it) }
                        val optional = (total - necessary).coerceAtLeast(0L)
                        val drillIndex = topLevelTotals.indexOfFirst { it.key == drill.id }.coerceAtLeast(0)
                        val drillColor = catColor(drillIndex)
                        val drillOptionalColor = drillColor.copy(alpha = 0.65f)
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
                                Text(
                                    if (privacyEnabled) privacyFakeAmount(401) else formatMoney(necessary),
                                    color = necessaryColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("非必要", color = optionalColor, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (privacyEnabled) privacyFakeAmount(402) else formatMoney(optional),
                                    color = optionalColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Column(Modifier.fillMaxWidth(0.84f).align(Alignment.CenterHorizontally)) {
                            children.forEachIndexed { childIndex, child ->
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
                                            if (privacyEnabled) privacyObfuscatedText(child.name, 410 + childIndex) else child.name,
                                            color = childTextColor,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (childNecessary > 0L && childOptional > 0L) {
                                            Text(
                                                if (privacyEnabled) privacyFakeAmount(420 + childIndex * 3) else formatMoney(childNecessary),
                                                color = StatsGreen,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                if (privacyEnabled) privacyFakeAmount(421 + childIndex * 3) else formatMoney(childOptional),
                                                color = StatsRed,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        } else {
                                            Text(
                                                if (privacyEnabled) privacyFakeAmount(422 + childIndex * 3) else formatMoney(childTotal),
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
                            val nec = sub.filter { effectiveNecessary(it, categories) }.sumOf { netOf(it) }
                            val opt = sub.filterNot { effectiveNecessary(it, categories) }.sumOf { netOf(it) }
                            listOfNotNull(
                                if (nec > 0) nec to color else null,
                                if (opt > 0) opt to color.copy(alpha = 0.55f) else null
                            )
                        }.flatten()
                        DonutChart(
                            totalCents = monthExpense,
                            centerIncomeCents = monthIncome,
                            centerRepaymentCents = monthRepayment,
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
                                if (privacyEnabled) {
                                    "${privacyObfuscatedText("本月报销到账", 428)} ${privacyFakeAmount(429)}（${privacyObfuscatedText("已从消费冲减", 430)}）"
                                } else {
                                    "本月报销到账 ${formatMoney(monthReimbursed)}（已从消费冲减）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = ReimbursementYellow,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 分类列表：金额/占比/非必要占比，点击下钻（REQ 统计§3/§14）
                        slices.forEachIndexed { index, (name, total, color) ->
                            val parent = categories.firstOrNull { it.name == name }
                            val nonNec = if (parent != null) expenses.filter {
                                categories.firstOrNull { c -> c.name == it.category }?.parentId == parent.id && !effectiveNecessary(it, categories)
                            }.sumOf { netOf(it) } else 0L
                            CategoryStatRow(
                                name = name,
                                iconKey = parent?.iconKey ?: "more-horiz",
                                amountCents = total,
                                nonNecessaryCents = nonNec,
                                totalMonthCents = monthExpense,
                                color = color,
                                privacyIndex = index,
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
                                text = if (privacyEnabled) {
                                    "${privacyObfuscatedText(top.first, 430)}本月最大支出，其中非必要消费 ${privacyFakeAmount(431)}。"
                                } else {
                                    "${top.first}本月最大支出，其中非必要消费 ${formatMoneyCompact(topNonNecessary)}。"
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── ②本月预算（REQ 统计§6/§17-18）──
        item {
            GlassCard(
                modifier = Modifier.clickable { showBudgetDetails = true },
                contentPadding = Modifier
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
                    Text("本月预算", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    BudgetProgressLine("必要预算", necessarySpent, budgetSum, StatsGreen, privacyIndex = 410)
                    BudgetProgressLine("自由开销", optionalSpent, freeSpendingCents, PendingOrange, privacyIndex = 412)
                }
            }
        }

        // ── ③收支与还款趋势（REQ 统计§19-20）──
        item {
            GlassCard(contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("收支与还款趋势", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(3, 6, 12).forEach { n ->
                                FilterChip(selected = trendMonths == n, onClick = { trendMonths = n }, label = { Text("${n}月") })
                            }
                        }
                    }
                    val bars = privacySafeMonthlyBars(
                        stats?.monthlyBars?.takeLast(trendMonths) ?: emptyList(),
                        privacyEnabled = privacyEnabled,
                        fakeFractions = LocalPrivacyChaosFrame.current.barFractions
                    ).mapIndexed { index, bar ->
                        if (!privacyEnabled) bar else bar.copy(
                            incomeCents = animatePrivacyValue(
                                bar.incomeCents.toFloat(),
                                "privacy-trend-income-$index"
                            ).toLong(),
                            expenseCents = animatePrivacyValue(
                                bar.expenseCents.toFloat(),
                                "privacy-trend-expense-$index"
                            ).toLong(),
                            repaymentCents = animatePrivacyValue(
                                bar.repaymentCents.toFloat(),
                                "privacy-trend-repayment-$index"
                            ).toLong()
                        )
                    }
                    if (bars.isNotEmpty()) {
                        // 选中月顶部数字（REQ 统计§5/§20）：默认本月，点击柱只切换月份。
                        val effectiveSelectedMonth = effectiveTrendMonth(bars.map { it.month }, selectedBar)
                        val sel = bars.first { it.month == effectiveSelectedMonth.toString() }
                        val balance = sel.incomeCents - sel.expenseCents - sel.repaymentCents
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 10.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                TrendMetric(
                                    if (privacyEnabled) "${privacyFakeYearMonth(450)}收入" else "${effectiveSelectedMonth.monthValue}月收入",
                                    sel.incomeCents,
                                    StatsGreen,
                                    privacyIndex = 600,
                                    modifier = Modifier.weight(1f)
                                )
                                TrendMetric("普通支出", sel.expenseCents, StatsRed, privacyIndex = 601, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth()) {
                                TrendMetric("已还款", sel.repaymentCents, RepaymentPurple, privacyIndex = 602, modifier = Modifier.weight(1f))
                                TrendMetric(
                                    if (balance >= 0L) "现金结余" else "现金赤字",
                                    kotlin.math.abs(balance),
                                    cashBalanceColor(balance),
                                    privacyIndex = 603,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        TrendChart(
                            bars = bars,
                            selectedMonth = effectiveSelectedMonth,
                            onBarClick = { ym -> selectedBar = ym }
                        )
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
    if (showBudgetDetails) {
        BudgetDetailsDialog(
            month = month,
            budgets = budgets,
            categories = categories,
            transactions = state.transactions,
            onDismiss = { showBudgetDetails = false }
        )
    }
}

@Composable
private fun StatsHeaderActionButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
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
    iconKey: String,
    amountCents: Long,
    nonNecessaryCents: Long,
    totalMonthCents: Long,
    color: Color,
    privacyIndex: Int,
    onClick: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyFrame = LocalPrivacyChaosFrame.current
    val share = if (privacyEnabled) privacyFakePercent(500 + privacyIndex)
    else if (totalMonthCents > 0) (amountCents * 100 / totalMonthCents).coerceIn(0, 100) else 0
    val nonNecessary = nonNecessaryCents.coerceIn(0, amountCents)
    val necessary = (amountCents - nonNecessary).coerceAtLeast(0L)
    val privacyNecessary = privacyFrame.progressFractions[(privacyIndex * 2) % privacyFrame.progressFractions.size]
    val necessaryFraction = animatePrivacyValue(
        if (privacyEnabled) privacyNecessary else if (amountCents > 0) necessary.toFloat() / amountCents else 0f,
        "privacy-category-necessary-$privacyIndex"
    )
    val nonNecessaryFraction = animatePrivacyValue(
        if (privacyEnabled) 1f - privacyNecessary else if (amountCents > 0) nonNecessary.toFloat() / amountCents else 0f,
        "privacy-category-optional-$privacyIndex"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).background(color.copy(alpha = 0.14f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                IconLibrary.byKey(
                    if (privacyEnabled) privacyIconKeys[privacyFakeIndex(520 + privacyIndex, privacyIconKeys.size)] else iconKey
                ),
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = color
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (privacyEnabled) privacyObfuscatedText(name, 540 + privacyIndex) else name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("$share%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(18.dp))
                Text(
                    if (privacyEnabled) privacyFakeAmount(560 + privacyIndex) else formatMoneyCompact(amountCents),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
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
            Box(
                Modifier.fillMaxWidth().height(23.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                val necessityLabel = if (privacyEnabled) "${privacyFakePercent(580 + privacyIndex)}%" else categoryNecessityLabel(amountCents, nonNecessary)
                Text(
                    necessityLabel,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nonNecessary > 0L) StatsRed else StatsGreen
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

internal fun categoryNecessityLabel(amountCents: Long, nonNecessaryCents: Long): String =
    if (nonNecessaryCents > 0L) {
        "${nonNecessaryCents * 100 / amountCents.coerceAtLeast(1)}%"
    } else {
        "0%"
    }

internal data class DonutCenterMetric(val label: String, val valueCents: Long)

internal fun donutCenterMetrics(
    expenseCents: Long,
    incomeCents: Long?,
    repaymentCents: Long = 0L
): List<DonutCenterMetric> =
    buildList {
        add(DonutCenterMetric("总支出", expenseCents))
        incomeCents?.let { income ->
            add(DonutCenterMetric("总收入", income))
            add(DonutCenterMetric("总结余", income - expenseCents - repaymentCents))
            add(DonutCenterMetric("已还款", repaymentCents))
        }
    }

internal fun nextDonutCenterMode(current: Int, count: Int): Int =
    if (count <= 1) 0 else (current + 1) % count

internal data class DonutCalloutInput(
    val index: Int,
    val right: Boolean,
    val rawY: Float,
    val labelWidth: Float,
    val labelHeight: Float,
    val visibleLeft: Float = 0f,
    val visibleRight: Float = labelWidth
)

internal data class DonutCalloutPlacement(
    val index: Int,
    val labelX: Float,
    val lineEndX: Float,
    val elbowX: Float,
    val lineY: Float
)

internal fun layoutDonutCallouts(
    canvasWidth: Float,
    canvasHeight: Float,
    edgePadding: Float,
    labelGap: Float,
    verticalGap: Float,
    inputs: List<DonutCalloutInput>
): List<DonutCalloutPlacement> {
    val centerX = canvasWidth / 2f
    val dim = minOf(canvasWidth, canvasHeight)
    val elbowDistance = dim * 0.53f
    val labelRailDistance = dim * 0.65f
    val placements = inputs.associate { input ->
        val visibleInnerEdge = if (input.right) {
            minOf(
                centerX + labelRailDistance,
                canvasWidth - edgePadding - input.labelWidth + input.visibleLeft
            )
        } else {
            maxOf(centerX - labelRailDistance, edgePadding + input.visibleRight)
        }
        val labelX = if (input.right) {
            visibleInnerEdge - input.visibleLeft
        } else {
            visibleInnerEdge - input.visibleRight
        }
        val lineEndX = if (input.right) {
            labelX + input.visibleLeft - labelGap
        } else {
            labelX + input.visibleRight + labelGap
        }
        input.index to DonutCalloutPlacement(
            index = input.index,
            labelX = labelX,
            lineEndX = lineEndX,
            elbowX = centerX + if (input.right) elbowDistance else -elbowDistance,
            lineY = input.rawY
        )
    }.toMutableMap()
    listOf(false, true).forEach { rightSide ->
        val side = inputs.filter { it.right == rightSide }.sortedBy { it.rawY }
        if (side.isNotEmpty()) {
            val ys = MutableList(side.size) { 0f }
            side.forEachIndexed { index, input ->
                val minY = edgePadding + input.labelHeight / 2f
                ys[index] = maxOf(input.rawY, minY, if (index == 0) minY else ys[index - 1] + verticalGap)
            }
            val lastMaxY = canvasHeight - edgePadding - side.last().labelHeight / 2f
            val overflow = (ys.last() - lastMaxY).coerceAtLeast(0f)
            if (overflow > 0f) ys.indices.forEach { ys[it] -= overflow }
            for (index in ys.lastIndex - 1 downTo 0) {
                ys[index] = minOf(ys[index], ys[index + 1] - verticalGap)
            }
            val firstMinY = edgePadding + side.first().labelHeight / 2f
            val underflow = (firstMinY - ys.first()).coerceAtLeast(0f)
            if (underflow > 0f) ys.indices.forEach { ys[it] += underflow }
            side.forEachIndexed { index, input ->
                val current = placements.getValue(input.index)
                placements[input.index] = current.copy(lineY = ys[index])
            }
        }
    }
    return inputs.map { placements.getValue(it.index) }
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
    centerIncomeCents: Long? = null,
    centerRepaymentCents: Long = 0L,
    outerSlices: List<Pair<Long, Color>> = emptyList(),
    sliceLabels: List<String> = emptyList(),
    onSliceClick: ((Int) -> Unit)? = null
) {
    val ringTrackColor = MaterialTheme.colorScheme.surfaceVariant
    if (LocalPrivacyEnabled.current) {
        val frame = LocalPrivacyChaosFrame.current
        val innerFractions = frame.innerRingFractions.mapIndexed { index, fraction ->
            animatePrivacyValue(fraction, "privacy-donut-inner-$index")
        }
        val outerFractions = frame.outerRingFractions.mapIndexed { index, fraction ->
            animatePrivacyValue(fraction, "privacy-donut-outer-$index")
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val dim = size.minDimension
                fun fakeRing(
                    radiusScale: Float,
                    widthScale: Float,
                    fractions: List<Float>,
                    colorOffset: Int
                ) {
                    val radius = dim / 2f * radiusScale
                    val ringSize = Size(radius * 2f, radius * 2f)
                    val topLeft = Offset(size.width / 2f - radius, size.height / 2f - radius)
                    drawArc(
                        color = ringTrackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = dim * widthScale),
                        size = ringSize,
                        topLeft = topLeft
                    )
                    var start = -90f
                    fractions.forEachIndexed { index, fraction ->
                        val sweep = (fraction * 360f - 2f).coerceAtLeast(1f)
                        drawArc(
                            color = categoryPalette[(index + colorOffset) % categoryPalette.size],
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(width = dim * widthScale),
                            size = ringSize,
                            topLeft = topLeft
                        )
                        start += fraction * 360f
                    }
                }
                fakeRing(0.92f, 0.07f, outerFractions, 2)
                fakeRing(0.69f, 0.11f, innerFractions, 0)
            }
            Text(privacyFakeAmount(0), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    val calloutColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val centerMetrics = remember(totalCents, centerIncomeCents, centerRepaymentCents) {
        donutCenterMetrics(totalCents, centerIncomeCents, centerRepaymentCents)
    }
    var centerMode by rememberSaveable(totalCents, centerIncomeCents, centerRepaymentCents) { mutableIntStateOf(0) }
    val centerMetric = centerMetrics[centerMode.coerceIn(centerMetrics.indices)]
    val centerValueColor = when (centerMetric.label) {
        "总收入" -> StatsGreen
        "总结余" -> cashBalanceColor(centerMetric.valueCents)
        "已还款" -> RepaymentPurple
        else -> MaterialTheme.colorScheme.onSurface
    }
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
                ring(dim / 2f * 0.69f, dim * 0.11f, slices)        // 内圈：一级占比，保留清晰环间距
            } else {
                ring(dim / 2f * 0.85f, dim * 0.14f, slices)
            }
            if (callouts.isNotEmpty() && totalCents > 0) {
                data class CalloutGeometry(
                    val layout: androidx.compose.ui.text.TextLayoutResult,
                    val start: Offset,
                    val placement: DonutCalloutPlacement
                )
                val center = Offset(size.width / 2f, size.height / 2f)
                val rawCallouts = callouts.map { (index, layout) ->
                    val before = slices.take(index).sumOf { it.first }
                    val middleAngle = before * 360f / totalCents + slices[index].first * 180f / totalCents - 90f
                    val radians = Math.toRadians(middleAngle.toDouble())
                    val cos = kotlin.math.cos(radians).toFloat()
                    val sin = kotlin.math.sin(radians).toFloat()
                    val start = Offset(center.x + cos * dim * 0.46f, center.y + sin * dim * 0.46f)
                    val bend = Offset(center.x + cos * dim * 0.54f, center.y + sin * dim * 0.54f)
                    val glyphBounds = sliceLabels[index].indices.map(layout::getBoundingBox)
                    Triple(
                        layout,
                        start,
                        DonutCalloutInput(
                            index = index,
                            right = cos >= 0f,
                            rawY = bend.y,
                            labelWidth = layout.size.width.toFloat(),
                            labelHeight = layout.size.height.toFloat(),
                            visibleLeft = glyphBounds.minOfOrNull { it.left } ?: 0f,
                            visibleRight = glyphBounds.maxOfOrNull { it.right } ?: layout.size.width.toFloat()
                        )
                    )
                }
                val placements = layoutDonutCallouts(
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    edgePadding = 8.dp.toPx(),
                    labelGap = 10.dp.toPx(),
                    verticalGap = 18.dp.toPx(),
                    inputs = rawCallouts.map { it.third }
                ).associateBy { it.index }
                val geometries = rawCallouts.map { (layout, start, input) ->
                    CalloutGeometry(layout, start, placements.getValue(input.index))
                }
                geometries.forEach { item ->
                    val placement = item.placement
                    val elbow = Offset(placement.elbowX, placement.lineY)
                    val end = Offset(placement.lineEndX, placement.lineY)
                    drawLine(calloutColor, item.start, elbow, strokeWidth = 1.dp.toPx())
                    drawLine(calloutColor, elbow, end, strokeWidth = 1.dp.toPx())
                    drawText(
                        textMeasurer = textMeasurer,
                        text = sliceLabels[placement.index],
                        topLeft = Offset(
                            placement.labelX,
                            (placement.lineY - item.layout.size.height / 2f).coerceIn(0f, size.height - item.layout.size.height)
                        ),
                        style = calloutStyle
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = centerMetrics.size > 1) {
                    centerMode = nextDonutCenterMode(centerMode, centerMetrics.size)
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(centerMetric.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (centerMetric.valueCents >= 0L) {
                    formatMoneyCompact(centerMetric.valueCents)
                } else {
                    "−${formatMoneyCompact(-centerMetric.valueCents)}"
                },
                color = centerValueColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun TrendMetric(
    label: String,
    value: Long,
    color: Color,
    privacyIndex: Int,
    modifier: Modifier = Modifier
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (privacyEnabled) privacyFakeAmount(privacyIndex) else formatMoneyCompact(value),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

internal data class TrendAxisRange(
    val minCents: Long,
    val maxCents: Long,
    val tickStepCents: Long
) {
    fun yFor(valueCents: Long, top: Float, bottom: Float): Float {
        val span = (maxCents - minCents).coerceAtLeast(1L).toFloat()
        return bottom - (valueCents - minCents).toFloat() / span * (bottom - top)
    }

    fun tickValues(): List<Long> = buildList {
        var value = 0L
        while (value <= maxCents) {
            add(value)
            value += tickStepCents
        }
        value = -tickStepCents
        while (value >= minCents) {
            add(value)
            value -= tickStepCents
        }
    }.sorted()
}

private fun niceTrendStep(rawStep: Double): Long {
    if (!rawStep.isFinite() || rawStep <= 1.0) return 1L
    val exponent = kotlin.math.floor(kotlin.math.log10(rawStep)).toInt()
    val magnitude = Math.pow(10.0, exponent.toDouble())
    val fraction = rawStep / magnitude
    val niceFraction = when {
        fraction < 1.5 -> 1.0
        fraction < 2.25 -> 2.0
        fraction < 3.75 -> 2.5
        fraction < 7.5 -> 5.0
        else -> 10.0
    }
    return kotlin.math.round(niceFraction * magnitude).toLong().coerceAtLeast(1L)
}

internal fun trendAxisRange(incomes: List<Long>, outflows: List<Long>): TrendAxisRange {
    val rawMax = maxOf(1L, incomes.maxOrNull() ?: 0L, outflows.maxOrNull() ?: 0L)
    // 目标约 12% 顶部呼吸空间，再按 1/2/2.5/5 漂亮刻度贴合；避免 ¥10,941 被粗放抬到 ¥20,000。
    val desiredMax = rawMax * 1.12
    val step = niceTrendStep(desiredMax / 4.0)
    val max = kotlin.math.ceil(desiredMax / step).toLong() * step
    return TrendAxisRange(0L, max, step)
}

internal data class TrendComposition(
    val outflowCents: Long,
    val incomeCoveredOutflowCents: Long,
    val balanceCents: Long,
    val positiveBalanceCents: Long,
    val deficitCents: Long,
    val compositionHeightCents: Long
)

internal fun trendComposition(
    incomeCents: Long,
    expenseCents: Long,
    repaymentCents: Long
): TrendComposition {
    val outflow = expenseCents + repaymentCents
    val balance = incomeCents - outflow
    return TrendComposition(
        outflowCents = outflow,
        incomeCoveredOutflowCents = minOf(incomeCents, outflow),
        balanceCents = balance,
        positiveBalanceCents = balance.coerceAtLeast(0L),
        deficitCents = (-balance).coerceAtLeast(0L),
        compositionHeightCents = maxOf(incomeCents, outflow)
    )
}

/** 纵轴选中值只靠颜色区分语义，使用短金额把绘图区尽量还给柱图。 */
internal fun formatTrendAxisValue(cents: Long): String {
    if (PrivacyMode.enabled) return PRIVACY_MASK
    val sign = if (cents < 0L) "−" else ""
    val absolute = kotlin.math.abs(cents)
    val amount = if (absolute >= 100_000L) {
        val truncatedTenths = absolute / 10_000L
        java.lang.String.format(java.util.Locale.US, "%.1fK", truncatedTenths / 10.0)
            .replace(".0K", "K")
    } else if (absolute % 100L == 0L) {
        (absolute / 100L).toString()
    } else {
        java.lang.String.format(java.util.Locale.US, "%.1f", absolute / 100.0)
            .removeSuffix(".0")
    }
    return sign + amount
}

/** 隐私模式下月份布局保留，但柱高只由独立假值决定；关闭后立即恢复原始序列。 */
internal fun privacySafeMonthlyBars(
    bars: List<com.assetsking.usecase.MonthlyBar>,
    privacyEnabled: Boolean,
    fakeFractions: List<Triple<Float, Float, Float>> = emptyList()
): List<com.assetsking.usecase.MonthlyBar> =
    if (!privacyEnabled) bars else bars.mapIndexed { index, bar ->
        val fraction = fakeFractions.getOrNull(index % fakeFractions.size.coerceAtLeast(1))
            ?: Triple(0.52f, 0.34f, 0.18f)
        bar.copy(
            incomeCents = 700_000L + (fraction.first * 1_100_000L).toLong(),
            expenseCents = 180_000L + (fraction.second * 850_000L).toLong(),
            repaymentCents = 80_000L + (fraction.third * 620_000L).toLong()
        )
    }

/** 同轴标注碰撞处理：保持输入顺序，同时给相邻金额标签统一最小留白。 */
internal fun layoutTrendAnnotationYs(
    rawYs: List<Float>,
    top: Float,
    bottom: Float,
    minGap: Float
): List<Float> {
    if (rawYs.isEmpty()) return emptyList()
    val sorted = rawYs.withIndex().sortedBy { it.value }
    val ys = MutableList(sorted.size) { 0f }
    sorted.forEachIndexed { index, item ->
        ys[index] = maxOf(item.value, top, if (index == 0) top else ys[index - 1] + minGap)
    }
    val overflow = (ys.last() - bottom).coerceAtLeast(0f)
    if (overflow > 0f) ys.indices.forEach { ys[it] -= overflow }
    for (index in ys.lastIndex - 1 downTo 0) {
        ys[index] = minOf(ys[index], ys[index + 1] - minGap)
    }
    val underflow = (top - ys.first()).coerceAtLeast(0f)
    if (underflow > 0f) ys.indices.forEach { ys[it] += underflow }
    val byOriginalIndex = MutableList(rawYs.size) { 0f }
    sorted.forEachIndexed { sortedIndex, indexedValue ->
        byOriginalIndex[indexedValue.index] = ys[sortedIndex]
    }
    return byOriginalIndex
}

internal fun effectiveTrendMonth(availableMonths: List<String>, selectedMonth: YearMonth): YearMonth =
    if (selectedMonth.toString() in availableMonths) selectedMonth
    else availableMonths.lastOrNull()?.let(YearMonth::parse) ?: selectedMonth

internal fun trendMonthTickVisible(index: Int, totalMonths: Int, month: YearMonth): Boolean =
    totalMonths <= 6 || index == 0 || month.monthValue == 1 || index % 4 == 0 || index == totalMonths - 1

/** 单图组成柱：左侧收入中超出总流出的部分标蓝，右侧为普通支出 + 实际还款；赤字时右柱自然更高。 */
@Composable
private fun TrendChart(
    bars: List<com.assetsking.usecase.MonthlyBar>,
    selectedMonth: YearMonth,
    onBarClick: (YearMonth) -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyAxisLabels = listOf(
        privacyFakeAmount(740),
        privacyFakeAmount(741),
        privacyFakeAmount(742),
        privacyFakeAmount(743)
    )
    val compositions = bars.map { trendComposition(it.incomeCents, it.expenseCents, it.repaymentCents) }
    val axis = if (privacyEnabled) {
        // 隐秘假值跨帧时纵轴保持不变，避免“最大值换档”把整组柱子瞬间拉高。
        TrendAxisRange(minCents = 0L, maxCents = 2_000_000L, tickStepCents = 500_000L)
    } else {
        trendAxisRange(bars.map { it.incomeCents }, compositions.map { it.outflowCents })
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val months = bars.map { bar -> YearMonth.of(bar.month.substring(0, 4).toInt(), bar.month.substring(5).toInt()) }
    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val plotLeftDp = if (privacyEnabled) 72 else TREND_PLOT_LEFT_DP
    val chartDescription = if (privacyEnabled) {
        "隐秘模式动态趋势图，数值与月份均为持续变化的虚构数据"
    } else {
        bars.joinToString("；") { bar ->
            val ym = YearMonth.parse(bar.month)
            val flow = trendComposition(bar.incomeCents, bar.expenseCents, bar.repaymentCents)
            "${ym.monthValue}月收入${formatMoney(bar.incomeCents)}，普通支出${formatMoney(bar.expenseCents)}，" +
                "已还款${formatMoney(bar.repaymentCents)}，" +
                if (flow.balanceCents >= 0L) "现金结余${formatMoney(flow.balanceCents)}" else "现金赤字${formatMoney(flow.deficitCents)}"
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrendLegendItem(StatsGreen, "收入")
            TrendLegendItem(StatsRed, "支出")
            TrendLegendItem(RepaymentPurple, "还款")
            TrendLegendItem(BalanceBlue, "结余")
        }
        Canvas(
            Modifier.fillMaxWidth().height(182.dp)
                .semantics { contentDescription = chartDescription }
                .pointerInput(bars, selectedMonth, onBarClick) {
                    detectTapGestures { off ->
                        val plotLeft = plotLeftDp.dp.toPx()
                        val plotRight = size.width - 8.dp.toPx()
                        if (off.x !in plotLeft..plotRight || months.isEmpty()) return@detectTapGestures
                        val idx = ((off.x - plotLeft) / ((plotRight - plotLeft) / months.size)).toInt().coerceIn(0, months.lastIndex)
                        onBarClick(months[idx])
                    }
                }
        ) {
            val plotLeft = plotLeftDp.dp.toPx()
            val plotRight = size.width - 8.dp.toPx()
            val plotTop = 3.dp.toPx()
            val plotBottom = size.height - 8.dp.toPx()
            val plotWidth = plotRight - plotLeft
            val n = bars.size.coerceAtLeast(1)
            val groupW = plotWidth / n
            val barW = (groupW * 0.28f).coerceIn(2f, 18.dp.toPx())
            val zeroY = axis.yFor(0L, plotTop, plotBottom)
            val selectedIndex = months.indexOf(selectedMonth).coerceAtLeast(0)
            val selectedBar = bars[selectedIndex]
            val selectedFlow = compositions[selectedIndex]
            // 固定顺序的四行彩色数值轨道，与顶部摘要一致；不再按柱段锚点上下跳动。
            val selectedAnnotations = listOf(
                selectedBar.incomeCents to StatsGreen,
                selectedBar.expenseCents to StatsRed,
                selectedBar.repaymentCents to RepaymentPurple,
                selectedFlow.balanceCents to
                    cashBalanceColor(selectedFlow.balanceCents)
            )
            val selectedLabelYs = selectedAnnotations.indices.map { index ->
                plotTop + (12 + index * 20).dp.toPx()
            }

            fun drawAxisText(text: String, centerY: Float, style: TextStyle) {
                val layout = textMeasurer.measure(text, style = style)
                drawText(
                    textMeasurer = textMeasurer,
                    text = text,
                    topLeft = Offset(
                        (plotLeft - 5.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                        (centerY - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    ),
                    style = style
                )
            }

            axis.tickValues().forEach { tick ->
                val y = axis.yFor(tick, plotTop, plotBottom)
                drawLine(
                    if (tick == 0L) zeroLineColor else gridColor,
                    Offset(plotLeft, y),
                    Offset(plotRight, y),
                    strokeWidth = if (tick == 0L) 1.2.dp.toPx() else 1.dp.toPx()
                )
            }

            bars.forEachIndexed { i, bar ->
                val cx = plotLeft + groupW * i + groupW / 2
                val flow = compositions[i]
                // 选中月高亮背景
                if (months[i] == selectedMonth) {
                    drawRect(
                        highlightColor,
                        topLeft = Offset(cx - groupW / 2, plotTop),
                        size = Size(groupW, plotBottom - plotTop)
                    )
                }
                val incomeX = cx - barW - 1.dp.toPx()
                val outflowX = cx + 1.dp.toPx()
                fun drawSegment(x: Float, fromCents: Long, toCents: Long, color: Color) {
                    if (toCents <= fromCents) return
                    val topY = axis.yFor(toCents, plotTop, plotBottom)
                    val bottomY = axis.yFor(fromCents, plotTop, plotBottom)
                    val rawHeight = bottomY - topY
                    val seam = minOf(0.55.dp.toPx(), rawHeight / 6f)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, topY + seam),
                        size = Size(barW, (rawHeight - seam * 2f).coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(2.5.dp.toPx())
                    )
                }
                // 蓝色结余属于收入柱：把绿柱高于红紫总流出的那一截直接标蓝。
                drawSegment(incomeX, 0L, flow.incomeCoveredOutflowCents, StatsGreen)
                drawSegment(incomeX, flow.incomeCoveredOutflowCents, bar.incomeCents, BalanceBlue)
                drawSegment(incomeX, bar.incomeCents, flow.compositionHeightCents, DeficitRed)
                drawSegment(outflowX, 0L, bar.expenseCents, StatsRed)
                drawSegment(outflowX, bar.expenseCents, flow.outflowCents, RepaymentPurple)
            }

            // 选中月份四项金额贴近左轴；仅以颜色和纵向位置表达，避免短连接线制造视觉噪声。
            selectedAnnotations.forEachIndexed { index, (value, color) ->
                val labelY = selectedLabelYs[index]
                drawAxisText(
                    if (privacyEnabled) privacyAxisLabels[index] else formatTrendAxisValue(value),
                    labelY,
                    axisTextStyle.copy(color = color, fontWeight = FontWeight.Bold)
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = plotLeftDp.dp, end = 8.dp)) {
            months.forEachIndexed { i, ym ->
                val tickVisible = trendMonthTickVisible(i, months.size, ym) || ym == selectedMonth
                Text(
                    if (privacyEnabled) privacyFakeYearMonth(760 + i).substringAfter("年") else "${ym.monthValue}月",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !tickVisible -> Color.Transparent
                        ym == selectedMonth -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (tickVisible && ym == selectedMonth) FontWeight.Bold else null,
                     modifier = Modifier
                         .weight(1f)
                         .defaultMinSize(minHeight = UiTokens.MinimumTouch)
                         .clickable { onBarClick(ym) }
                         .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TrendLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
