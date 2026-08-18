package com.assetsking.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.ui.format.accountTypeLabel
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime

@Composable
fun AccountRow(account: AccountEntity, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(account.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            val details = buildString {
                append(accountTypeLabel(account.type))
                if (account.statementDay != null) append(" · 出账${account.statementDay}日")
                if (account.dueDay != null) append(" · 还款${account.dueDay}日")
                if (account.creditLimitCents > 0) append(" · 额度${"%.0f".format(account.creditLimitCents / 100.0)}")
            }
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val displayCents = if (account.type == AccountType.CREDIT.name || account.type == AccountType.LOAN.name)
            -account.balanceCents else account.balanceCents
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatMoney(displayCents),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                color = if (displayCents < 0) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurface
            )
            // Credit usage bar
            if (account.type == AccountType.CREDIT.name && account.creditLimitCents > 0) {
                val usagePct = (account.balanceCents.toFloat() / account.creditLimitCents).coerceIn(0f, 1f)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { usagePct },
                    modifier = Modifier.width(60.dp).height(4.dp),
                    color = if (usagePct > 0.8f) MaterialTheme.colorScheme.error
                           else if (usagePct > 0.5f) androidx.compose.ui.graphics.Color(0xFFFF9800)
                           else androidx.compose.ui.graphics.Color(0xFF66BB6A),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

private fun txTypeLabel(type: String): String = when (type) {
    "EXPENSE" -> "支出"
    "INCOME" -> "收入"
    "REFUND" -> "退款"
    "FEE" -> "手续费"
    "LOAN_DISBURSEMENT" -> "借款到账"
    "LOAN_PAYMENT" -> "贷款还款"
    "LOAN_PREPAYMENT" -> "提前还款"
    "REIMBURSEMENT" -> "报销到账"
    else -> type
}

@Composable
fun TransactionRow(
    transaction: TransactionEntity,
    accountName: String,
    onCategoryChange: (String, TransactionCategory) -> Unit,
    onClick: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 自定义分类（如"转账"）不在枚举里：valueOf 失败时直接显示存储的原始名字，不回退成"未分类"
    val category = runCatching { TransactionCategory.valueOf(transaction.category) }.getOrNull()
    val categoryText = category?.let { categoryLabel(it) } ?: transaction.category
    val isLoanTx = transaction.type == "LOAN_DISBURSEMENT" || transaction.type == "LOAN_PAYMENT" || transaction.type == "LOAN_PREPAYMENT"
    // V5：借款/还款/提前还款流水不可编辑分类（与贷款计划联动），只读展示
    val title = when {
        transaction.type == "LOAN_DISBURSEMENT" -> "借款到账"
        transaction.type == "LOAN_PAYMENT" -> "贷款还款"
        transaction.type == "LOAN_PREPAYMENT" -> "提前还款"
        else -> transaction.merchant ?: categoryText
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "$accountName · ${txTypeLabel(transaction.type)} · ${formatTime(transaction.occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (transaction.type == "LOAN_PAYMENT" &&
                    (transaction.principalCents > 0 || transaction.interestCents > 0 || transaction.feeCents > 0)
                ) {
                    val split = buildString {
                        if (transaction.principalCents > 0) append("本金 ${formatMoney(transaction.principalCents)}")
                        if (transaction.interestCents > 0) append(" · 利息 ${formatMoney(transaction.interestCents)}")
                        if (transaction.feeCents > 0) append(" · 费 ${formatMoney(transaction.feeCents)}")
                    }
                    Text(
                        split,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                formatMoney(transaction.amountCents),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (!isLoanTx) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(
                    categoryText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { menuExpanded = true }
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    TransactionCategory.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(categoryLabel(cat)) },
                            onClick = { menuExpanded = false; onCategoryChange(transaction.id, cat) }
                        )
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}

/** 转账行：钱从哪出 → 进到哪（transfers 表，与流水合并展示） */
@Composable
fun TransferRow(
    fromName: String,
    toName: String,
    amountCents: Long,
    occurredAt: Long,
    note: String?,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$fromName → $toName",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "转账 · ${formatTime(occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                formatMoney(amountCents),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}
