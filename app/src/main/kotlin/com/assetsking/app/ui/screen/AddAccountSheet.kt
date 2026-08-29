package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.assetsking.model.AccountType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet

@Composable
fun AddAccountSheet(
    initialType: AccountType,
    onAddAccount: (String, AccountType, String, String?, Int?, Int?, Long, (Result<Unit>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    Sheet(title = if (initialType == AccountType.LOAN) "新建贷款账户" else "新建账户", onDismiss = onDismiss) {
        AddAccountForm(
            initialType = initialType,
            onAddAccount = { name, type, balance, tail, statementDay, dueDay, limit, callback ->
                onAddAccount(name, type, balance, tail, statementDay, dueDay, limit) { result ->
                    result.onSuccess { onDismiss() }
                    callback(result)
                }
            }
        )
    }
}

@Composable
private fun AddAccountForm(
    initialType: AccountType,
    onAddAccount: (String, AccountType, String, String?, Int?, Int?, Long, (Result<Unit>) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember(initialType) { mutableStateOf(initialType) }
    var balance by remember { mutableStateOf("") }
    var cardNumber by remember { mutableStateOf("") }
    var statementDay by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("") }
    var creditLimit by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    if (initialType == AccountType.LOAN) {
        Text("贷款账户")
        Text("用于承载消费贷、房贷等普通贷款；不会混入储蓄卡、信用卡或花呗。")
    } else {
        ChipRow(
            items = AccountType.entries,
            selected = type,
            onSelected = { type = it },
            label = {
                when (it) {
                    AccountType.ASSET -> "资产（储蓄/借记卡）"
                    AccountType.CREDIT -> "信用卡"
                    AccountType.LOAN -> "贷款"
                }
            }
        )
    }
    Spacer(Modifier.height(8.dp))
    FormField(value = name, onValueChange = { name = it }, label = "账户名称")
    Spacer(Modifier.height(8.dp))
    FormField(
        value = balance,
        onValueChange = { balance = it.filter { char -> char.isDigit() || char == '.' } },
        label = "启用日余额/欠款（必填，可为 0）",
        isAmount = true
    )
    Spacer(Modifier.height(8.dp))
    FormField(
        value = cardNumber,
        onValueChange = { cardNumber = it.filter(Char::isDigit).take(4) },
        label = "卡号末四位（可选）"
    )
    if (type == AccountType.CREDIT) {
        Spacer(Modifier.height(8.dp))
        FormField(
            value = statementDay,
            onValueChange = { statementDay = it.filter(Char::isDigit).take(2) },
            label = "出账日（1-28）"
        )
        Spacer(Modifier.height(8.dp))
        FormField(
            value = dueDay,
            onValueChange = { dueDay = it.filter(Char::isDigit).take(2) },
            label = "还款日（1-31）"
        )
        Spacer(Modifier.height(8.dp))
        FormField(
            value = creditLimit,
            onValueChange = { creditLimit = it.filter { char -> char.isDigit() || char == '.' } },
            label = "信用额度（可选）",
            isAmount = true
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            val limit = runCatching {
                java.math.BigDecimal(creditLimit.ifBlank { "0" }.trim())
                    .movePointRight(2)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact()
            }.getOrNull() ?: 0L
            onAddAccount(
                name,
                type,
                balance,
                cardNumber.ifBlank { null },
                statementDay.toIntOrNull(),
                dueDay.toIntOrNull(),
                limit
            ) { result -> error = result.exceptionOrNull()?.message }
        },
        enabled = name.isNotBlank() && balance.toDoubleOrNull() != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("添加账户")
    }
    error?.let { Text("失败：$it", color = MaterialTheme.colorScheme.error) }
}
