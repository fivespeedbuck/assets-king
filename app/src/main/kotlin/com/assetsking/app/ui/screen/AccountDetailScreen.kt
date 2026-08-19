package com.assetsking.app.ui.screen

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
import com.assetsking.database.AccountEntity
import com.assetsking.database.BalanceAdjustmentEntity
import com.assetsking.database.BalanceCheckpointEntity
import com.assetsking.model.AccountType
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime

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
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onBack: () -> Unit
) {
    var checkpoints by remember { mutableStateOf<List<BalanceCheckpointEntity>>(emptyList()) }
    var adjustments by remember { mutableStateOf<List<BalanceAdjustmentEntity>>(emptyList()) }
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
                title = { Text(account.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("余额", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMoney(account.balanceCents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (isAsset) DetailGreen else DetailRed)
                    account.startDateEpochDay?.let { d ->
                        Text(
                            "启用日期 ${java.time.LocalDate.ofEpochDay(d)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 信用卡拆分展示（REQ 信用卡欠款口径§2）：本期应还 + 未出账，都计入总欠款
                    if (account.type == AccountType.CREDIT.name && account.statementOriginalDueCents > 0) {
                        Spacer(Modifier.height(4.dp))
                        val statement = account.statementOriginalDueCents
                        val unbilled = (account.balanceCents - statement).coerceAtLeast(0L)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("本期应还 ${formatMoney(statement)}", style = MaterialTheme.typography.bodySmall, color = DetailOrange)
                            Text("未出账 ${formatMoney(unbilled)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 余额口径（REQ 账户对账§12）：有有效检查点=已对账余额；现金/钱包=账面余额
                    val valid = latest != null && latest.checkedAt > 0
                    Text(
                        if (valid) "已对账余额 · ${checkpointSourceLabel(latest!!.source)} ${formatTime(latest.checkedAt)}"
                        else "账面余额 · 待手动对账",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("对账历史", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (checkpoints.isEmpty()) {
                        Text("暂无对账记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    checkpoints.sortedByDescending { it.checkedAt }.forEach { cp ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(checkpointSourceLabel(cp.source), style = MaterialTheme.typography.bodySmall)
                            Text(formatMoney(cp.balanceCents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
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
                    adjustments.sortedByDescending { it.occurredAt }.forEach { adj ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (adj.diffCents >= 0) "差额 +${formatMoney(adj.diffCents)}" else "差额 ${formatMoney(adj.diffCents)}", style = MaterialTheme.typography.bodySmall, color = DetailOrange)
                                Text(formatTime(adj.occurredAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                "${formatMoney(adj.beforeCents)} → ${formatMoney(adj.afterCents)}${adj.reason.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
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
