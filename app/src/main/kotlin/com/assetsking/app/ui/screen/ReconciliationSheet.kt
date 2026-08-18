package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.accountTypeLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime

@Composable
fun ReconciliationSheet(
    accounts: List<AccountEntity>,
    onReconcile: (AccountEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val checked = remember { mutableStateMapOf(*accounts.map { it.id to true }.toTypedArray()) }

    Sheet(title = "对账确认", onDismiss = onDismiss) {
        Text(
            "确认各账户余额与实际一致，不一致的账户请回到首页点击编辑调整后再确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        accounts.forEach { account ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked[account.id] == true,
                    onCheckedChange = { checked[account.id] = it }
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${account.name} · ${accountTypeLabel(account.type)}",
                        fontWeight = FontWeight.Medium
                    )
                    val displayCents = if (account.type == com.assetsking.model.AccountType.CREDIT.name || account.type == com.assetsking.model.AccountType.LOAN.name)
                        -account.balanceCents else account.balanceCents
                    Text(
                        formatMoney(displayCents),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (displayCents < 0) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurface
                    )
                    val lastCheck = account.lastCheckedAt
                    if (lastCheck != null && lastCheck > 0) {
                        Text(
                            "上次对账 ${formatTime(lastCheck)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "尚未对过账",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (account.balanceStatus == "DISCREPANCY") {
                        Text(
                            "余额与银行报告存在差异，请核对流水后手动调整",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                accounts.filter { checked[it.id] == true }.forEach { onReconcile(it) }
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = checked.any { it.value }
        ) {
            Text("确认对账（${checked.count { it.value }}个账户）")
        }
    }
}
