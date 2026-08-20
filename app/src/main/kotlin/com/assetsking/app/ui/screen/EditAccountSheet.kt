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
    onArchive: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(account.name) }
    var balance by remember { mutableStateOf("%.2f".format(account.balanceCents / 100.0)) }
    var stmtDay by remember { mutableStateOf(account.statementDay?.toString() ?: "") }
    var dueDay by remember { mutableStateOf(account.dueDay?.toString() ?: "") }
    var creditLimit by remember { mutableStateOf(if (account.creditLimitCents > 0) "%.2f".format(account.creditLimitCents / 100.0) else "") }
    var statementDue by remember { mutableStateOf(if (account.statementOriginalDueCents > 0) "%.2f".format(account.statementOriginalDueCents / 100.0) else "") }
    var pending by remember { mutableStateOf(if (account.pendingCents > 0) "%.2f".format(account.pendingCents / 100.0) else "") }
    var cardTail by remember { mutableStateOf(account.cardTail ?: "") }
    var confirmArchive by remember { mutableStateOf(false) }

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
            label = "当前总负债/余额",
            isAmount = true
        )

        // 自动对账靠尾号认卡：银行短信只报「尾号3721…余额657.09」，尾号对得上才敢
        // 拿那个余额盖本地余额。空着就退回手动对账。
        Spacer(Modifier.height(8.dp))
        FormField(
            value = cardTail,
            onValueChange = { cardTail = it.filter(Char::isDigit).take(4) },
            label = "卡号后4位（填了才能用银行短信自动对账）"
        )

        if (account.type == com.assetsking.model.AccountType.CREDIT.name) {
            Spacer(Modifier.height(8.dp))
            FormField(value = statementDue, onValueChange = { statementDue = it.filter { c -> c.isDigit() || c == '.' } }, label = "本期待还（账单原始金额，还款后勿重录，已还部分系统自动扣）", isAmount = true)
            Spacer(Modifier.height(8.dp))
            FormField(value = pending, onValueChange = { pending = it.filter { c -> c.isDigit() || c == '.' } }, label = "Pending 未入账（单独展示，不计总负债）", isAmount = true)
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
                val dueCents = runCatching {
                    java.math.BigDecimal(statementDue.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: 0L
                val pendingCents = runCatching {
                    java.math.BigDecimal(pending.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: 0L
                onSave(account.copy(
                    name = name.trim(), balanceCents = cents,
                    cardTail = cardTail.takeIf { it.length == 4 },
                    statementDay = stmtDay.toIntOrNull(),
                    dueDay = dueDay.toIntOrNull(),
                    creditLimitCents = limit,
                    statementOriginalDueCents = dueCents,
                    pendingCents = pendingCents
                ))
            },
            enabled = name.isNotBlank() && balance.toDoubleOrNull() != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }

        Spacer(Modifier.height(8.dp))
        if (account.balanceCents != 0L) {
            Text(
                "账户余额归零后才能归档，历史流水会继续保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) { Text("余额归零后可归档") }
        } else if (confirmArchive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { confirmArchive = false },
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = { onArchive(account.id) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("确认归档") }
            }
        } else {
            OutlinedButton(
                onClick = { confirmArchive = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("归档账户") }
        }
    }
}
