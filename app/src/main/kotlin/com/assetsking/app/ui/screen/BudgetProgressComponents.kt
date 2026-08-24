package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.app.ui.privacy.LocalPrivacyChaosFrame
import com.assetsking.app.ui.privacy.animatePrivacyValue
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeYearMonth
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.theme.DeficitRed
import java.time.YearMonth
import java.time.ZoneId

internal fun isBudgetOverrun(spentCents: Long, budgetCents: Long): Boolean =
    budgetCents > 0L && spentCents > budgetCents

@Composable
internal fun BudgetProgressLine(
    label: String,
    spentCents: Long,
    budgetCents: Long,
    normalColor: Color,
    privacyIndex: Int,
    roomy: Boolean = false
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val frame = LocalPrivacyChaosFrame.current
    val fractionIndex = Math.floorMod(privacyIndex, frame.progressFractions.size)
    val privacyFraction = animatePrivacyValue(
        frame.progressFractions[fractionIndex],
        "privacy-budget-$privacyIndex"
    )
    val overBudget = !privacyEnabled && isBudgetOverrun(spentCents, budgetCents)
    val semanticColor = if (overBudget) DeficitRed else normalColor
    val progress = if (privacyEnabled) {
        privacyFraction
    } else if (budgetCents > 0L) {
        (spentCents.toFloat() / budgetCents).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(Modifier.padding(vertical = if (roomy) 9.dp else 3.dp)) {
        Text(
            if (overBudget) "$label · 超支 ${formatMoney(spentCents - budgetCents)}" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (overBudget) DeficitRed else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (privacyEnabled) {
                "${privacyFakeAmount(privacyIndex)} / ${privacyFakeAmount(privacyIndex + 1)}"
            } else {
                "${formatMoney(spentCents)} / ${formatMoney(budgetCents)}"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = semanticColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = semanticColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
internal fun BudgetDetailsDialog(
    month: YearMonth,
    budgets: List<BudgetEntity>,
    categories: List<CategoryEntity>,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val zone = ZoneId.systemDefault()
    val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val monthTxs = transactions.filter { it.status == "CONFIRMED" && it.occurredAt in monthStart..monthEnd }
    val refundOffset = monthTxs
        .filter { it.type == "REFUND" && it.refundOfId != null }
        .groupBy { requireNotNull(it.refundOfId) }
        .mapValues { (_, refunds) -> refunds.sumOf { it.amountCents } }
    val expenses = monthTxs.filter { it.type == "EXPENSE" || it.type == "FEE" }
    val monthBudgets = budgets.filter { it.month == month.toString() }

    fun spentFor(budget: BudgetEntity): Long {
        val category = categories.firstOrNull { it.id == budget.category || it.name == budget.category }
        return expenses.filter { expense ->
            expense.category == budget.category ||
                expense.category == category?.id ||
                expense.category == category?.name
        }.sumOf { expense ->
            (expense.amountCents - refundOffset.getOrDefault(expense.id, 0L) - expense.reimbursedCents)
                .coerceAtLeast(0L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (privacyEnabled) "${privacyFakeYearMonth(510)} 全部预算"
                else "${month.year}年${month.monthValue}月 全部预算"
            )
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (monthBudgets.isEmpty()) {
                    Text("本月未设分类预算", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                monthBudgets.forEachIndexed { index, budget ->
                    BudgetProgressLine(
                        label = if (privacyEnabled) {
                            privacyObfuscatedText(budget.category, 520 + index)
                        } else {
                            categories.firstOrNull { it.id == budget.category }?.name ?: budget.category
                        },
                        spentCents = spentFor(budget),
                        budgetCents = budget.monthlyLimitCents,
                        normalColor = MaterialTheme.colorScheme.primary,
                        privacyIndex = 520 + index,
                        roomy = true
                    )
                    if (index < monthBudgets.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
