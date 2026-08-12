package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.accountTypeLabel

@Composable
fun EditAccountSheet(
    account: AccountEntity,
    onSave: (AccountEntity) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(account.name) }
    var balance by remember { mutableStateOf("%.2f".format(account.balanceCents / 100.0)) }
    var stmtDay by remember { mutableStateOf(account.statementDay?.toString() ?: "") }
    var dueDay by remember { mutableStateOf(account.dueDay?.toString() ?: "") }
    var creditLimit by remember { mutableStateOf(if (account.creditLimitCents > 0) "%.2f".format(account.creditLimitCents / 100.0) else "") }
    var confirmDelete by remember { mutableStateOf(false) }

    Sheet(title = "编辑账户", onDismiss = onDismiss) {
        FormField(value = name, onValueChange = { name = it }, label = "账户名称")

        Spacer(Modifier.height(8.dp))
        Text(
            "类型：${accountTypeLabel(account.type)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
        FormField(
            value = balance,
            onValueChange = { balance = it.filter { c -> c.isDigit() || c == '.' } },
            label = "当前余额",
            isAmount = true
        )

        if (account.type == com.assetsking.model.AccountType.CREDIT.name) {
            Spacer(Modifier.height(8.dp))
            FormField(value = stmtDay, onValueChange = { stmtDay = it.filter(Char::isDigit).take(2) }, label = "出账日（1-28）")
            Spacer(Modifier.height(8.dp))
            FormField(value = dueDay, onValueChange = { dueDay = it.filter(Char::isDigit).take(2) }, label = "还款日（1-31）")
            Spacer(Modifier.height(8.dp))
            FormField(value = creditLimit, onValueChange = { creditLimit = it.filter { c -> c.isDigit() || c == '.' } }, label = "信用额度", isAmount = true)
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(balance.trim())
                        .movePointRight(2)
                        .setScale(0, java.math.RoundingMode.HALF_UP)
                        .longValueExact()
                }.getOrNull() ?: return@Button
                val limit = runCatching {
                    java.math.BigDecimal(creditLimit.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: 0L
                onSave(account.copy(
                    name = name.trim(), balanceCents = cents,
                    statementDay = stmtDay.toIntOrNull(),
                    dueDay = dueDay.toIntOrNull(),
                    creditLimitCents = limit
                ))
            },
            enabled = name.isNotBlank() && balance.toDoubleOrNull() != null,
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
                    onClick = { onDelete(account.id) },
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
            ) { Text("删除账户") }
        }
    }
}
