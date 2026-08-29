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
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.accountTypeLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.privacy.LocalPrivacyEnabled

@Composable
fun ReconciliationSheet(
    accounts: List<AccountEntity>,
    receivableAccountIds: Set<String> = emptySet(),
    onReconcile: (AccountEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val visibleAccounts = remember(accounts) {
        accounts.asSequence()
            .filter { !it.archived }
            .sortedWith(
                compareBy<AccountEntity> { account ->
                    val checkedAt = account.lastCheckedAt
                    when {
                        account.balanceStatus == "DISCREPANCY" -> 0
                        checkedAt == null || checkedAt <= 0L -> 1
                        else -> 2
                    }
                }.thenBy { it.lastCheckedAt ?: Long.MIN_VALUE }
                    .thenBy { it.name }
                    .thenBy { it.cardTail.orEmpty() }
            )
            .toList()
    }
    val assetAccounts = visibleAccounts.filter {
        it.type == com.assetsking.model.AccountType.ASSET.name && it.id !in receivableAccountIds
    }
    val receivableAccounts = visibleAccounts.filter { it.id in receivableAccountIds }
    val debtAccounts = visibleAccounts.filter {
        it.type != com.assetsking.model.AccountType.ASSET.name && it.id !in receivableAccountIds
    }
    val checked = remember(visibleAccounts) {
        mutableStateMapOf(*visibleAccounts.map { it.id to true }.toTypedArray())
    }

    Sheet(title = "对账确认", onDismiss = onDismiss) {
        Text(
            "确认各账户余额与实际一致，不一致的账户请回到首页点击编辑调整后再确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "资产账户" to assetAccounts,
            "应收账户" to receivableAccounts,
            "负债账户" to debtAccounts
        ).forEach { (title, group) ->
            if (group.isNotEmpty()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                group.forEach { account ->
                    val index = visibleAccounts.indexOf(account)
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
                                "${if (privacyEnabled) privacyObfuscatedText(account.name, 2600 + index) else account.name} · ${accountTypeLabel(account.type)}",
                                fontWeight = FontWeight.Medium
                            )
                            val displayCents = if (
                                account.type == com.assetsking.model.AccountType.CREDIT.name ||
                                account.type == com.assetsking.model.AccountType.LOAN.name
                            ) -account.balanceCents else account.balanceCents
                            Text(
                                if (privacyEnabled) privacyFakeAmount(2620 + index) else formatMoney(displayCents),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (displayCents < 0) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurface
                            )
                            val lastCheck = account.lastCheckedAt
                            if (lastCheck != null && lastCheck > 0) {
                                Text(
                                    "上次对账 ${if (privacyEnabled) privacyFakeDateTime(2640 + index) else formatTime(lastCheck)}",
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
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                visibleAccounts.filter { checked[it.id] == true }.forEach { onReconcile(it) }
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = checked.any { it.value }
        ) {
            Text(if (privacyEnabled) "确认对账（${privacyFakeCount(2660)}个账户）" else "确认对账（${checked.count { it.value }}个账户）")
        }
    }
}
