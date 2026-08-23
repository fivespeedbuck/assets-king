package com.assetsking.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.database.AccountEntity
import com.assetsking.database.BalanceAdjustmentEntity
import com.assetsking.database.BalanceCheckpointEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.AccountType
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.theme.transactionCashFlowColor

private val DetailGreen = Color(0xFF66BB6A)
private val DetailRed = Color(0xFFE57373)
private val DetailOrange = Color(0xFFFFB74D)

private fun checkpointSourceLabel(source: String): String = when (source) {
    "OPENING" -> "开户"
    "BANK_SMS" -> "银行短信"
    "MANUAL" -> "手动对账"
    else -> source
}

/**
 * 账户详情页（REQ 账户对账 §1-3/§8/§12-13）：余额口径（已对账/账面）、对账状态、
 * 7 天未对账提醒、手动对账与编辑入口、对账历史与余额调整同列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    account: AccountEntity,
    viewModel: LedgerViewModel,
    transactions: List<TransactionEntity> = emptyList(),
    statementRemainingCents: Long = account.statementOriginalDueCents,
    onOpenTransaction: (TransactionEntity) -> Unit = {},
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val privacyEnabled = LocalPrivacyEnabled.current
    var checkpoints by remember { mutableStateOf<List<BalanceCheckpointEntity>>(emptyList()) }
    var adjustments by remember { mutableStateOf<List<BalanceAdjustmentEntity>>(emptyList()) }
    var creditFlowScope by remember(account.id, account.statementDay) {
        mutableStateOf(if (account.statementDay == null) CreditFlowScope.ALL else CreditFlowScope.STATEMENT)
    }
    LaunchedEffect(account.id) {
        checkpoints = viewModel.checkpointsFor(account.id)
        adjustments = viewModel.adjustmentsFor(account.id)
    }

    val latest = checkpoints.maxByOrNull { it.checkedAt }
    val isAsset = account.type == AccountType.ASSET.name
    val lastChecked = account.lastCheckedAt
    val stale = isAsset && ((lastChecked == null) || System.currentTimeMillis() - lastChecked > 7L * 24 * 3600 * 1000)
    // 上次手动对账 0 元不提示（REQ 账户对账§13）
    val manualZero = latest?.source == "MANUAL" && latest.balanceCents == 0L
    val showStale = stale && !manualZero

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (privacyEnabled) privacyObfuscatedText(account.name, 2001) else account.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        if (account.type == AccountType.CREDIT.name) "总欠款" else "余额",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (privacyEnabled) privacyFakeAmount(2002) else formatMoney(account.balanceCents),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isAsset) DetailGreen else DetailRed
                    )
                    account.startDateEpochDay?.takeIf { account.type != AccountType.CREDIT.name }?.let { d ->
                        Text(
                            if (privacyEnabled) "启用日期 ${privacyFakeDateTime(2003).substringBefore(' ')}" else "启用日期 ${java.time.LocalDate.ofEpochDay(d)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 信用账户拆分展示：即使本期应还为 0，也要让普通未出账消费有明确入口。
                    if (account.type == AccountType.CREDIT.name) {
                        Spacer(Modifier.height(4.dp))
                        val statement = statementRemainingCents.coerceAtLeast(0L)
                        val unbilled = (account.balanceCents - statement).coerceAtLeast(0L)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("本期应还", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (privacyEnabled) privacyFakeAmount(2004) else formatMoney(statement),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = DetailOrange
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("未出账/未来分期", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (privacyEnabled) privacyFakeAmount(2005) else formatMoney(unbilled),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (unbilled > 0L) DetailRed else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            if (privacyEnabled) {
                                "账单日 ${privacyFakeCount(2006)} · 还款日 ${privacyFakeCount(2007)}"
                            } else {
                                "账单日 ${account.statementDay?.let { "每月${it}日" } ?: "未设置"} · 还款日 ${account.dueDay?.let { "每月${it}日" } ?: "未设置"}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (privacyEnabled || account.pendingCents > 0L) {
                            Text(
                                "待入账 ${if (privacyEnabled) privacyFakeAmount(2008) else formatMoney(account.pendingCents)}（暂不计正式欠款）",
                                style = MaterialTheme.typography.labelSmall,
                                color = DetailOrange
                            )
                        }
                    }
                    // 余额口径（REQ 账户对账§12）：有有效检查点=已对账余额；现金/钱包=账面余额
                    val valid = latest != null && latest.checkedAt > 0
                    if (account.type != AccountType.CREDIT.name) {
                        Text(
                            if (valid) "已对账余额 · ${checkpointSourceLabel(latest!!.source)} ${if (privacyEnabled) privacyFakeDateTime(2006) else formatTime(latest.checkedAt)}"
                            else "账面余额 · 待手动对账",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (account.balanceStatus == "DISCREPANCY") {
                        Spacer(Modifier.height(4.dp))
                        Text("差额待核对：银行余额与账面推算不一致，请手动对账", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = DetailRed)
                    }
                    if (showStale) {
                        Spacer(Modifier.height(4.dp))
                        Text("余额可能过期：超过 7 天未对账，建议手动对账", style = MaterialTheme.typography.bodySmall, color = DetailOrange)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReconcile) { Text("手动对账") }
                        OutlinedButton(onClick = onEdit) { Text("编辑账户") }
                    }
                }
            }

            if (account.type == AccountType.CREDIT.name) {
                val today = java.time.LocalDate.now()
                val cycleWindow = creditCycleWindow(account.statementDay, today)
                val accountFlows = creditFlowTransactions(
                    accountId = account.id,
                    transactions = transactions,
                    scope = creditFlowScope,
                    statementDay = account.statementDay,
                    today = today,
                    statementOutstandingCents = statementRemainingCents.coerceAtLeast(0L)
                )
                val statementTotal = statementRemainingCents.coerceAtLeast(0L)
                val unbilledTotal = (account.balanceCents - statementTotal).coerceAtLeast(0L)
                val scopeTotal = when (creditFlowScope) {
                    CreditFlowScope.STATEMENT -> statementTotal
                    CreditFlowScope.UNBILLED -> unbilledTotal
                    CreditFlowScope.ALL -> account.balanceCents.coerceAtLeast(0L)
                }
                val scopeTotalLabel = when (creditFlowScope) {
                    CreditFlowScope.STATEMENT -> "本期总账单"
                    CreditFlowScope.UNBILLED -> "未出账总账单"
                    CreditFlowScope.ALL -> "当前未还总额"
                }
                GlassCard {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("账户流水", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (privacyEnabled) "${privacyFakeCount(2010)} 笔" else "${accountFlows.size} 笔",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (cycleWindow != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CreditFlowScope.entries.forEach { scope ->
                                    FilterChip(
                                        selected = creditFlowScope == scope,
                                        onClick = { creditFlowScope = scope },
                                        label = { Text(scope.label) }
                                    )
                                }
                            }
                            Text(
                                "${creditCycleRangeLabel(creditFlowScope, cycleWindow)} · 按消费日期估算，银行实际账单为准",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "未设置出账日，先显示该账户全部流水。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(scopeTotalLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (privacyEnabled) privacyFakeAmount(2009) else formatMoney(scopeTotal),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (scopeTotal > 0L) DetailOrange else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (accountFlows.isEmpty()) {
                            Text(
                                when {
                                    creditFlowScope == CreditFlowScope.STATEMENT && statementTotal == 0L -> "本期账单已还清"
                                    creditFlowScope == CreditFlowScope.ALL && account.balanceCents <= 0L -> "当前没有未还账款"
                                    else -> "这个账期暂无流水"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            accountFlows.take(30).forEachIndexed { index, transaction ->
                                CreditAccountFlowRow(
                                    transaction = transaction,
                                    privacyIndex = 2_100 + index,
                                    onClick = { if (!privacyEnabled) onOpenTransaction(transaction) }
                                )
                            }
                            if (accountFlows.size > 30) {
                                Text(
                                    "仅显示最近 30 笔，完整历史可到总流水核对。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("对账历史", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (checkpoints.isEmpty()) {
                        Text("暂无对账记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    checkpoints.sortedByDescending { it.checkedAt }.forEachIndexed { index, cp ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(checkpointSourceLabel(cp.source), style = MaterialTheme.typography.bodySmall)
                            Text(if (privacyEnabled) privacyFakeAmount(2020 + index) else formatMoney(cp.balanceCents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 余额调整与检查点同列（REQ 账户对账§7-8）
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("余额调整记录", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (adjustments.isEmpty()) {
                        Text("暂无余额调整", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    adjustments.sortedByDescending { it.occurredAt }.forEachIndexed { index, adj ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    if (privacyEnabled) "差额 ${privacyFakeAmount(2040 + index * 4)}"
                                    else if (adj.diffCents >= 0) "差额 +${formatMoney(adj.diffCents)}" else "差额 ${formatMoney(adj.diffCents)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DetailOrange
                                )
                                Text(if (privacyEnabled) privacyFakeDateTime(2041 + index * 4) else formatTime(adj.occurredAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                if (privacyEnabled) {
                                    "${privacyFakeAmount(2042 + index * 4)} → ${privacyFakeAmount(2043 + index * 4)}${adj.reason.takeIf { it.isNotBlank() }?.let { " · ${privacyObfuscatedText(it, 2060 + index)}" } ?: ""}"
                                } else {
                                    "${formatMoney(adj.beforeCents)} → ${formatMoney(adj.afterCents)}${adj.reason.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditAccountFlowRow(
    transaction: TransactionEntity,
    privacyIndex: Int,
    onClick: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val title = transaction.merchant?.takeIf { it.isNotBlank() }
        ?: transaction.note?.takeIf { it.isNotBlank() }
        ?: transaction.category
    Column(
        Modifier.fillMaxWidth().clickable(enabled = !privacyEnabled, onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (privacyEnabled) privacyObfuscatedText(title, privacyIndex) else title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    if (privacyEnabled) {
                        "${privacyObfuscatedText(transaction.category, privacyIndex + 100)} · ${privacyFakeDateTime(privacyIndex + 200)}"
                    } else {
                        "${transaction.category} · ${formatTime(transaction.occurredAt)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                if (privacyEnabled) privacyFakeAmount(privacyIndex + 300) else formatMoney(transaction.amountCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = transactionCashFlowColor(transaction.type)
            )
        }
        HorizontalDivider()
    }
}
