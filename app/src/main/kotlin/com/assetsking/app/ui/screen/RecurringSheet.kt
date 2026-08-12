package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import java.util.Calendar
import java.util.UUID

private val intervals = listOf("MONTHLY" to "每月", "WEEKLY" to "每周", "DAILY" to "每天", "YEARLY" to "每年")
private val recurringTypes = listOf(TransactionType.EXPENSE to "支出", TransactionType.INCOME to "收入")

@Composable
fun RecurringRulesSection(
    rules: List<RecurringRuleEntity>,
    accounts: List<AccountEntity>,
    onSave: (RecurringRuleEntity) -> Unit,
    onDelete: (String) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<RecurringRuleEntity?>(null) }

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("周期性账单", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Button(onClick = { showSheet = true }) { Text("＋ 新增") }
    }

    Spacer(Modifier.height(8.dp))

    if (rules.isEmpty()) {
        Text("暂无周期性账单", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        rules.forEach { rule ->
            val account = accounts.firstOrNull { it.id == rule.accountId }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(rule.merchant ?: "周期性${rule.type}", fontWeight = FontWeight.Medium)
                    Text(
                        "${account?.name ?: "?"} · ${formatMoney(rule.amountCents)} · ${intervals.first { it.first == rule.interval }.second} · 下次 ${formatTime(rule.nextRunAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { editingRule = rule; showSheet = true }) { Text("编辑") }
                    OutlinedButton(onClick = { onDelete(rule.id) }) { Text("删除") }
                }
            }
            HorizontalDivider()
        }
    }

    if (showSheet) {
        RecurringRuleSheet(
            existing = editingRule,
            accounts = accounts,
            onSave = { onSave(it); showSheet = false; editingRule = null },
            onDismiss = { showSheet = false; editingRule = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringRuleSheet(
    existing: RecurringRuleEntity?,
    accounts: List<AccountEntity>,
    onSave: (RecurringRuleEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var accountId by remember { mutableStateOf(existing?.accountId ?: accounts.firstOrNull()?.id.orEmpty()) }
    var amount by remember { mutableStateOf(existing?.let { "%.2f".format(it.amountCents / 100.0) } ?: "") }
    var type by remember {
        mutableStateOf(
            existing?.let { runCatching { TransactionType.valueOf(it.type) }.getOrDefault(TransactionType.EXPENSE) }
                ?: TransactionType.EXPENSE
        )
    }
    var category by remember {
        mutableStateOf(
            existing?.let { runCatching { TransactionCategory.valueOf(it.category) }.getOrDefault(TransactionCategory.UNCATEGORIZED) }
                ?: TransactionCategory.UNCATEGORIZED
        )
    }
    var merchant by remember { mutableStateOf(existing?.merchant ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var interval by remember { mutableStateOf(existing?.interval ?: "MONTHLY") }
    val defaultNextRun = remember(interval) {
        val cal = Calendar.getInstance()
        when (interval) {
            "DAILY" -> cal.add(Calendar.DAY_OF_MONTH, 1)
            "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            "MONTHLY" -> cal.add(Calendar.MONTH, 1)
            "YEARLY" -> cal.add(Calendar.YEAR, 1)
        }
        cal.timeInMillis
    }
    var nextRunAt by remember { mutableStateOf(existing?.nextRunAt ?: defaultNextRun) }
    var showDatePicker by remember { mutableStateOf(false) }

    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    Sheet(title = if (existing != null) "编辑周期性账单" else "新增周期性账单", onDismiss = onDismiss) {
        if (selectedAccount == null) {
            Text("请先在首页添加账户", color = MaterialTheme.colorScheme.error)
            return@Sheet
        }
        Text("账户", fontWeight = FontWeight.Medium)
        ChipRow(items = accounts, selected = selectedAccount, onSelected = { accountId = it.id }, label = { it.name }, id = { it.id })

        Spacer(Modifier.height(8.dp))
        FormField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = "金额")

        Spacer(Modifier.height(8.dp))
        Text("类型", fontWeight = FontWeight.Medium)
        ChipRow(items = recurringTypes, selected = recurringTypes.first { it.first == type }, onSelected = { type = it.first }, label = { it.second }, id = { it.first.name })

        Spacer(Modifier.height(8.dp))
        Text("分类", fontWeight = FontWeight.Medium)
        ChipRow(items = TransactionCategory.entries, selected = category, onSelected = { category = it }, label = { categoryLabel(it) })

        Spacer(Modifier.height(8.dp))
        FormField(value = merchant, onValueChange = { merchant = it }, label = "商户/说明")

        Spacer(Modifier.height(8.dp))
        Text("周期", fontWeight = FontWeight.Medium)
        ChipRow(items = intervals, selected = intervals.first { it.first == interval }, onSelected = { interval = it.first }, label = { it.second }, id = { it.first })

        Spacer(Modifier.height(8.dp))
        val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("下次执行", fontWeight = FontWeight.Medium)
            TextButton(onClick = { showDatePicker = true }) {
                Text(dateFormat.format(java.util.Date(nextRunAt)))
            }
        }
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = nextRunAt)
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { nextRunAt = it }
                        showDatePicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
            ) { DatePicker(state = datePickerState) }
        }

        Spacer(Modifier.height(8.dp))
        var isSubscription by remember { mutableStateOf(existing?.isSubscription ?: false) }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = isSubscription, onCheckedChange = { isSubscription = it })
            Text("订阅服务（会员/云服务/保险等）", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        FormField(value = note, onValueChange = { note = it }, label = "备注（可选）")

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(amount.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                if (cents <= 0) return@Button
                onSave(
                    RecurringRuleEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        accountId = accountId,
                        amountCents = cents,
                        type = type.name,
                        category = category.name,
                        merchant = merchant.trim().takeIf { it.isNotEmpty() },
                        note = note.trim().takeIf { it.isNotEmpty() },
                        interval = interval,
                        nextRunAt = nextRunAt,
                        isActive = true,
                        isSubscription = isSubscription
                    )
                )
            },
            enabled = amount.toDoubleOrNull()?.let { it > 0 } == true && accountId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}
