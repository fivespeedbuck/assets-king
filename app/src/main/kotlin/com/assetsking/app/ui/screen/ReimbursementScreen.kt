package com.assetsking.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.TransactionEntity
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import java.time.YearMonth
import java.time.ZoneId

private val ReimbGreen = Color(0xFF66BB6A)
private val ReimbOrange = Color(0xFFFFB74D)

/**
 * 报销栏目（REQ 报销§2/§6）：本月已报销金额 + 待报销垫付记录列表。
 * 报销到账在统一编辑器「入账→报销到账」里勾选垫付处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimbursementScreen(
    reimbursableTxs: List<TransactionEntity>,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val zone = ZoneId.systemDefault()
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val monthReimbursed = reimbursableTxs.filter { it.occurredAt in monthStart..monthEnd }.sumOf { it.reimbursedCents }
    val pending = reimbursableTxs.filter { it.isReimbursable && it.reimbursedCents < it.amountCents }
        .sortedByDescending { it.occurredAt }
    // 审核 J-4 修复：补「待报销总额」（REQ 报销§2 要求显示本月待报销金额）。
    val pendingTotal = pending.sumOf { (it.amountCents - it.reimbursedCents).coerceAtLeast(0L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待报销") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("本月已报销", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(monthReimbursed), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ReimbGreen)
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text("待报销总额", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(pendingTotal), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ReimbOrange)
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending.isEmpty()) {
                    item { Text("没有待报销的消费", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(pending, key = { it.id }) { tx ->
                    GlassCard {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tx.merchant ?: "未命名", fontWeight = FontWeight.Medium)
                                Text(
                                    "${formatTime(tx.occurredAt)} · ${tx.category.ifEmpty { "待分类" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(
                                    "剩 ${formatMoney((tx.amountCents - tx.reimbursedCents).coerceAtLeast(0L))}",
                                    fontWeight = FontWeight.Bold,
                                    color = ReimbOrange
                                )
                                Text("共 ${formatMoney(tx.amountCents)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
