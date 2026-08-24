package com.assetsking.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.TransactionEntity
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCompactAmount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.app.ui.privacy.privacyScrambleText
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.format.transactionCategoryLabel
import com.assetsking.ui.theme.ReimbursementYellow
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import java.time.YearMonth
import java.time.ZoneId

/**
 * 报销栏目（REQ 报销§2/§6）：本月已报销金额 + 待报销垫付记录列表。
 * 报销到账在统一编辑器「入账→报销到账」里勾选垫付处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimbursementScreen(
    transactions: List<TransactionEntity>,
    onOpenTransaction: (TransactionEntity) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val privacyEnabled = LocalPrivacyEnabled.current
    val zone = ZoneId.systemDefault()
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val monthReimbursed = transactions
        .filter { it.type == "REIMBURSEMENT" && it.occurredAt in monthStart..monthEnd }
        .sumOf { it.amountCents }
    val pending = outstandingReimbursements(transactions)
    val pendingTotal = pending.sumOf(::reimbursementRemainingCents)
    val monthRecords = reimbursementMonthRecords(transactions, monthStart, monthEnd)
    val carriedPending = carriedOutstandingReimbursements(transactions, monthStart, monthEnd)

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
                    Column(Modifier.weight(1f)) {
                        Text(
                            "本月已报销",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (privacyEnabled) privacyFakeCompactAmount(2401) else formatMoney(monthReimbursed),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = ReimbursementYellow,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text(
                            "待报销总额",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (privacyEnabled) privacyFakeCompactAmount(2402) else formatMoney(pendingTotal),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = ReimbursementYellow,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "本月报销流水",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (monthRecords.isEmpty()) {
                    item { Text("本月暂无待报销或已报销流水", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(monthRecords, key = { it.id }) { tx ->
                    ReimbursementRecordCard(tx, onOpenTransaction)
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "跨期待报销",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (carriedPending.isEmpty()) {
                    item { Text("没有跨期未结清款项", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(carriedPending, key = { it.id }) { tx ->
                    ReimbursementRecordCard(tx, onOpenTransaction)
                }
            }
        }
    }
}

internal fun reimbursementMonthRecords(
    transactions: List<TransactionEntity>,
    monthStart: Long,
    monthEnd: Long
): List<TransactionEntity> = transactions
    .filter { it.occurredAt in monthStart..monthEnd && reimbursementBadge(it) != null }
    .sortedByDescending { it.occurredAt }

internal fun carriedOutstandingReimbursements(
    transactions: List<TransactionEntity>,
    monthStart: Long,
    monthEnd: Long
): List<TransactionEntity> = outstandingReimbursements(transactions)
    .filter { it.occurredAt !in monthStart..monthEnd }
    .sortedByDescending { it.occurredAt }

@Composable
private fun ReimbursementRecordCard(
    transaction: TransactionEntity,
    onClick: (TransactionEntity) -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyIndex = transaction.id.hashCode()
    val badge = reimbursementBadge(transaction) ?: return
    val amount = when (badge) {
        ReimbursementBadge.PENDING -> reimbursementRemainingCents(transaction)
        ReimbursementBadge.SETTLED, ReimbursementBadge.ARRIVAL -> transaction.amountCents
    }
    GlassCard {
        Row(
            Modifier.fillMaxWidth().clickable { onClick(transaction) }.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    transaction.merchant?.let { if (privacyEnabled) privacyObfuscatedText(it, 2420 + privacyIndex) else it } ?: "未命名",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (privacyEnabled) privacyObfuscatedText(badge.label, 2419 + privacyIndex) else badge.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ReimbursementYellow
                )
                val categoryText = transactionCategoryLabel(transaction.type, transaction.category)
                Text(
                    if (privacyEnabled) {
                        listOfNotNull(
                            privacyFakeDateTime(2421 + privacyIndex),
                            categoryText?.let { privacyScrambleText(it, 2422 + privacyIndex) }
                        ).joinToString(" · ")
                    } else {
                        listOfNotNull(formatTime(transaction.occurredAt), categoryText).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(if (privacyEnabled) privacyFakeAmount(2423 + privacyIndex) else formatMoney(amount), fontWeight = FontWeight.Bold, color = ReimbursementYellow)
                if (badge == ReimbursementBadge.PENDING) {
                    Text("原款 ${if (privacyEnabled) privacyFakeAmount(2424 + privacyIndex) else formatMoney(transaction.amountCents)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
