package com.assetsking.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.database.AccountEntity
import com.assetsking.database.RawNotificationEntity
import com.assetsking.ledger.BalanceMath
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.categoryLabelOrName
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.usecase.AccountInference
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF66BB6A)
private val Red = Color(0xFFE57373)
private val NeutralGray = Color(0xFF757575)

private data class PendingBoxTypeOption(val type: TransactionType, val label: String)

private val typeOptions = listOf(
    PendingBoxTypeOption(TransactionType.EXPENSE, "支出"),
    PendingBoxTypeOption(TransactionType.INCOME, "收入"),
    PendingBoxTypeOption(TransactionType.REFUND, "退款"),
    PendingBoxTypeOption(TransactionType.LOAN_PAYMENT, "贷款还款"),
    PendingBoxTypeOption(TransactionType.REIMBURSEMENT, "报销到账")
)

private fun amountColor(type: TransactionType): Color = when (type) {
    TransactionType.EXPENSE -> Red
    TransactionType.INCOME, TransactionType.REFUND, TransactionType.REIMBURSEMENT -> Green
    else -> NeutralGray
}

private fun amountPrefix(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "−¥"
    TransactionType.INCOME, TransactionType.REFUND, TransactionType.REIMBURSEMENT -> "+¥"
    else -> "¥"
}

/**
 * 待确认箱全屏页（REQ 待确认箱 UI §1-13）：卡片列表倒序、日期分隔、批量确认、
 * 左滑删除二次确认、证据折叠与拆分、余额校验预览、空状态。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PendingBoxScreen(
    items: List<PendingItem>,
    ignoredItems: List<RawNotificationEntity>,
    accounts: List<AccountEntity>,
    merchantLastAccount: Map<String, String>,
    customCategoryNames: List<String>,
    viewModel: LedgerViewModel,
    lastReceivedAt: Long,
    onBack: () -> Unit
) {
    val sorted = remember(items) { items.sortedByDescending { it.notification.postedAt } }
    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<PendingItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (multiSelect) "已选择 ${selected.size} 笔" else "待确认 ${sorted.size} 笔") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (multiSelect) { multiSelect = false; selected.clear() } else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (multiSelect) {
                        TextButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                val count = selected.size
                                selected.toList().forEach { id ->
                                    val item = sorted.firstOrNull { it.notification.id == id } ?: return@forEach
                                    confirmItem(viewModel, item, accounts, merchantLastAccount)
                                }
                                selected.clear()
                                multiSelect = false
                                scope.launch { snackbar.showSnackbar("已入账 $count 笔") }
                            }
                        ) { Text("确认入账") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🏦", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("待确认已清空", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (lastReceivedAt > 0) "最近入库 ${formatTime(lastReceivedAt)}" else "等待第一笔账目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                var lastDay: String? = null
                sorted.forEach { item ->
                    val day = dayLabel(item.notification.postedAt)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "sep-$day") {
                            Text(
                                day,
                                Modifier.padding(top = 8.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item(key = item.notification.id) {
                        val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) deleteTarget = item
                            false // 只弹二次确认，不允许一滑到底直接删（REQ 待确认箱§11）
                        })
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    Modifier.fillMaxSize().background(Red).padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) { Text("删除", color = Color.White, fontWeight = FontWeight.Bold) }
                            }
                        ) {
                            PendingBoxCard(
                                item = item,
                                accounts = accounts,
                                merchantLastAccount = merchantLastAccount,
                                customCategoryNames = customCategoryNames,
                                ignoredItems = ignoredItems,
                                multiSelect = multiSelect,
                                checked = item.notification.id in selected,
                                onToggleSelect = {
                                    if (item.notification.id in selected) selected.remove(item.notification.id)
                                    else selected.add(item.notification.id)
                                },
                                onEnterMultiSelect = {
                                    if (!multiSelect) {
                                        multiSelect = true
                                        selected.add(item.notification.id)
                                    }
                                },
                                viewModel = viewModel,
                                onConfirmed = { scope.launch { snackbar.showSnackbar("已入账") } }
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("永久删除？") },
            text = { Text("删除后这笔候选及其证据不再出现在待确认箱（已保存去重指纹，补扫不会再次出现）。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.ignoreNotification(target.notification.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingBoxCard(
    item: PendingItem,
    accounts: List<AccountEntity>,
    merchantLastAccount: Map<String, String>,
    customCategoryNames: List<String>,
    ignoredItems: List<RawNotificationEntity>,
    multiSelect: Boolean,
    checked: Boolean,
    onToggleSelect: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    viewModel: LedgerViewModel,
    onConfirmed: () -> Unit
) {
    if (accounts.isEmpty()) return
    val parsed = item.parsed
    val amountCents = parsed.amountCents ?: 0L
    val merchant = parsed.merchant
    var expanded by remember { mutableStateOf(false) }
    var selectedType by remember {
        mutableStateOf(
            when {
                parsed.isExpense == false -> TransactionType.INCOME
                parsed.isExpense == true -> TransactionType.EXPENSE
                else -> TransactionType.EXPENSE
            }
        )
    }
    var selectedCategory by remember {
        mutableStateOf(viewModel.categorize(merchant, null).name)
    }
    val inferredId = AccountInference.infer(
        bankMatchedAccountId = parsed.bankHint?.let { hint ->
            accounts.firstOrNull { it.name.contains(hint) || hint.contains(it.name) }?.id
        },
        merchantHistoryAccountId = merchant?.let { merchantLastAccount[it] },
        sourcePackage = item.notification.packageName,
        candidates = accounts.map { AccountInference.Candidate(it.id, it.name) }
    )
    var selectedAccountId by remember { mutableStateOf(inferredId ?: accounts.firstOrNull()?.id.orEmpty()) }
    val account = accounts.firstOrNull { it.id == selectedAccountId }

    // 余额校验预览（REQ 归并§6 / 对账§4）：账面余额 + 本次影响 vs 银行余额
    val delta = when {
        selectedType == TransactionType.EXPENSE -> -amountCents
        selectedType == TransactionType.INCOME || selectedType == TransactionType.REFUND || selectedType == TransactionType.REIMBURSEMENT -> amountCents
        else -> null // 转账/贷款还款不做该预览（数据不足）
    }
    val tailMismatch = parsed.cardTail != null && account != null && account.cardTail != null && account.cardTail != parsed.cardTail
    val balanceCheck = if (parsed.balanceCents != null && parsed.cardTail != null && account?.type == "ASSET" && delta != null && !tailMismatch) {
        BalanceMath.checkBalance(account.balanceCents, delta, parsed.balanceCents)
    } else null

    val isCompleting = System.currentTimeMillis() - item.notification.receivedAt < 10_000
    val isRescanned = item.notification.sourceLabel?.startsWith("短信补回") == true
    // 完整性：金额/方向有 + 标准商户有 + 余额校验无冲突（REQ 待确认箱§4）
    val complete = merchant != null && balanceCheck?.matches != false && !tailMismatch

    GlassCard(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (multiSelect) onToggleSelect() else expanded = !expanded },
            onLongClick = onEnterMultiSelect
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (multiSelect) {
                    Checkbox(checked = checked, onCheckedChange = { onToggleSelect() }, enabled = complete)
                }
                Text(
                    "${amountPrefix(selectedType)}${formatMoney(amountCents)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor(selectedType)
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(
                    merchant ?: "待补全商户",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (merchant == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (complete && !multiSelect) {
                    Button(onClick = {
                        confirmItem(viewModel, item, accounts, merchantLastAccount)
                        onConfirmed()
                    }) { Text("确认") }
                } else if (!multiSelect) {
                    OutlinedButton(onClick = { expanded = true }) { Text("去补全") }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("· ${categoryLabelOrName(selectedCategory, customCategoryNames)}", style = MaterialTheme.typography.labelSmall)
                Text("· ${account?.name ?: "未选账户"}", style = MaterialTheme.typography.labelSmall)
                Text("· ${formatTime(item.notification.postedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val badges = buildList {
                if (isCompleting) add("正在补全")
                if (isRescanned) add("由短信补回")
                if (balanceCheck != null) {
                    add(if (balanceCheck.matches) "余额一致" else "余额差 ${formatMoney(kotlin.math.abs(balanceCheck.diffCents))}")
                }
                if (tailMismatch) add("银行尾号与账户不符")
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    badges.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (badges.any { it != "正在补全" && it != "余额一致" && it != "由短信补回" }) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // 类型
                Text("类型", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    items = typeOptions,
                    selected = typeOptions.first { it.type == selectedType },
                    onSelected = { selectedType = it.type },
                    label = { it.label },
                    id = { it.type.name }
                )

                Spacer(Modifier.height(8.dp))
                Text("入账账户", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    items = accounts,
                    selected = accounts.firstOrNull { it.id == selectedAccountId } ?: accounts.first(),
                    onSelected = { selectedAccountId = it.id },
                    label = { it.name },
                    id = { it.id }
                )

                Spacer(Modifier.height(8.dp))
                Text("分类", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    items = com.assetsking.model.TransactionCategory.entries.map { it.name } + customCategoryNames,
                    selected = selectedCategory,
                    onSelected = { selectedCategory = it },
                    label = { categoryLabelOrName(it, customCategoryNames) }
                )

                // 余额校验预览（REQ 归并§6）
                if (balanceCheck != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("余额校验预览", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("账面余额 ${formatMoney(account?.balanceCents ?: 0)}", style = MaterialTheme.typography.labelSmall)
                    Text("本次影响 ${if (delta!! >= 0) "+" else ""}${formatMoney(delta)}", style = MaterialTheme.typography.labelSmall)
                    Text("应有余额 ${formatMoney(balanceCheck.expectedCents)}", style = MaterialTheme.typography.labelSmall)
                    Text("银行余额 ${formatMoney(balanceCheck.bankCents ?: 0)}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (balanceCheck.matches) "✓ 一致" else "✗ 不一致，差额 ${formatMoney(balanceCheck.diffCents)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (balanceCheck.matches) Green else Red,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 合并证据 + 拆分（REQ 归并§17-18）
                EvidenceSection(item, ignoredItems, viewModel)

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { /* 删除走左滑 */ },
                        modifier = Modifier.weight(1f)
                    ) { Text("左滑删除") }
                    Button(
                        onClick = {
                            confirmItem(viewModel, item, accounts, merchantLastAccount)
                            onConfirmed()
                        },
                        enabled = complete,
                        modifier = Modifier.weight(1f)
                    ) { Text("确认入账") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EvidenceSection(
    item: PendingItem,
    ignoredItems: List<RawNotificationEntity>,
    viewModel: LedgerViewModel
) {
    val own = item.notification
    // 备注带 kept=<本卡id> 的已忽略证据 = 同笔交易的合并证据
    val merged = ignoredItems.filter { it.processingNote?.contains("kept=${own.id}") == true }
    var showEvidence by remember { mutableStateOf(false) }

    Spacer(Modifier.height(8.dp))
    Text(
        "已合并 ${merged.size + 1} 条消息",
        Modifier.combinedClickable(onClick = { showEvidence = !showEvidence }),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
    if (showEvidence) {
        Spacer(Modifier.height(4.dp))
        evidenceRow(own, splitEnabled = false, viewModel)
        merged.forEach { evidenceRow(it, splitEnabled = true, viewModel) }
    }
}

@Composable
private fun evidenceRow(notification: RawNotificationEntity, splitEnabled: Boolean, viewModel: LedgerViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "${notification.sourceLabel ?: notification.packageName} · ${formatTime(notification.postedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            notification.content.ifBlank { notification.title.orEmpty() },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (splitEnabled) {
            TextButton(onClick = { viewModel.splitNotification(notification.id) }) {
                Text("拆分为独立待确认", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 单笔确认：用卡片当前预填值（调用方状态与卡片同步） */
private fun confirmItem(
    viewModel: LedgerViewModel,
    item: PendingItem,
    accounts: List<AccountEntity>,
    merchantLastAccount: Map<String, String>
) {
    // 批量/摘要层确认用默认预填：金额、方向、推断账户、建议分类、商户
    val parsed = item.parsed
    val amount = parsed.amountCents ?: return
    val type = when {
        parsed.isExpense == false -> TransactionType.INCOME
        else -> TransactionType.EXPENSE
    }
    val accountId = AccountInference.infer(
        bankMatchedAccountId = parsed.bankHint?.let { hint ->
            accounts.firstOrNull { it.name.contains(hint) || hint.contains(it.name) }?.id
        },
        merchantHistoryAccountId = parsed.merchant?.let { merchantLastAccount[it] },
        sourcePackage = item.notification.packageName,
        candidates = accounts.map { AccountInference.Candidate(it.id, it.name) }
    ) ?: accounts.firstOrNull()?.id ?: return
    viewModel.confirmNotification(
        notificationId = item.notification.id,
        accountId = accountId,
        amountCents = amount,
        type = type,
        category = viewModel.categorize(parsed.merchant, null).name,
        merchant = parsed.merchant,
        note = item.notification.title,
        bankBalanceCents = parsed.balanceCents,
        bankCardTail = parsed.cardTail
    )
}

/** 今天 / 昨天 / M月d日 的日期分隔（REQ 待确认箱§9） */
private fun dayLabel(at: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.clone() as Calendar
    cal.time = Date(at)
    today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0)
    val that = cal.clone() as Calendar
    that.set(Calendar.HOUR_OF_DAY, 0); that.set(Calendar.MINUTE, 0); that.set(Calendar.SECOND, 0); that.set(Calendar.MILLISECOND, 0)
    val diffDays = (today.timeInMillis - that.timeInMillis) / (24 * 3600_000L)
    return when (diffDays) {
        0L -> "今天"
        1L -> "昨天"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(at))
    }
}
