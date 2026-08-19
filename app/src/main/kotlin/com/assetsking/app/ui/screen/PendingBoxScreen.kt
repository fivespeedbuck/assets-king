package com.assetsking.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.usecase.AccountInference
import com.assetsking.usecase.TransferPairMerge
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val BoxGreen = Color(0xFF66BB6A)
private val BoxRed = Color(0xFFE57373)
private val BoxGray = Color(0xFF757575)

private fun typeOf(item: PendingItem): TransactionType = when {
    item.parsed.isExpense == false -> TransactionType.INCOME
    else -> TransactionType.EXPENSE
}

private fun amountColor(type: TransactionType): Color = when (type) {
    TransactionType.EXPENSE -> BoxRed
    TransactionType.INCOME, TransactionType.REFUND, TransactionType.REIMBURSEMENT -> BoxGreen
    else -> BoxGray
}

private fun amountPrefix(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "−¥"
    TransactionType.INCOME, TransactionType.REFUND, TransactionType.REIMBURSEMENT -> "+¥"
    else -> "¥"
}

/**
 * 待确认箱全屏页（REQ 待确认箱 UI §1-13）：倒序列表、日期分隔、批量确认、
 * 左滑二次确认删除、空状态。点击卡片进入统一编辑器补全与确认（REQ 编辑器§1）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PendingBoxScreen(
    items: List<PendingItem>,
    accounts: List<AccountEntity>,
    merchantLastAccount: Map<String, String>,
    viewModel: LedgerViewModel,
    lastReceivedAt: Long,
    listenerStatus: ListenerStatus,
    onOpenEditor: (PendingItem) -> Unit,
    onBack: () -> Unit
) {
    val sorted = remember(items) { items.sortedByDescending { it.notification.postedAt } }
    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<PendingItem?>(null) }
    // 同额转出+转入自动合并（REQ 待确认交易类型§4）；「分开处理」后恢复独立卡片
    val transferPairs = remember(sorted) {
        TransferPairMerge.findPairs(sorted.map {
            TransferPairMerge.Leg(it.notification.id, it.parsed.amountCents ?: 0L, it.parsed.isExpense == true, it.notification.postedAt)
        }).map { p ->
            sorted.first { it.notification.id == p.out.id } to sorted.first { it.notification.id == p.inLeg.id }
        }
    }
    var unmerged by remember(sorted) { mutableStateOf(setOf<String>()) }

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 监听中断红色状态条（REQ 监听§7）：首页金库卡之外的待确认箱侧警示
            if (listenerStatus != ListenerStatus.OK) {
                Row(
                    Modifier.fillMaxWidth().background(BoxRed).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (listenerStatus == ListenerStatus.DISABLED) "自动记账已中断：通知使用权未开启，新账目不会进入此箱" else "入库暂时中断：监听掉线，正在自动恢复",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (sorted.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 主题色金库图标（REQ 待确认箱§7），不用 Emoji（REQ 视觉§4）
                        Icon(
                            Icons.Filled.AccountBalance,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
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
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                var lastDay: String? = null
                val pairIds = transferPairs.flatMap { listOf(it.first.notification.id, it.second.notification.id) }.toSet()
                val renderedMerged = mutableSetOf<String>()
                sorted.forEach { item ->
                    val pair = transferPairs.firstOrNull { it.first.notification.id == item.notification.id }
                    val merged = item.notification.id in pairIds && item.notification.id !in unmerged
                    if (merged) {
                        // 转入腿不在自己的位置渲染（由转出腿的合并卡代表），也不留日期分隔
                        if (pair == null) return@forEach
                        if (!renderedMerged.add(pair.first.notification.id)) return@forEach
                        item(key = "transferpair-${pair.first.notification.id}") {
                            TransferPairCard(
                                outItem = pair.first,
                                inItem = pair.second,
                                accounts = accounts,
                                merchantLastAccount = merchantLastAccount,
                                viewModel = viewModel,
                                onConfirmed = { scope.launch { snackbar.showSnackbar("已记转账") } },
                                onSplit = { unmerged = unmerged + pair.first.notification.id + pair.second.notification.id }
                            )
                        }
                        return@forEach
                    }
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
                        // 左滑驻留：露出红色删除按钮后停住，点按钮才弹二次确认（REQ 待确认箱§11）
                        val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                            value == SwipeToDismissBoxValue.EndToStart
                        })
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    Modifier.fillMaxSize().background(BoxRed).padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Text(
                                        "删除",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable {
                                                deleteTarget = item
                                                scope.launch { dismissState.reset() }
                                            }
                                            .padding(vertical = 12.dp, horizontal = 20.dp)
                                    )
                                }
                            }
                        ) {
                            PendingBoxCard(
                                item = item,
                                accounts = accounts,
                                merchantLastAccount = merchantLastAccount,
                                multiSelect = multiSelect,
                                checked = item.notification.id in selected,
                                complete = item.parsed.merchant != null,
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
                                onOpenEditor = { onOpenEditor(item) },
                                viewModel = viewModel,
                                onConfirmed = { scope.launch { snackbar.showSnackbar("已入账") } }
                            )
                        }
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
    multiSelect: Boolean,
    checked: Boolean,
    complete: Boolean,
    onToggleSelect: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    onOpenEditor: () -> Unit,
    viewModel: LedgerViewModel,
    onConfirmed: () -> Unit
) {
    val parsed = item.parsed
    val amountCents = parsed.amountCents ?: 0L
    val merchant = parsed.merchant
    val type = typeOf(item)
    val inferredId = AccountInference.infer(
        bankMatchedAccountId = parsed.bankHint?.let { hint ->
            accounts.firstOrNull { it.name.contains(hint) || hint.contains(it.name) }?.id
        },
        merchantHistoryAccountId = merchant?.let { merchantLastAccount[it] },
        sourcePackage = item.notification.packageName,
        candidates = accounts.map { AccountInference.Candidate(it.id, it.name) }
    )
    val account = accounts.firstOrNull { it.id == inferredId }
    val isCompleting = System.currentTimeMillis() - item.notification.receivedAt < 10_000
    val isRescanned = item.notification.sourceLabel?.startsWith("短信补回") == true

    GlassCard(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (multiSelect) onToggleSelect() else onOpenEditor() },
            onLongClick = onEnterMultiSelect
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (multiSelect) {
                    Checkbox(checked = checked, onCheckedChange = { onToggleSelect() }, enabled = complete)
                }
                Text(
                    "${amountPrefix(type)}${formatMoney(amountCents)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor(type)
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
                    OutlinedButton(onClick = onOpenEditor) { Text("去补全") }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("· ${account?.name ?: "未选账户"}", style = MaterialTheme.typography.labelSmall)
                Text("· ${AccountInference.channelLabel(item.notification.packageName, item.notification.sourceLabel)}", style = MaterialTheme.typography.labelSmall)
                Text("· ${formatTime(item.notification.postedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val bankBalance = parsed.balanceCents
            val bankTail = parsed.cardTail
            val badges = buildList {
                if (isCompleting) add("正在补全")
                if (isRescanned) add("由短信补回")
                if (bankBalance != null && bankTail != null) add("银行余额 ${formatMoney(bankBalance)}")
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    badges.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 单笔快速确认：金额、方向、推断账户、建议分类、商户（REQ 待确认箱§15） */
private fun confirmItem(
    viewModel: LedgerViewModel,
    item: PendingItem,
    accounts: List<AccountEntity>,
    merchantLastAccount: Map<String, String>
) {
    val parsed = item.parsed
    val amount = parsed.amountCents ?: return
    val type = when {
        // 审核 BUG-6 修复：优先识别退款（isRefund），否则退款被记成 INCOME，
        // 虚增收入且不冲减原消费（REQ 退款§8/收入§5）。
        parsed.isRefund -> TransactionType.REFUND
        parsed.isExpense == false -> TransactionType.INCOME
        else -> TransactionType.EXPENSE
    }
    val accountId = inferAccountId(item, accounts, merchantLastAccount) ?: accounts.firstOrNull()?.id ?: return
    viewModel.confirmNotification(
        notificationId = item.notification.id,
        accountId = accountId,
        amountCents = amount,
        type = type,
        category = viewModel.categorize(parsed.merchant, null).name,
        merchant = parsed.merchant,
        note = item.notification.title,
        bankBalanceCents = parsed.balanceCents,
        bankCardTail = parsed.cardTail,
        channel = AccountInference.channelLabel(item.notification.packageName, item.notification.sourceLabel)
    )
}

private fun inferAccountId(item: PendingItem, accounts: List<AccountEntity>, merchantLastAccount: Map<String, String>): String? =
    AccountInference.infer(
        bankMatchedAccountId = item.parsed.bankHint?.let { hint ->
            accounts.firstOrNull { it.name.contains(hint) || hint.contains(it.name) }?.id
        },
        merchantHistoryAccountId = item.parsed.merchant?.let { merchantLastAccount[it] },
        sourcePackage = item.notification.packageName,
        candidates = accounts.map { AccountInference.Candidate(it.id, it.name) }
    )

/** 同额转出+转入合并卡（REQ 待确认交易类型§4）：确认直接记账户转账，可「分开处理」恢复独立卡片 */
@Composable
private fun TransferPairCard(
    outItem: PendingItem,
    inItem: PendingItem,
    accounts: List<AccountEntity>,
    merchantLastAccount: Map<String, String>,
    viewModel: LedgerViewModel,
    onConfirmed: () -> Unit,
    onSplit: () -> Unit
) {
    val amount = outItem.parsed.amountCents ?: 0L
    val fromId = inferAccountId(outItem, accounts, merchantLastAccount).orEmpty()
    val toId = inferAccountId(inItem, accounts, merchantLastAccount).orEmpty()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("账户转账", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(formatMoney(amount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, color = BoxGray)
            }
            Text(
                "${outItem.notification.sourceLabel ?: outItem.notification.packageName} 转出 → ${inItem.notification.sourceLabel ?: inItem.notification.packageName} 转入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("${formatTime(outItem.notification.postedAt)} · 同额反向自动合并", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = fromId.isNotBlank() && toId.isNotBlank() && amount > 0,
                    onClick = {
                        viewModel.confirmTransferPair(outItem.notification.id, inItem.notification.id, fromId, toId, amount, "账户转账（通知合并）")
                        onConfirmed()
                    }
                ) { Text("确认转账") }
                TextButton(onClick = onSplit) { Text("分开处理") }
            }
        }
    }
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
