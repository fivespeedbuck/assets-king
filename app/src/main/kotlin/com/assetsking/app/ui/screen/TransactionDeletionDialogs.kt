package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionDeletionAccountPreview
import com.assetsking.model.AccountType
import com.assetsking.ui.format.formatMoney

@Composable
internal fun TransactionDeletionPreviewDialog(
    transactionCount: Int,
    previews: List<TransactionDeletionAccountPreview>?,
    accounts: List<AccountEntity>,
    errorMessage: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onBalancesMatch: () -> Unit,
    onBalancesMismatch: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("删除前核对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这 $transactionCount 笔正式流水将进入垃圾箱并保留 7 天。请先核对删除后的预计账目。")
                if (previews == null) {
                    Text("正在计算删除后的余额…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    previews.forEachIndexed { index, preview ->
                        val account = accounts.firstOrNull { it.id == preview.accountId }
                        val debtAccount = account?.type in setOf(AccountType.CREDIT.name, AccountType.LOAN.name)
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                account?.name ?: "未知账户",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (debtAccount) "当前欠款" else "当前余额", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatMoney(preview.currentBalanceCents), fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (debtAccount) "删除后预计欠款" else "删除后预计余额", fontWeight = FontWeight.Bold)
                                Text(
                                    formatMoney(preview.projectedBalanceCents),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (preview.balanceStatus == "DISCREPANCY") {
                                Text(
                                    "该账户当前已有对账差额",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (index != previews.lastIndex) HorizontalDivider()
                    }
                }
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onBalancesMatch,
                enabled = previews != null && !busy
            ) { Text(if (busy) "处理中…" else "账目正确，删除") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onBalancesMismatch,
                    enabled = previews != null && !busy
                ) { Text("账对不上") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            }
        }
    )
}

@Composable
internal fun TransactionDeletionRiskDialog(
    transactionCount: Int,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirmAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("账目尚未对上") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "继续删除这 $transactionCount 笔流水可能保留账户差额。流水仍会进入 7 天垃圾箱，可在确认问题后恢复。",
                    color = MaterialTheme.colorScheme.error
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmAnyway, enabled = !busy) {
                Text(if (busy) "处理中…" else "仍然删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("返回核对") } }
    )
}
