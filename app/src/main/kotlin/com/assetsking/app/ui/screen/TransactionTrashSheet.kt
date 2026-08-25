package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime

private const val TRASH_DAY_MS = 24L * 60 * 60 * 1000

private fun trashTypeLabel(type: String): String = when (type) {
    TransactionType.EXPENSE.name -> "支出"
    TransactionType.INCOME.name -> "收入"
    TransactionType.REFUND.name -> "退款"
    TransactionType.FEE.name -> "手续费"
    TransactionType.LOAN_DISBURSEMENT.name -> "借款到账"
    TransactionType.LOAN_PAYMENT.name -> "贷款还款"
    TransactionType.LOAN_PREPAYMENT.name -> "提前还款"
    TransactionType.REIMBURSEMENT.name -> "报销到账"
    else -> "流水"
}

internal fun transactionTrashRemainingDays(deletedAt: Long, now: Long): Int {
    val remaining = deletedAt + 7L * TRASH_DAY_MS - now
    return ((remaining.coerceAtLeast(0L) + TRASH_DAY_MS - 1) / TRASH_DAY_MS).toInt()
}

@Composable
fun TransactionTrashSheet(
    transactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    onRestore: (String, (Result<Unit>) -> Unit) -> Unit,
    onPermanentlyDelete: (String, (Result<Unit>) -> Unit) -> Unit,
    transfers: List<TransferEntity> = emptyList(),
    onRestoreTransfer: (String, (Result<Unit>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("恢复划转服务未连接"))) },
    onPermanentlyDeleteTransfer: (String, (Result<Unit>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("删除划转服务未连接"))) },
    onDismiss: () -> Unit
) {
    var permanentDeleteTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var permanentTransferTarget by remember { mutableStateOf<TransferEntity?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()

    Sheet(title = "流水垃圾箱", onDismiss = onDismiss) {
        Text(
            "已删除流水保留 7 天。恢复会把余额、贷款或报销联动一并恢复；到期后自动永久删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        if (transactions.isEmpty() && transfers.isEmpty()) {
            Text("垃圾箱是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            transactions.forEach { transaction ->
                val accountName = accounts.firstOrNull { it.id == transaction.accountId }?.name ?: "未知账户"
                GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                transaction.merchant?.takeIf(String::isNotBlank)
                                    ?: transaction.note?.takeIf(String::isNotBlank)
                                    ?: trashTypeLabel(transaction.type),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold
                            )
                            Text(formatMoney(transaction.amountCents), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "${trashTypeLabel(transaction.type)} · $accountName · ${formatTime(transaction.occurredAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val deletedAt = transaction.deletedAt ?: now
                        Text(
                            "删除于 ${formatTime(deletedAt)} · 剩余 ${transactionTrashRemainingDays(deletedAt, now)} 天",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    busyId = transaction.id
                                    message = null
                                    onRestore(transaction.id) { result ->
                                        busyId = null
                                        result.onFailure { message = it.message ?: "恢复失败" }
                                    }
                                },
                                enabled = busyId == null,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (busyId == transaction.id) "恢复中…" else "恢复") }
                            OutlinedButton(
                                onClick = { permanentDeleteTarget = transaction },
                                enabled = busyId == null,
                                modifier = Modifier.weight(1f)
                            ) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            transfers.forEach { transfer ->
                val fromName = accounts.firstOrNull { it.id == transfer.fromAccountId }?.name ?: "未知账户"
                val toName = accounts.firstOrNull { it.id == transfer.toAccountId }?.name ?: "未知账户"
                GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("$fromName → $toName", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                            Text(formatMoney(transfer.amountCents), fontWeight = FontWeight.Bold)
                        }
                        Text("划转 · ${formatTime(transfer.occurredAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val deletedAt = transfer.deletedAt ?: now
                        Text("删除于 ${formatTime(deletedAt)} · 剩余 ${transactionTrashRemainingDays(deletedAt, now)} 天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    busyId = transfer.id
                                    message = null
                                    onRestoreTransfer(transfer.id) { result ->
                                        busyId = null
                                        result.onFailure { message = it.message ?: "恢复划转失败" }
                                    }
                                }, enabled = busyId == null, modifier = Modifier.weight(1f)
                            ) { Text(if (busyId == transfer.id) "恢复中…" else "恢复") }
                            OutlinedButton(onClick = { permanentTransferTarget = transfer }, enabled = busyId == null, modifier = Modifier.weight(1f)) {
                                Text("永久删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    permanentDeleteTarget?.let { transaction ->
        AlertDialog(
            onDismissRequest = { if (busyId == null) permanentDeleteTarget = null },
            title = { Text("永久删除这条流水？") },
            text = { Text("永久删除后无法恢复；原通知的忽略标记仍会保留，避免同一短信再次入库。") },
            confirmButton = {
                TextButton(
                    enabled = busyId == null,
                    onClick = {
                        busyId = transaction.id
                        message = null
                        onPermanentlyDelete(transaction.id) { result ->
                            busyId = null
                            result.onSuccess { permanentDeleteTarget = null }
                                .onFailure { message = it.message ?: "永久删除失败" }
                        }
                    }
                ) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { permanentDeleteTarget = null }, enabled = busyId == null) { Text("取消") }
            }
        )
    }

    permanentTransferTarget?.let { transfer ->
        AlertDialog(
            onDismissRequest = { if (busyId == null) permanentTransferTarget = null },
            title = { Text("永久删除这条划转？") },
            text = { Text("永久删除后无法恢复；与信用卡分期的联动快照也会一并清除。") },
            confirmButton = {
                TextButton(enabled = busyId == null, onClick = {
                    busyId = transfer.id
                    message = null
                    onPermanentlyDeleteTransfer(transfer.id) { result ->
                        busyId = null
                        result.onSuccess { permanentTransferTarget = null }
                            .onFailure { message = it.message ?: "永久删除划转失败" }
                    }
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { permanentTransferTarget = null }, enabled = busyId == null) { Text("取消") } }
        )
    }
}
