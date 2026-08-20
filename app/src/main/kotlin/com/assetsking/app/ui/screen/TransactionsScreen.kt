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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import com.assetsking.database.MerchantEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.IconLibrary
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatMoneyCompact
import com.assetsking.ui.format.formatSignedMoney
import com.assetsking.ui.format.formatTime
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

/**
 * 流水页（REQ 流水列表与详情 §1-22）：月度摘要卡、连续列表倒序、月历净变化、
 * 搜索与多条件筛选、长按多选批量操作、点击编辑。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    state: LedgerUiState,
    categories: List<CategoryEntity>,
    merchants: List<MerchantEntity>,
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

    // 双视图（REQ 流水商户库分类库入口 §1）：流水记录 / 商户与分类库
    var viewMode by remember { mutableStateOf("list") }
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
            (fCategory == null || tx.category == fCategory) &&
            (fNecessity == null || tx.necessity == fNecessity) &&
            (fChannel == null || tx.channel == fChannel) &&
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
        if (viewMode == "library") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("商户与分类库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { viewMode = "list" }) { Text("返回流水") }
            }
            MerchantCategoryLibrary(
                merchants = merchants,
                categories = categories,
                viewModel = model
            )
            return@Column
        }

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
            Text("流水记录", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = if (showSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showFilter = true }) {
                Icon(
                    Icons.Filled.List,
                    contentDescription = "筛选",
                    tint = if (fTypes.isNotEmpty() || fCategory != null || fNecessity != null || fChannel != null || fMinCents.isNotBlank() || fMaxCents.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
            range = when {
                rangeMode == "LAST3" -> "LAST3"
                rangeMode == "CUSTOM" -> "CUSTOM"
                month == YearMonth.now().minusMonths(1) -> "LAST_MONTH"
                else -> "THIS_MONTH"
            },
            fTypes = fTypes, fCategory = fCategory, fNecessity = fNecessity, fChannel = fChannel,
            fMinCents = fMinCents, fMaxCents = fMaxCents,
            onApply = { pickedRange, t, c, n, ch, min, max ->
                when (pickedRange) {
                    "THIS_MONTH" -> { rangeMode = "MONTH"; month = YearMonth.now() }
                    "LAST_MONTH" -> { rangeMode = "MONTH"; month = YearMonth.now().minusMonths(1) }
                    "LAST3" -> rangeMode = "LAST3"
                    "CUSTOM" -> rangeMode = "CUSTOM"
                }
                dayFilter = null
                fTypes = t; fCategory = c; fNecessity = n; fChannel = ch; fMinCents = min; fMaxCents = max
                showFilter = false
            },
            onOpenLibrary = { showFilter = false; viewMode = "library" },
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
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onEnterMulti: () -> Unit,
    onClick: () -> Unit
) {
    val metadata = listOfNotNull(
        typeLabel(tx.type),
        tx.category.ifEmpty { null },
        if (tx.channel != null) "${tx.channel} · $accountName" else accountName.ifEmpty { null },
        if (tx.necessity == true) "必要" else if (tx.necessity == false) "非必要" else null
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
                    formatTime(tx.occurredAt),
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
                    Box(
                        Modifier.weight(1f).height(56.dp).padding(2.dp)
                            .clickable(enabled = day in 1..daysInMonth) { if (day in 1..daysInMonth) onDayClick(day) }
                    ) {
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
    range: String,
    fTypes: Set<String>, fCategory: String?, fNecessity: Boolean?, fChannel: String?,
    fMinCents: String, fMaxCents: String,
    onApply: (String, Set<String>, String?, Boolean?, String?, String, String) -> Unit,
    onOpenLibrary: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var pickedRange by remember { mutableStateOf(range) }
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
                TextButton(onClick = onOpenLibrary) { Text("打开商户与分类库") }
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
                TextButton(onClick = { onApply(pickedRange, types, category, necessity, channel, min, max) }) { Text("应用") }
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
                categories.filter { it.parentId != null && !it.isArchived && it.kind == "EXPENSE" }.groupBy { it.parentId }.forEach { (parentId, children) ->
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

/** 双视图第二页：商户与分类库集中管理（REQ 流水商户库分类库入口 §2-3）。 */
@Composable
private fun MerchantCategoryLibrary(
    merchants: List<MerchantEntity>,
    categories: List<CategoryEntity>,
    viewModel: LedgerViewModel
) {
    var merchantSearch by remember { mutableStateOf("") }
    var mergeSource by remember { mutableStateOf<MerchantEntity?>(null) }
    var showCategoryManage by remember { mutableStateOf(false) }
    var showIncomeCategoryManage by remember { mutableStateOf(false) }
    var newCategoryParentId by remember { mutableStateOf<String?>(null) }
    var showNewCategory by remember { mutableStateOf(false) }
    var newCategoryKind by remember { mutableStateOf("EXPENSE") }

    fun aliasesOf(json: String): List<String> = runCatching {
        org.json.JSONArray(json).let { arr -> (0 until arr.length()).map { arr.getString(it) } }
    }.getOrDefault(emptyList())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("交易对象库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text("标准商户/收入来源 + 原名别名 + 学习规则", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            merchantSearch, { merchantSearch = it },
            label = { Text("搜索商户/别名") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            singleLine = true
        )
        val shown = merchants.filter { m ->
            merchantSearch.isBlank() || m.id.contains(merchantSearch) || m.aliasesJson.contains(merchantSearch)
        }
        if (shown.isEmpty()) Text("暂无商户", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.forEach { m ->
            val aliases = aliasesOf(m.aliasesJson)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.id, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(
                            m.learnedType?.let { typeLabel(it) },
                            m.learnedAccountId?.let { id -> "账户" },
                            m.learnedCategory?.takeIf { it.isNotBlank() },
                            if (aliases.isNotEmpty()) "别名 ${aliases.joinToString("、")}" else null
                        ).joinToString(" · ").ifBlank { "未学习规则" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { mergeSource = m }) { Text("合并", style = MaterialTheme.typography.labelSmall) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("分类库", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Row {
            TextButton(onClick = { showCategoryManage = true }) { Text("管理消费分类") }
            TextButton(onClick = { showIncomeCategoryManage = true }) { Text("管理收入分类") }
        }
    }

    // 合并对象（REQ 商户库§8）：选目标，原名进别名、流水与学习规则迁移
    mergeSource?.let { source ->
        val targets = merchants.filter { it.id != source.id }
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("把「${source.id}」合并到") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (targets.isEmpty()) Text("没有其他商户", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    targets.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable {
                            viewModel.mergeMerchants(t.id, listOf(source.id))
                            mergeSource = null
                        }.padding(8.dp)) { Text(t.id) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeSource = null }) { Text("取消") } }
        )
    }

    if (showCategoryManage) {
        CategoryManageDialog(
            categories = categories,
            catKind = "EXPENSE",
            onDismiss = { showCategoryManage = false },
            onAddChild = { parentId -> newCategoryParentId = parentId; newCategoryKind = "EXPENSE"; showNewCategory = true; showCategoryManage = false },
            viewModel = viewModel
        )
    }
    if (showIncomeCategoryManage) {
        CategoryManageDialog(
            categories = categories,
            catKind = "INCOME",
            onDismiss = { showIncomeCategoryManage = false },
            onAddChild = { parentId -> newCategoryParentId = parentId; newCategoryKind = "INCOME"; showNewCategory = true; showIncomeCategoryManage = false },
            viewModel = viewModel
        )
    }
    if (showNewCategory) {
        val parents = categories.filter { it.parentId == null && !it.isArchived && it.kind == newCategoryKind }
        NewCategoryDialog(
            parentId = newCategoryParentId,
            parents = parents,
            catKind = newCategoryKind,
            onDismiss = { showNewCategory = false },
            onCreate = { name, shortName, parentId, iconKey, defaultNecessary ->
                viewModel.addCategoryEntity(name, shortName, parentId, iconKey, defaultNecessary, newCategoryKind)
                showNewCategory = false
            }
        )
    }
}
