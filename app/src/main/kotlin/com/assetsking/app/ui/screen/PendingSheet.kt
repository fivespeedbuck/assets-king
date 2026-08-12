package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.database.AccountEntity
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney

private data class TypeOption(val type: TransactionType, val label: String)

private val typeOptions = listOf(
    TypeOption(TransactionType.EXPENSE, "支出"),
    TypeOption(TransactionType.INCOME, "收入"),
    TypeOption(TransactionType.REFUND, "退款")
)

@Composable
fun PendingSheet(
    items: List<PendingItem>,
    accounts: List<AccountEntity>,
    viewModel: LedgerViewModel,
    onDismiss: () -> Unit
) {
    Sheet(title = "待确认通知", onDismiss = onDismiss) {
        if (items.isEmpty()) {
            Text("没有待确认的通知", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.notification.id }) { item ->
                    PendingNotificationCard(item, accounts, viewModel)
                }
            }
        }
    }
}

@Composable
private fun PendingNotificationCard(
    item: PendingItem,
    accounts: List<AccountEntity>,
    viewModel: LedgerViewModel
) {
    val parsed = item.parsed
    val amountCents = parsed.amountCents ?: 0L
    val merchant = parsed.merchant ?: "未知商户"
    val suggestedCategory = viewModel.categorize(merchant, null)
    val defaultType = when {
        parsed.isExpense == false -> TransactionType.INCOME
        parsed.isExpense == true -> TransactionType.EXPENSE
        else -> TransactionType.EXPENSE
    }

    // 根据 bankHint 自动匹配账户
    val matchedAccount: AccountEntity? = parsed.bankHint?.let { hint ->
        accounts.firstOrNull { account ->
            account.name.contains(hint) || hint.contains(account.name)
        }
    }
    val defaultAccount = matchedAccount ?: accounts.firstOrNull() ?: return

    val typeLabel = when (defaultType) {
        TransactionType.EXPENSE -> "支出"
        TransactionType.INCOME -> "收入"
        TransactionType.REFUND -> "退款"
        else -> ""
    }

    var selectedAccountId by remember { mutableStateOf(defaultAccount.id) }
    var selectedCategory by remember { mutableStateOf(suggestedCategory) }
    var selectedType by remember { mutableStateOf(defaultType) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatMoney(amountCents),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                merchant,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }

        Spacer(Modifier.height(4.dp))

        // 方向 + 银行匹配提示
        val hintParts = buildList {
            add(typeLabel)
            if (matchedAccount != null && parsed.bankHint != null) {
                add("→ ${parsed.bankHint} → ${matchedAccount.name}")
            } else if (parsed.bankHint != null) {
                add("卡: ${parsed.bankHint}")
            }
        }
        Text(
            hintParts.joinToString("  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(4.dp))
        Text(
            item.notification.content.ifBlank { item.notification.title ?: "" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))

        // 交易类型
        Text("类型", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        ChipRow(
            items = typeOptions,
            selected = typeOptions.first { it.type == selectedType },
            onSelected = { selectedType = it.type },
            label = { it.label },
            id = { it.type.name }
        )

        Spacer(Modifier.height(8.dp))

        // 账户
        Text("入账账户", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        ChipRow(
            items = accounts,
            selected = accounts.firstOrNull { it.id == selectedAccountId } ?: defaultAccount,
            onSelected = { selectedAccountId = it.id },
            label = { it.name },
            id = { it.id }
        )

        Spacer(Modifier.height(8.dp))

        // 分类
        Text("分类", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        ChipRow(
            items = TransactionCategory.entries,
            selected = selectedCategory,
            onSelected = { selectedCategory = it },
            label = { categoryLabel(it) }
        )

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.ignoreNotification(item.notification.id) },
                modifier = Modifier.weight(1f)
            ) { Text("忽略") }
            Button(
                onClick = {
                    viewModel.confirmNotification(
                        notificationId = item.notification.id,
                        accountId = selectedAccountId,
                        amountCents = amountCents,
                        type = selectedType,
                        category = selectedCategory.name,
                        merchant = merchant,
                        note = null
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("确认入账") }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
