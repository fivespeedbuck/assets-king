package com.assetsking.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.assetsking.database.AccountEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.app.ui.privacy.LocalPrivacyChaosFrame
import com.assetsking.app.ui.privacy.animatePrivacyValue
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import com.assetsking.ui.format.accountTypeLabel
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.format.transactionCategoryLabel
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.theme.transactionCashFlowColor
import com.assetsking.ui.theme.LoanPrincipalDebtColor
import com.assetsking.ui.theme.RecurringDebitOrange

@Composable
fun AccountRow(account: AccountEntity, onClick: () -> Unit = {}) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyFrame = LocalPrivacyChaosFrame.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (privacyEnabled) privacyObfuscatedText(account.name, 800) else account.name,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge
            )
            val privacyLimit = if (privacyEnabled) privacyFakeAmount(account.name.hashCode()) else null
            val details = if (privacyEnabled) {
                "${privacyObfuscatedText(accountTypeLabel(account.type), 801)} · 出账${privacyFakeCount(802)}日 · 还款${privacyFakeCount(803)}日 · 额度${privacyFakeAmount(804)}"
            } else buildString {
                append(accountTypeLabel(account.type))
                if (account.statementDay != null) append(" · 出账${account.statementDay}日")
                if (account.dueDay != null) append(" · 还款${account.dueDay}日")
                if (account.creditLimitCents > 0) {
                    append(" · 额度")
                    append(privacyLimit ?: "%.0f".format(account.creditLimitCents / 100.0))
                }
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
                if (privacyEnabled) privacyFakeAmount(account.id.hashCode()) else formatMoney(displayCents),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                color = if (!privacyEnabled && displayCents < 0) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurface
            )
            // Credit usage bar
            if (account.type == AccountType.CREDIT.name && account.creditLimitCents > 0) {
                val privacyIndex = Math.floorMod(account.id.hashCode(), privacyFrame.progressFractions.size)
                val usagePct = animatePrivacyValue(
                    if (privacyEnabled) privacyFrame.progressFractions[privacyIndex]
                    else (account.balanceCents.toFloat() / account.creditLimitCents).coerceIn(0f, 1f),
                    "privacy-credit-usage-${account.id}"
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { usagePct },
                    modifier = Modifier.width(60.dp).height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)),
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
    val privacyEnabled = LocalPrivacyEnabled.current
    var menuExpanded by remember { mutableStateOf(false) }
    // 自定义分类（如"转账"）不在枚举里：valueOf 失败时直接显示存储的原始名字，不回退成"未分类"
    val category = runCatching { TransactionCategory.valueOf(transaction.category) }.getOrNull()
    val categoryText = transactionCategoryLabel(transaction.type, transaction.category).orEmpty()
    val linkBadges = transactionLinkBadges(transaction)
    val isLoanTx = transaction.type == "LOAN_DISBURSEMENT" || transaction.type == "LOAN_PAYMENT" || transaction.type == "LOAN_PREPAYMENT"
    // V5：借款/还款/提前还款流水不可编辑分类（与贷款计划联动），只读展示
    val title = when {
        transaction.type == "LOAN_DISBURSEMENT" -> "借款到账"
        transaction.type == "LOAN_PAYMENT" -> "贷款还款"
        transaction.type == "LOAN_PREPAYMENT" -> "提前还款"
        else -> transaction.merchant ?: categoryText.ifBlank { txTypeLabel(transaction.type) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (privacyEnabled) privacyObfuscatedText(title, 820) else title,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    if (privacyEnabled) {
                        "${privacyObfuscatedText(accountName, 821)} · ${privacyObfuscatedText(txTypeLabel(transaction.type), 822)} · ${privacyFakeDateTime(823)}"
                    } else {
                        "$accountName · ${txTypeLabel(transaction.type)} · ${formatTime(transaction.occurredAt)}"
                    },
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
                        if (privacyEnabled) "本金 ${privacyFakeAmount(824)} · 利息 ${privacyFakeAmount(825)} · 费 ${privacyFakeAmount(826)}" else split,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (linkBadges.isNotEmpty()) {
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        linkBadges.forEach { badge ->
                            val badgeColor = if (badge.colorKey == "recurring") RecurringDebitOrange else LoanPrincipalDebtColor
                            Text(
                                badge.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor,
                                modifier = Modifier
                                    .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(50))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Text(
                if (privacyEnabled) privacyFakeAmount(827) else formatMoney(transaction.amountCents),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
                color = transactionCashFlowColor(transaction.type)
            )
        }
        if (!isLoanTx && categoryText.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(
                    if (privacyEnabled) privacyObfuscatedText(categoryText, 828) else categoryText,
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
    val privacyEnabled = LocalPrivacyEnabled.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (privacyEnabled) {
                        "${privacyObfuscatedText(fromName, 850)} → ${privacyObfuscatedText(toName, 851)}"
                    } else {
                        "$fromName → $toName"
                    },
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    if (privacyEnabled) "转账 · ${privacyFakeDateTime(852)}" else "转账 · ${formatTime(occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        if (privacyEnabled) privacyObfuscatedText(it, 853) else it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                if (privacyEnabled) privacyFakeAmount(854) else formatMoney(amountCents),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}
