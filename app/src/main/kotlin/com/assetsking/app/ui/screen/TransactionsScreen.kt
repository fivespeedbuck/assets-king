package com.assetsking.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerUiState
import com.assetsking.app.LedgerViewModel
import com.assetsking.database.AccountEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.IconLibrary
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val FlowGreen = Color(0xFF66BB6A)
private val FlowRed = Color(0xFFE57373)
private val FlowGray = Color(0xFF757575)

private fun typeLabel(type: String): String = when (type) {
    "EXPENSE" -> "支出"
    "INCOME" -> "收入"
    "REFUND" -> "退款"
    "FEE" -> "手续费"
    "LOAN_DISBURSEMENT" -> "借款到账"
    "LOAN_PAYMENT" -> "贷款还款"
    "LOAN_PREPAYMENT" -> "提前还款"
    "REIMBURSEMENT" -> "报销到账"
    else -> type
}

private fun amountColorOf(type: String): Color = when (type) {
    "EXPENSE", "FEE" -> FlowRed
    "INCOME", "REFUND", "REIMBURSEMENT" -> FlowGreen
    else -> FlowGray
}

private fun amountPrefixOf(type: String): String = when (type) {
    "EXPENSE", "FEE" -> "−¥"
    "INCOME", "REFUND", "REIMBURSEMENT" -> "+¥"
    else -> "¥"
}

/**
 * 流水页（REQ 流水列表与详情 §1-22）：月度摘要卡、连续列表倒序、月历净变化、
 * 搜索与多条件筛选、长按多选批量操作、点击编辑。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    state: LedgerUiState,
    categories: List<CategoryEntity>,
    model: LedgerViewModel,
    onOpenEditor: () -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    initialFilterMonth: YearMonth? = null,
    initialFilterCategory: String? = null,
    onDrillConsumed: () -> Unit = {}
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var calendarMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var bulkCategory by remember { mutableStateOf(false) }
    var bulkNecessity by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    var editingTx by remember { mutableStateOf<TransactionEntity?>(null) }

    // 筛选条件（REQ 流水§11 多条件组合）
    var fTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fCategory by remember { mutableStateOf<String?>(null) }
    var fNecessity by remember { mutableStateOf<Boolean?>(null) }
    var fChannel by remember { mutableStateOf<String?>(null) }
    var fMinCents by remember { mutableStateOf("") }
    var fMaxCents by remember { mutableStateOf("") }

    // 统计页下钻（REQ 统计§3/§7/§20）：一次性带入月份+分类筛选，随后通知父级清空
    androidx.compose.runtime.LaunchedEffect(initialFilterMonth, initialFilterCategory) {
        if (initialFilterMonth != null) {
            month = initialFilterMonth
            fCategory = initialFilterCategory
        }
        onDrillConsumed()
    }

    val zone = ZoneId.systemDefault()
    val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    val monthTxs = state.transactions.filter { it.occurredAt in monthStart..monthEnd }
    val refundOffset = monthTxs.filter { it.type == "REFUND" && it.refundOfId != null }.sumOf { it.amountCents }
    val reimbOffset = monthTxs.filter { it.type == "EXPENSE" }.sumOf { it.reimbursedCents }
    val monthIncome = monthTxs.filter { it.type == "INCOME" }.sumOf { it.amountCents }
    val monthExpense = (monthTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }.sumOf { it.amountCents } - refundOffset - reimbOffset).coerceAtLeast(0L)

    // 筛选 + 搜索（REQ 流水§11-12）
    val accountNameOf = { id: String -> state.accounts.firstOrNull { it.id == id }?.name ?: "" }
    val minCents = fMinCents.toDoubleOrNull()?.times(100)?.toLong()
    val maxCents = fMaxCents.toDoubleOrNull()?.times(100)?.toLong()
    val filtered = monthTxs.filter { tx ->
        (fTypes.isEmpty() || tx.type in fTypes) &&
            (fCategory == null || tx.category == fCategory) &&
            (fNecessity == null || tx.necessity == fNecessity) &&
            (fChannel == null || tx.channel == fChannel) &&
            (minCents == null || tx.amountCents >= minCents) &&
            (maxCents == null || tx.amountCents <= maxCents) &&
            (searchQuery.isBlank() ||
                (tx.merchant?.contains(searchQuery) == true) ||
                (tx.note?.contains(searchQuery) == true) ||
                accountNameOf(tx.accountId).contains(searchQuery) ||
                formatMoney(tx.amountCents).contains(searchQuery))
    }.sortedByDescending { it.occurredAt }

    Column(Modifier.fillMaxSize()) {
        // ── 月度摘要卡（REQ 流水§16/§19）──
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上月") }
                Text(
                    "${month.year}年${month.monthValue}月",
                    Modifier.clickable { showMonthPicker = true }.padding(horizontal = 4.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下月") }
                IconButton(onClick = { calendarMode = !calendarMode }) {
                    Icon(Icons.Filled.DateRange, contentDescription = "月历", tint = if (calendarMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row {
                IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Filled.Search, contentDescription = "搜索", tint = if (showSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { showFilter = true }) { Icon(Icons.Filled.List, contentDescription = "筛选", tint = if (fTypes.isNotEmpty() || fCategory != null || fNecessity != null || fChannel != null || fMinCents.isNotBlank() || fMaxCents.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (!showSearch && !multiSelect) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("收入 ${formatMoney(monthIncome)}", color = FlowGreen, style = MaterialTheme.typography.bodyMedium)
                Text("支出 ${formatMoney(monthExpense)}", color = FlowRed, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "结余 ${if (monthIncome - monthExpense >= 0) formatMoney(monthIncome - monthExpense) else formatMoney(monthExpense - monthIncome)}",
                    color = if (monthIncome - monthExpense >= 0) FlowGreen else FlowRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (showSearch || multiSelect) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("搜索商户/备注/账户/金额") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true
            )
        }
        if (multiSelect) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已选择 ${selected.size} 笔", fontWeight = FontWeight.Bold)
                Row {
                    TextButton(onClick = { bulkCategory = true }) { Text("改分类") }
                    TextButton(onClick = { bulkNecessity = true }) { Text("改必要性") }
                    TextButton(onClick = { bulkDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { multiSelect = false; selected.clear() }) { Text("取消") }
                }
            }
        }

        if (calendarMode) {
            // ── 月历视图（REQ 流水§17-18）──
            CalendarView(month, monthTxs)
        } else {
            // ── 连续列表（REQ 流水§1-3/§15/§21）──
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                var lastDay: LocalDate? = null
                filtered.forEach { tx ->
                    val day = Instant.ofEpochMilli(tx.occurredAt).atZone(zone).toLocalDate()
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "sep-$day") {
                            Text(
                                if (day == LocalDate.now()) "今天" else "${day.monthValue}月${day.dayOfMonth}日",
                                Modifier.padding(top = 10.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item(key = tx.id) {
                        TransactionListRow(
                            tx = tx,
                            accountName = accountNameOf(tx.accountId),
                            category = categories.firstOrNull { it.name == tx.category },
                            multiSelect = multiSelect,
                            checked = tx.id in selected,
                            onToggle = { if (tx.id in selected) selected.remove(tx.id) else selected.add(tx.id) },
                            onEnterMulti = { if (!multiSelect) { multiSelect = true; selected.add(tx.id) } },
                            onClick = { if (multiSelect) { if (tx.id in selected) selected.remove(tx.id) else selected.add(tx.id) } else editingTx = tx }
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item { Text("没有符合条件的流水", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(editingTx) {
        editingTx?.let {
            onEditTransaction(it)
            editingTx = null
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(initial = month, onPick = { month = it; showMonthPicker = false }, onDismiss = { showMonthPicker = false })
    }
    if (showFilter) {
        FilterDialog(
            categories = categories,
            fTypes = fTypes, fCategory = fCategory, fNecessity = fNecessity, fChannel = fChannel,
            fMinCents = fMinCents, fMaxCents = fMaxCents,
            onApply = { t, c, n, ch, min, max ->
                fTypes = t; fCategory = c; fNecessity = n; fChannel = ch; fMinCents = min; fMaxCents = max
                showFilter = false
            },
            onClear = { fTypes = emptySet(); fCategory = null; fNecessity = null; fChannel = null; fMinCents = ""; fMaxCents = ""; showFilter = false },
            onDismiss = { showFilter = false }
        )
    }
    if (bulkCategory) {
        BulkCategoryDialog(categories = categories, onPick = { catName ->
            selected.forEach { model.setTransactionCategoryName(it, catName) }
            selected.clear(); multiSelect = false; bulkCategory = false
        }, onDismiss = { bulkCategory = false })
    }
    if (bulkNecessity) {
        AlertDialog(
            onDismissRequest = { bulkNecessity = false },
            title = { Text("批量改必要性") },
            text = {
                Column {
                    TextButton(onClick = {
                        selected.forEach { model.setTransactionNecessity(it, true) }
                        selected.clear(); multiSelect = false; bulkNecessity = false
                    }) { Text("必要") }
                    TextButton(onClick = {
                        selected.forEach { model.setTransactionNecessity(it, false) }
                        selected.clear(); multiSelect = false; bulkNecessity = false
                    }) { Text("非必要") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { bulkNecessity = false }) { Text("取消") } }
        )
    }
    if (bulkDelete) {
        AlertDialog(
            onDismissRequest = { bulkDelete = false },
            title = { Text("批量删除 ${selected.size} 笔？") },
            text = { Text("删除后不可撤销；由通知确认的流水会回到待确认箱重新处理。") },
            confirmButton = {
                TextButton(onClick = {
                    selected.forEach { model.deleteTransaction(it) }
                    selected.clear(); multiSelect = false; bulkDelete = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { bulkDelete = false }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionListRow(
    tx: TransactionEntity,
    accountName: String,
    category: CategoryEntity?,
    multiSelect: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onEnterMulti: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onEnterMulti).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (multiSelect) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
        // 左侧浅主题色圆角底块 + 深色线性分类图标（REQ 流水§22）
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                IconLibrary.byKey(category?.iconKey ?: "more-horiz"),
                contentDescription = tx.category,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tx.merchant ?: typeLabel(tx.type),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Text(
                listOfNotNull(
                    typeLabel(tx.type),
                    tx.category.ifEmpty { null },
                    if (tx.channel != null) "$tx.channel · $accountName" else accountName.ifEmpty { null },
                    if (tx.necessity == true) "必要" else if (tx.necessity == false) "非必要" else null,
                    formatTime(tx.occurredAt)
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${amountPrefixOf(tx.type)}${formatMoney(tx.amountCents)}",
            fontWeight = FontWeight.Bold,
            color = amountColorOf(tx.type),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** 月历：每日「收入−支出」净变化（REQ 流水§18）。 */
@Composable
private fun CalendarView(month: YearMonth, monthTxs: List<TransactionEntity>) {
    val zone = ZoneId.systemDefault()
    fun at(day: Int): Long = month.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
    val daysInMonth = month.lengthOfMonth()
    val firstDay = month.atDay(1).dayOfWeek.value % 7 // 周一=0
    val netByDay = monthTxs.groupBy {
        Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate().dayOfMonth
    }.mapValues { (_, txs) ->
        val income = txs.filter { it.type == "INCOME" }.sumOf { it.amountCents }
        val expense = txs.filter { it.type == "EXPENSE" || it.type == "FEE" }.sumOf { it.amountCents }
        income - expense
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                Text(w, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        repeat((firstDay + daysInMonth + 6) / 7) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { dow ->
                    val day = week * 7 + dow - firstDay + 1
                    Box(Modifier.weight(1f).height(56.dp).padding(2.dp)) {
                        if (day in 1..daysInMonth) {
                            val net = netByDay[day]
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$day", style = MaterialTheme.typography.labelSmall)
                                if (net != null && net != 0L) {
                                    Text(
                                        "${if (net > 0) "+" else "−"}${net / 100}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (net > 0) FlowGreen else FlowRed,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MonthPickerDialog(initial: YearMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by remember { mutableStateOf(initial.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择月份") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { year-- }) { Text("−") }
                    Text("$year 年", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { year++ }) { Text("＋") }
                }
                Column {
                    (1..12).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { m ->
                                FilterChip(
                                    selected = m == initial.monthValue && year == initial.year,
                                    onClick = { onPick(YearMonth.of(year, m)) },
                                    label = { Text("${m}月") },
                                    modifier = Modifier.weight(1f).padding(2.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FilterDialog(
    categories: List<CategoryEntity>,
    fTypes: Set<String>, fCategory: String?, fNecessity: Boolean?, fChannel: String?,
    fMinCents: String, fMaxCents: String,
    onApply: (Set<String>, String?, Boolean?, String?, String, String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var types by remember { mutableStateOf(fTypes) }
    var category by remember { mutableStateOf(fCategory) }
    var necessity by remember { mutableStateOf(fNecessity) }
    var channel by remember { mutableStateOf(fChannel) }
    var min by remember { mutableStateOf(fMinCents) }
    var max by remember { mutableStateOf(fMaxCents) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("筛选") },
        text = {
            Column {
                Text("交易类型", fontWeight = FontWeight.Medium)
                Row {
                    listOf("支出" to "EXPENSE", "收入" to "INCOME", "退款" to "REFUND", "转账还款" to "LOAN_PAYMENT").forEach { (label, t) ->
                        FilterChip(
                            selected = t in types,
                            onClick = { types = if (t in types) types - t else types + t },
                            label = { Text(label) },
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                }
                Text("分类", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    categories.filter { it.parentId == null }.forEach { c ->
                        FilterChip(selected = category == c.name, onClick = { category = if (category == c.name) null else c.name }, label = { Text(c.shortName) }, modifier = Modifier.padding(2.dp))
                    }
                }
                Text("必要性", fontWeight = FontWeight.Medium)
                Row {
                    FilterChip(selected = necessity == true, onClick = { necessity = if (necessity == true) null else true }, label = { Text("必要") }, modifier = Modifier.padding(2.dp))
                    FilterChip(selected = necessity == false, onClick = { necessity = if (necessity == false) null else false }, label = { Text("非必要") }, modifier = Modifier.padding(2.dp))
                }
                Text("支付渠道", fontWeight = FontWeight.Medium)
                Row {
                    listOf("微信支付", "支付宝", "银行短信").forEach { ch ->
                        FilterChip(selected = channel == ch, onClick = { channel = if (channel == ch) null else ch }, label = { Text(ch) }, modifier = Modifier.padding(2.dp))
                    }
                }
                Text("金额区间", fontWeight = FontWeight.Medium)
                Row {
                    OutlinedTextField(min, { min = it }, label = { Text("最低") }, modifier = Modifier.weight(1f).padding(2.dp), singleLine = true)
                    OutlinedTextField(max, { max = it }, label = { Text("最高") }, modifier = Modifier.weight(1f).padding(2.dp), singleLine = true)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onClear) { Text("清空") }
                TextButton(onClick = { onApply(types, category, necessity, channel, min, max) }) { Text("应用") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun BulkCategoryDialog(categories: List<CategoryEntity>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量改分类") },
        text = {
            Column {
                categories.filter { it.parentId != null && !it.isArchived }.groupBy { it.parentId }.forEach { (parentId, children) ->
                    val parent = categories.firstOrNull { it.id == parentId }
                    Text(parent?.name ?: "其他", fontWeight = FontWeight.Bold)
                    Row {
                        children.take(4).forEach { c ->
                            FilterChip(selected = false, onClick = { onPick(c.name) }, label = { Text(c.shortName) }, modifier = Modifier.padding(2.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
