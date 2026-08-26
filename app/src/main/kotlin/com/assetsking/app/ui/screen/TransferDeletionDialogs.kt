package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransferDeletionAccountPreview
import com.assetsking.model.AccountType
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.format.formatMoney

@Composable
internal fun TransferDeletionPreviewDialog(
    previews: List<TransferDeletionAccountPreview>?,
    accounts: List<AccountEntity>,
    errorMessage: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onBalancesMatch: () -> Unit,
    onBalancesMismatch: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("删除划转前核对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这条划转会进入垃圾箱并保留 7 天。请先核对两边账户删除后的预计余额。")
                if (previews == null) Text("正在计算删除后的余额…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else previews.forEachIndexed { index, preview ->
                    val account = accounts.firstOrNull { it.id == preview.accountId }
                    val debt = account?.type in setOf(AccountType.CREDIT.name, AccountType.LOAN.name)
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            if (privacyEnabled) privacyObfuscatedText("账户", 6200 + index) else account?.name ?: "未知账户",
                            fontWeight = FontWeight.Bold
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (debt) "当前欠款" else "当前余额", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (privacyEnabled) privacyFakeAmount(6201 + index * 3) else formatMoney(preview.currentBalanceCents))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (debt) "删除后预计欠款" else "删除后预计余额", fontWeight = FontWeight.Bold)
                            Text(
                                if (privacyEnabled) privacyFakeAmount(6202 + index * 3) else formatMoney(preview.projectedBalanceCents),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (preview.balanceStatus == "DISCREPANCY") {
                            Text("该账户当前已有对账差额", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (index != previews.lastIndex) HorizontalDivider()
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (previews?.any { it.balanceStatus == "DISCREPANCY" } == true) onBalancesMismatch()
                    else onBalancesMatch()
                },
                enabled = previews != null && !busy && previews.none { it.balanceStatus == "DISCREPANCY" }
            ) { Text(if (busy) "处理中…" else "账目正确，删除") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBalancesMismatch, enabled = previews != null && !busy) { Text("账对不上") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            }
        }
    )
}

@Composable
internal fun TransferDeletionRiskDialog(
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirmAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("划转账目尚未对上") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("继续删除可能保留账户差额。划转仍会进入 7 天垃圾箱，发现问题可恢复。", color = MaterialTheme.colorScheme.error)
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirmAnyway, enabled = !busy) { Text("仍然删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("返回核对") } }
    )
}
