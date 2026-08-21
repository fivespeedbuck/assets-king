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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.IconLibrary
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatMoneyCompact
import com.assetsking.ui.format.formatDailyNetChange
import com.assetsking.ui.format.formatClockTime
import com.assetsking.ui.layout.calendarCellIndex
import com.assetsking.ui.format.formatSignedMoney
import com.assetsking.ui.theme.ExpenseRed
import com.assetsking.ui.theme.IncomeGreen
import com.assetsking.ui.theme.TextSecondaryLight
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val FlowGreen = IncomeGreen
private val FlowRed = ExpenseRed
private val FlowGray = TextSecondaryLight

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

private fun dayLabel(day: LocalDate): String = when {
    day == LocalDate.now() -> "今天"
    day == LocalDate.now().minusDays(1) -> "昨天"
    else -> "${day.monthValue}月${day.dayOfMonth}日"
}

internal fun matchesCategoryFilter(
    transactionCategory: String,
    filterCategory: String?,
    categories: List<CategoryEntity>
): Boolean {
    if (filterCategory == null || transactionCategory == filterCategory) return true
    val parent = categories.firstOrNull {
        it.name == filterCategory && it.parentId == null && !it.isArchived
    } ?: return false
    return categories.firstOrNull { it.name == transactionCategory && !it.isArchived }?.parentId == parent.id
}

/**
 * 流水页（REQ 流水列表与详情 §1-22）：月度摘要卡、连续列表倒序、月历净变化、
 * 搜索与多条件筛选、长按多选批量操作、点击编辑。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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

    // 时间范围（REQ 流水§1）：MONTH=按月 / LAST3=近三月 / CUSTOM=自定义起止
    var rangeMode by remember { mutableStateOf("MONTH") }
    var customStart by remember { mutableStateOf<LocalDate?>(null) }
    var customEnd by remember { mutableStateOf<LocalDate?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    // 月历点击当日（REQ 流水§18）：选中后列表只看当日
    var dayFilter by remember { mutableStateOf<LocalDate?>(null) }

    // 筛选条件（REQ 流水§11 多条件组合）
    var fTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fCategory by remember { mutableStateOf<String?>(null) }
    var fNecessity by remember { mutableStateOf<Boolean?>(null) }
    var fChannel by remember { mutableStateOf<String?>(null) }
    var fAccountId by remember { mutableStateOf<String?>(null) }
    var fMerchant by remember { mutableStateOf("") }
    var fMinCents by remember { mutableStateOf("") }
    var fMaxCents by remember { mutableStateOf("") }

    // 统计页下钻（REQ 统计§3/§7/§20）：一次性带入月份+分类筛选，随后通知父级清空
    androidx.compose.runtime.LaunchedEffect(initialFilterMonth, initialFilterCategory) {
        if (initialFilterMonth != null) {
            month = initialFilterMonth
            rangeMode = "MONTH"
            dayFilter = null
            fCategory = initialFilterCategory
        }
        onDrillConsumed()
    }

    val zone = ZoneId.systemDefault()
    val (rangeStart, rangeEnd) = when (rangeMode) {
        "CUSTOM" -> {
            val s = customStart ?: month.atDay(1)
            val e = customEnd ?: month.atEndOfMonth()
            s.atStartOfDay(zone).toInstant().toEpochMilli() to e.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        }
        "LAST3" -> {
            month.minusMonths(2).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        }
        else -> month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

    val rangeTxs = state.transactions.filter { it.occurredAt in rangeStart..rangeEnd }
    // 审核 BUG-3 修复：退款冲减需校验原消费在本时间范围内，否则跨月退款把本范围支出冲成虚低。
    val rangeTxIds = rangeTxs.mapTo(HashSet()) { it.id }
    val refundOffset = rangeTxs.filter { it.type == "REFUND" && it.refundOfId != null && it.refundOfId in rangeTxIds }.sumOf { it.amountCents }
    val reimbOffset = rangeTxs.filter { it.type == "EXPENSE" }.sumOf { it.reimbursedCents }
    val monthIncome = rangeTxs.filter { it.type == "INCOME" }.sumOf { it.amountCents }
    val monthExpense = (rangeTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }.sumOf { it.amountCents } - refundOffset - reimbOffset).coerceAtLeast(0L)
    val monthNet = monthIncome - monthExpense

    // 筛选 + 搜索（REQ 流水§11-12）
    val accountNameOf = { id: String -> state.accounts.firstOrNull { it.id == id }?.name ?: "" }
    val minCents = fMinCents.toDoubleOrNull()?.times(100)?.toLong()
    val maxCents = fMaxCents.toDoubleOrNull()?.times(100)?.toLong()
    val filtered = rangeTxs.filter { tx ->
        (fTypes.isEmpty() || tx.type in fTypes) &&
            matchesCategoryFilter(tx.category, fCategory, categories) &&
            (fNecessity == null || tx.necessity == fNecessity) &&
            (fChannel == null || tx.channel == fChannel) &&
            (fAccountId == null || tx.accountId == fAccountId) &&
            (fMerchant.isBlank() || tx.merchant?.contains(fMerchant, ignoreCase = true) == true) &&
            (minCents == null || tx.amountCents >= minCents) &&
            (maxCents == null || tx.amountCents <= maxCents) &&
            (dayFilter == null || Instant.ofEpochMilli(tx.occurredAt).atZone(zone).toLocalDate() == dayFilter) &&
            (searchQuery.isBlank() ||
                (tx.merchant?.contains(searchQuery) == true) ||
                (tx.note?.contains(searchQuery) == true) ||
                accountNameOf(tx.accountId).contains(searchQuery) ||
                formatMoney(tx.amountCents).contains(searchQuery))
    }.sortedByDescending { it.occurredAt }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ── 月度摘要卡（REQ 流水§16/§19）：白卡、紧凑层级、与效果图一致 ──
        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            contentPadding = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上月")
                    }
                    Text(
                        "${month.year}年${month.monthValue}月",
                        Modifier.clickable { showMonthPicker = true }.padding(horizontal = 4.dp),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下月")
                    }
                }
                IconButton(onClick = { calendarMode = !calendarMode }) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = "月历",
                        tint = if (calendarMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("收入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMoneyCompact(monthIncome), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FlowGreen)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("支出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMoneyCompact(monthExpense), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FlowRed)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("结余", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (monthNet >= 0) formatMoneyCompact(monthNet) else "−${formatMoneyCompact(-monthNet)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (monthNet >= 0) FlowGreen else FlowRed
                    )
                }
            }
        }

        // ── 页面标题与操作：搜索/筛选保留原入口 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("流水记录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = if (showSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showFilter = true }) {
                Icon(
                    Icons.Filled.List,
                    contentDescription = "筛选",
                    tint = if (fTypes.isNotEmpty() || fCategory != null || fNecessity != null || fChannel != null || fAccountId != null || fMerchant.isNotBlank() || fMinCents.isNotBlank() || fMaxCents.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 时间范围和商户/分类库入口收进筛选面板，默认页面保持效果图的简洁层级。
        if (dayFilter != null) {
            FilterChip(
                selected = true,
                onClick = { dayFilter = null },
                label = { Text("当日 ${dayFilter!!.monthValue}月${dayFilter!!.dayOfMonth}日 ✕") },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (rangeMode == "CUSTOM") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showStartPicker = true }) { Text("起 ${customStart ?: month.atDay(1)}", style = MaterialTheme.typography.labelMedium) }
                Text("至", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { showEndPicker = true }) { Text("止 ${customEnd ?: month.atEndOfMonth()}", style = MaterialTheme.typography.labelMedium) }
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
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选择 ${selected.size} 笔", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { multiSelect = false; selected.clear() }) { Text("取消") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { bulkCategory = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("改分类", maxLines = 1) }
                    OutlinedButton(
                        onClick = { bulkNecessity = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("改必要性", maxLines = 1) }
                    OutlinedButton(
                        onClick = { bulkDelete = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                }
            }
        }

        if (calendarMode) {
            // ── 月历视图（REQ 流水§17-18）：点当日进当日流水 ──
            CalendarView(month, rangeTxs.filter { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate().let { d -> d.year == month.year && d.monthValue == month.monthValue } }) { day ->
                dayFilter = month.atDay(day)
                calendarMode = false
            }
        } else {
            // ── 连续列表（REQ 流水§1-3/§15/§21）──
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text("没有符合条件的流水", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    filtered.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }.forEach { (day, dayTxs) ->
                        item(key = "day-$day") {
                            Column {
                                Text(
                                    dayLabel(day),
                                    Modifier.padding(start = 2.dp, bottom = 6.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = Modifier
                                ) {
                                    dayTxs.forEachIndexed { index, tx ->
                                        TransactionListRow(
                                            tx = tx,
                                            accountName = accountNameOf(tx.accountId),
                                            category = categories.firstOrNull { it.name == tx.category },
                                            multiSelect = multiSelect,
                                            checked = tx.id in selected,
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            onToggle = { if (tx.id in selected) selected.remove(tx.id) else selected.add(tx.id) },
                                            onEnterMulti = { if (!multiSelect) { multiSelect = true; selected.add(tx.id) } },
                                            onClick = { if (multiSelect) { if (tx.id in selected) selected.remove(tx.id) else selected.add(tx.id) } else editingTx = tx }
                                        )
                                        if (index < dayTxs.lastIndex) {
                                            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
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
    if (showStartPicker) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (customStart ?: month.atDay(1)).atStartOfDay(zone).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { customStart = Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    showStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text("取消") } }
        ) { DatePicker(state = st) }
    }
    if (showEndPicker) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (customEnd ?: month.atEndOfMonth()).atStartOfDay(zone).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { customEnd = Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    showEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text("取消") } }
        ) { DatePicker(state = st) }
    }
    if (showFilter) {
        FilterDialog(
            categories = categories,
            accounts = state.accounts,
            channels = state.transactions.mapNotNull { it.channel?.takeIf(String::isNotBlank) }.distinct(),
            defaultMonth = month,
            customStart = customStart,
            customEnd = customEnd,
            range = when {
                rangeMode == "LAST3" -> "LAST3"
                rangeMode == "CUSTOM" -> "CUSTOM"
                month == YearMonth.now().minusMonths(1) -> "LAST_MONTH"
                else -> "THIS_MONTH"
            },
            fTypes = fTypes, fCategory = fCategory, fNecessity = fNecessity, fChannel = fChannel,
            fAccountId = fAccountId, fMerchant = fMerchant,
            fMinCents = fMinCents, fMaxCents = fMaxCents,
            onApply = { pickedRange, t, c, n, ch, accountId, merchant, min, max, pickedStart, pickedEnd ->
                when (pickedRange) {
                    "THIS_MONTH" -> { rangeMode = "MONTH"; month = YearMonth.now() }
                    "LAST_MONTH" -> { rangeMode = "MONTH"; month = YearMonth.now().minusMonths(1) }
                    "LAST3" -> rangeMode = "LAST3"
                    "CUSTOM" -> rangeMode = "CUSTOM"
                }
                customStart = pickedStart
                customEnd = pickedEnd
                dayFilter = null
                fTypes = t; fCategory = c; fNecessity = n; fChannel = ch; fAccountId = accountId; fMerchant = merchant; fMinCents = min; fMaxCents = max
                showFilter = false
            },
            onClear = { fTypes = emptySet(); fCategory = null; fNecessity = null; fChannel = null; fAccountId = null; fMerchant = ""; fMinCents = ""; fMaxCents = ""; showFilter = false },
            onDismiss = { showFilter = false }
        )
    }
    if (bulkCategory) {
        BulkCategoryDialog(
            categories = categories,
            onPick = { catName ->
                selected.forEach { model.setTransactionCategoryName(it, catName) }
                selected.clear(); multiSelect = false; bulkCategory = false
            },
            onAddCategory = model::addCategoryEntity,
            onDismiss = { bulkCategory = false }
        )
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
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onEnterMulti: () -> Unit,
    onClick: () -> Unit
) {
    val metadata = listOfNotNull(
        tx.category.ifEmpty { null },
        if (tx.channel != null) "${tx.channel} · $accountName" else accountName.ifEmpty { null }
    ).joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onEnterMulti)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (multiSelect) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
        // 左侧浅主题色圆角底块 + 深色线性分类图标（REQ 流水§22）
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
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
            Text(
                tx.merchant ?: typeLabel(tx.type),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metadata,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatClockTime(tx.occurredAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatSignedMoney(
                tx.amountCents,
                positive = when (tx.type) {
                    "EXPENSE", "FEE" -> false
                    "INCOME", "REFUND", "REIMBURSEMENT" -> true
                    else -> null
                }
            ),
            fontWeight = FontWeight.Bold,
            color = amountColorOf(tx.type),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1
        )
    }
}

/** 月历：每日「收入−支出」净变化（REQ 流水§18）；点当日进当日流水。 */
@Composable
private fun CalendarView(month: YearMonth, monthTxs: List<TransactionEntity>, onDayClick: (Int) -> Unit) {
    val zone = ZoneId.systemDefault()
    fun at(day: Int): Long = month.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
    val daysInMonth = month.lengthOfMonth()
    val firstDay = calendarCellIndex(month, 1)
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
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .padding(2.dp)
                            .clickable(enabled = day in 1..daysInMonth) {
                                if (day in 1..daysInMonth) onDayClick(day)
                            },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (day in 1..daysInMonth) {
                            val net = netByDay[day]
                            Column(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "$day",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (net != null && net != 0L) {
                                    Text(
                                        formatDailyNetChange(net),
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDialog(
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    channels: List<String>,
    defaultMonth: YearMonth,
    customStart: LocalDate?,
    customEnd: LocalDate?,
    range: String,
    fTypes: Set<String>, fCategory: String?, fNecessity: Boolean?, fChannel: String?,
    fAccountId: String?, fMerchant: String,
    fMinCents: String, fMaxCents: String,
    onApply: (String, Set<String>, String?, Boolean?, String?, String?, String, String, String, LocalDate?, LocalDate?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var pickedRange by remember { mutableStateOf(range) }
    var types by remember { mutableStateOf(fTypes) }
    var category by remember { mutableStateOf(fCategory) }
    var necessity by remember { mutableStateOf(fNecessity) }
    var channel by remember { mutableStateOf(fChannel) }
    var accountId by remember { mutableStateOf(fAccountId) }
    var merchant by remember { mutableStateOf(fMerchant) }
    var min by remember { mutableStateOf(fMinCents) }
    var max by remember { mutableStateOf(fMaxCents) }
    var pickedStart by remember { mutableStateOf(customStart ?: defaultMonth.atDay(1)) }
    var pickedEnd by remember { mutableStateOf(customEnd ?: defaultMonth.atEndOfMonth()) }
    var showCustomStartPicker by remember { mutableStateOf(false) }
    var showCustomEndPicker by remember { mutableStateOf(false) }
    val customRangeValid = pickedRange != "CUSTOM" || !pickedStart.isAfter(pickedEnd)
    Sheet(title = "筛选", onDismiss = onDismiss) {
                Text("数据范围", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "本月" to "THIS_MONTH",
                        "上月" to "LAST_MONTH",
                        "近三月" to "LAST3",
                        "自定义" to "CUSTOM"
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = pickedRange == value,
                            onClick = { pickedRange = value },
                            label = { Text(label) },
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                }
                if (pickedRange == "CUSTOM") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showCustomStartPicker = true }, modifier = Modifier.weight(1f)) {
                            Text("开始 $pickedStart", maxLines = 1)
                        }
                        OutlinedButton(onClick = { showCustomEndPicker = true }, modifier = Modifier.weight(1f)) {
                            Text("结束 $pickedEnd", maxLines = 1)
                        }
                    }
                    if (!customRangeValid) {
                        Text("开始日期不能晚于结束日期", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("交易类型", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "支出" to "EXPENSE", "收入" to "INCOME", "退款" to "REFUND",
                        "手续费" to "FEE", "报销到账" to "REIMBURSEMENT",
                        "借款到账" to "LOAN_DISBURSEMENT", "贷款还款" to "LOAN_PAYMENT", "提前还款" to "LOAN_PREPAYMENT"
                    ).forEach { (label, t) ->
                        FilterChip(
                            selected = t in types,
                            onClick = { types = if (t in types) types - t else types + t },
                            label = { Text(label) },
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                }
                Text("分类", fontWeight = FontWeight.Medium)
                TextButton(onClick = { category = null }) { Text(if (category == null) "不限分类 ✓" else "不限分类") }
                CategoryGrid(
                    parents = categories.filter { it.parentId == null && !it.isArchived && it.kind == "EXPENSE" },
                    childrenOf = { parentId -> categories.filter { it.parentId == parentId && !it.isArchived && it.kind == "EXPENSE" } },
                    selectedCategoryId = categories.firstOrNull { it.name == category && !it.isArchived }?.id,
                    onSelect = { category = it.name },
                    onAddChild = {},
                    onReorder = {},
                    showAddChild = false,
                    selectParentOnExpand = true
                )
                Text("必要性", fontWeight = FontWeight.Medium)
                Row {
                    FilterChip(selected = necessity == true, onClick = { necessity = if (necessity == true) null else true }, label = { Text("必要") }, modifier = Modifier.padding(2.dp))
                    FilterChip(selected = necessity == false, onClick = { necessity = if (necessity == false) null else false }, label = { Text("非必要") }, modifier = Modifier.padding(2.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SelectDropdownField(
                        label = "支付渠道",
                        selectedLabel = channel ?: "不限渠道",
                        options = listOf("" to "不限渠道") + channels.map { it to it },
                        onSelected = { channel = it.ifBlank { null } },
                        modifier = Modifier.weight(1f)
                    )
                    SelectDropdownField(
                        label = "资金账户",
                        selectedLabel = accounts.firstOrNull { it.id == accountId }?.name ?: "不限账户",
                        options = listOf("" to "不限账户") + accounts.filter { !it.archived }.map { it.id to it.name },
                        onSelected = { accountId = it.ifBlank { null } },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户/来源包含") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("金额区间", fontWeight = FontWeight.Medium)
                Row {
                    OutlinedTextField(min, { min = it }, label = { Text("最低") }, modifier = Modifier.weight(1f).padding(2.dp), singleLine = true)
                    OutlinedTextField(max, { max = it }, label = { Text("最高") }, modifier = Modifier.weight(1f).padding(2.dp), singleLine = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("清空") }
                    Button(
                        onClick = { onApply(pickedRange, types, category, necessity, channel, accountId, merchant.trim(), min, max, pickedStart, pickedEnd) },
                        enabled = customRangeValid,
                        modifier = Modifier.weight(1f)
                    ) { Text("应用") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
    if (showCustomStartPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickedStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showCustomStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        pickedStart = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showCustomStartPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCustomStartPicker = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState) }
    }
    if (showCustomEndPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickedEnd.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showCustomEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        pickedEnd = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showCustomEndPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCustomEndPicker = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun BulkCategoryDialog(
    categories: List<CategoryEntity>,
    onPick: (String) -> Unit,
    onAddCategory: (String, String, String?, String, Boolean?, String) -> Unit,
    onDismiss: () -> Unit
) {
    val parents = categories.filter { it.parentId == null && !it.isArchived && it.kind == "EXPENSE" }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var newCategoryParentId by remember { mutableStateOf<String?>(null) }
    var showNewCategory by remember { mutableStateOf(false) }
    var pendingCategoryName by remember { mutableStateOf<String?>(null) }
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId && !it.isArchived }
    androidx.compose.runtime.LaunchedEffect(categories, pendingCategoryName) {
        val name = pendingCategoryName ?: return@LaunchedEffect
        categories.firstOrNull { it.name == name && it.kind == "EXPENSE" && !it.isArchived }?.let {
            selectedCategoryId = it.id
            pendingCategoryName = null
        }
    }
    Sheet(title = "批量改分类", onDismiss = onDismiss) {
        Text(
            "先选择分类，确认后才会修改已勾选的流水",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        CategoryGrid(
            parents = parents,
            childrenOf = { parentId ->
                categories.filter { it.parentId == parentId && !it.isArchived && it.kind == "EXPENSE" }
            },
            selectedCategoryId = selectedCategoryId,
            onSelect = { selectedCategoryId = it.id },
            onClearSelection = { selectedCategoryId = null },
            onAddChild = { parentId ->
                newCategoryParentId = parentId
                showNewCategory = true
            },
            onReorder = {},
            showAddChild = true
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = { selectedCategory?.let { onPick(it.name) } },
                enabled = selectedCategory != null,
                modifier = Modifier.weight(1f)
            ) { Text("确认修改") }
        }
    }
    if (showNewCategory) {
        NewCategoryDialog(
            parentId = newCategoryParentId,
            parents = parents,
            catKind = "EXPENSE",
            onDismiss = { showNewCategory = false },
            onCreate = { name, shortName, parentId, iconKey, defaultNecessary ->
                onAddCategory(name, shortName, parentId, iconKey, defaultNecessary, "EXPENSE")
                pendingCategoryName = name.trim()
                showNewCategory = false
            }
        )
    }
}
