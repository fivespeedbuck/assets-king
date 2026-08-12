package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney

private val editableTypes = listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.REFUND)

private fun txTypeLabel(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "支出"
    TransactionType.INCOME -> "收入"
    TransactionType.REFUND -> "退款"
    else -> type.name
}

@Composable
fun EditTransactionSheet(
    transaction: TransactionEntity,
    onSave: (String, Long, TransactionType, String, String?, String?) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    recurringRules: List<RecurringRuleEntity> = emptyList(),
    onLinkToRule: (String, String?) -> Unit = { _, _ -> },
    customCategoryNames: List<String> = emptyList()
) {
    val allCats = com.assetsking.ui.format.allCategories(customCategoryNames)
    val currentType = runCatching { TransactionType.valueOf(transaction.type) }
        .getOrDefault(TransactionType.EXPENSE)

    var amount by remember { mutableStateOf("%.2f".format(transaction.amountCents / 100.0)) }
    var type by remember { mutableStateOf(currentType) }
    var categoryStr by remember { mutableStateOf(transaction.category) }
    var merchant by remember { mutableStateOf(transaction.merchant.orEmpty()) }
    var note by remember { mutableStateOf(transaction.note.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    Sheet(title = "编辑流水", onDismiss = onDismiss) {
        FormField(
            value = amount,
            onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
            label = "金额",
            isAmount = true
        )

        Spacer(Modifier.height(8.dp))
        Text("类型", fontWeight = FontWeight.Medium)
        ChipRow(
            items = editableTypes,
            selected = type,
            onSelected = { type = it },
            label = { txTypeLabel(it) },
            id = { it.name }
        )

        Spacer(Modifier.height(8.dp))
        Text("分类", fontWeight = FontWeight.Medium)
        ChipRow(
            items = allCats,
            selected = categoryStr,
            onSelected = { categoryStr = it },
            label = { com.assetsking.ui.format.categoryLabelOrName(it, customCategoryNames) },
            id = { it }
        )

        Spacer(Modifier.height(8.dp))
        FormField(value = merchant, onValueChange = { merchant = it }, label = "商户/来源")

        Spacer(Modifier.height(8.dp))
        FormField(value = note, onValueChange = { note = it }, label = "备注（可选）")

        // 归属周期账单
        if (recurringRules.isNotEmpty()) {
            var selectedRuleId by remember { mutableStateOf(transaction.recurringRuleId) }
            var ruleDropdownExpanded by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Text("归属周期账单", fontWeight = FontWeight.Medium)
            val linkedRuleName = recurringRules.firstOrNull { it.id == selectedRuleId }?.let {
                "${it.merchant ?: "未命名"} (${formatMoney(it.amountCents)})"
            } ?: "不关联"
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { ruleDropdownExpanded = true }) {
                    Text(linkedRuleName, style = MaterialTheme.typography.bodyMedium)
                }
            }
            DropdownMenu(expanded = ruleDropdownExpanded, onDismissRequest = { ruleDropdownExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("不关联") },
                    onClick = {
                        selectedRuleId = null
                        ruleDropdownExpanded = false
                        onLinkToRule(transaction.id, null)
                    }
                )
                recurringRules.forEach { rule ->
                    DropdownMenuItem(
                        text = { Text("${rule.merchant ?: "未命名"} ${formatMoney(rule.amountCents)}") },
                        onClick = {
                            selectedRuleId = rule.id
                            ruleDropdownExpanded = false
                            onLinkToRule(transaction.id, rule.id)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(amount.trim())
                        .movePointRight(2)
                        .setScale(0, java.math.RoundingMode.HALF_UP)
                        .longValueExact()
                }.getOrNull() ?: return@Button
                if (cents <= 0) return@Button
                onSave(
                    transaction.id,
                    cents,
                    type,
                    categoryStr,
                    merchant.trim().takeIf { it.isNotEmpty() },
                    note.trim().takeIf { it.isNotEmpty() }
                )
            },
            enabled = amount.toDoubleOrNull()?.let { it > 0 } == true,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }

        Spacer(Modifier.height(8.dp))
        if (confirmDelete) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = { onDelete(transaction.id) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("确认删除") }
            }
        } else {
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("删除流水") }
        }
    }
}
