package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.database.WindfallEntity
import com.assetsking.model.AccountType
import com.assetsking.model.WindfallStatus
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.formatMoney
import java.util.UUID

private val Green = Color(0xFF66BB6A)

/** 年终奖 Windfall 管理：EXPECTED 不算现金；到账后才记收入（铁律 8） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindfallSheet(
    windfalls: List<WindfallEntity>,
    accounts: List<AccountEntity>,
    currentTotalDebtCents: Long,
    onSave: (WindfallEntity) -> Unit,
    onDelete: (String) -> Unit,
    onMarkReceived: (String, Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    var showForm by remember { mutableStateOf(false) }
    var receiving by remember { mutableStateOf<WindfallEntity?>(null) }

    Sheet(title = "年终奖", onDismiss = onDismiss) {
        if (windfalls.isEmpty() && !showForm) {
            Text("还没有年终奖记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        windfalls.forEach { wf ->
            WindfallRow(
                wf = wf,
                currentTotalDebtCents = currentTotalDebtCents,
                onReceive = { receiving = wf },
                onDelete = { onDelete(wf.id) }
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showForm = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("＋ 新增年终奖") }
    }

    if (showForm) {
        WindfallFormSheet(
            onSave = { onSave(it); showForm = false },
            onDismiss = { showForm = false }
        )
    }

    receiving?.let { wf ->
        ReceiveSheet(
            wf = wf,
            accounts = accounts.filter { it.type == AccountType.ASSET.name },
            onConfirm = { actualCents, cashAccountId ->
                onMarkReceived(wf.id, actualCents, cashAccountId)
                receiving = null
            },
            onDismiss = { receiving = null }
        )
    }
}

@Composable
private fun WindfallRow(
    wf: WindfallEntity,
    currentTotalDebtCents: Long,
    onReceive: () -> Unit,
    onDelete: () -> Unit
) {
    val status = runCatching { WindfallStatus.valueOf(wf.status) }.getOrDefault(WindfallStatus.EXPECTED)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(wf.name, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                when (status) {
                    WindfallStatus.EXPECTED -> {
                        Text("预计 ${formatMoney(wf.expectedAmountCents)} · 计划还债 ${formatMoney(wf.plannedDebtPaymentCents)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(
                            "到账后总负债预计从 ${formatMoney(currentTotalDebtCents)} 降到 ${formatMoney(maxOf(0, currentTotalDebtCents - wf.plannedDebtPaymentCents))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    WindfallStatus.RECEIVED -> {
                        Text("已到账 ${formatMoney(wf.receivedAmountCents)}", style = MaterialTheme.typography.bodySmall, color = Green)
                        Text("到账已记入收入；请在记账页手动执行还款", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WindfallStatus.CANCELLED -> {
                        Text("已取消", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (status == WindfallStatus.EXPECTED) {
                TextButton(onClick = onReceive) { Text("到账") }
            }
            TextButton(onClick = onDelete) { Text("删", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindfallFormSheet(
    onSave: (WindfallEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var expectedAmount by remember { mutableStateOf("") }
    var plannedPayment by remember { mutableStateOf("") }
    var expectedDate by remember { mutableStateOf(java.time.LocalDate.now().toEpochDay()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()) }

    Sheet(title = "新增年终奖", onDismiss = onDismiss) {
        FormField(value = name, onValueChange = { name = it }, label = "名称（如 2026年终奖）")
        Spacer(Modifier.height(8.dp))
        FormField(value = expectedAmount, onValueChange = { expectedAmount = it.filter { c -> c.isDigit() || c == '.' } }, label = "预计金额", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = plannedPayment, onValueChange = { plannedPayment = it.filter { c -> c.isDigit() || c == '.' } }, label = "计划用于还债金额", isAmount = true)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("预计到账日期", fontWeight = FontWeight.Medium)
            TextButton(onClick = { showDatePicker = true }) { Text(dateFormat.format(java.time.LocalDate.ofEpochDay(expectedDate).let { java.time.LocalDateTime.of(it, java.time.LocalTime.MIDNIGHT).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() })) }
        }
        if (showDatePicker) {
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = java.time.LocalDate.ofEpochDay(expectedDate)
                    .let { java.time.LocalDateTime.of(it, java.time.LocalTime.MIDNIGHT).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let {
                            expectedDate = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                        }
                        showDatePicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
            ) { DatePicker(state = pickerState) }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val expectedCents = runCatching {
                    java.math.BigDecimal(expectedAmount.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                val plannedCents = runCatching {
                    java.math.BigDecimal(plannedPayment.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: 0L
                onSave(
                    WindfallEntity(
                        id = UUID.randomUUID().toString(),
                        name = name.trim().ifBlank { "年终奖" },
                        expectedAmountCents = expectedCents,
                        expectedDateEpochDay = expectedDate,
                        plannedDebtPaymentCents = plannedCents,
                        status = WindfallStatus.EXPECTED.name
                    )
                )
            },
            enabled = expectedAmount.toDoubleOrNull()?.let { it > 0 } == true,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}

@Composable
private fun ReceiveSheet(
    wf: WindfallEntity,
    accounts: List<AccountEntity>,
    onConfirm: (Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    var actualAmount by remember { mutableStateOf("%.2f".format(wf.expectedAmountCents / 100.0)) }
    var cashAccountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }

    Sheet(title = "年终奖到账", onDismiss = onDismiss) {
        Text("到账后：现金 +金额、收入 +金额（铁律：到账前不算现金）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FormField(value = actualAmount, onValueChange = { actualAmount = it.filter { c -> c.isDigit() || c == '.' } }, label = "实际到账金额", isAmount = true)
        Spacer(Modifier.height(8.dp))
        Text("到账账户", fontWeight = FontWeight.Medium)
        val selected = accounts.firstOrNull { it.id == cashAccountId }
        if (selected != null) {
            ChipRow(
                items = accounts,
                selected = selected,
                onSelected = { cashAccountId = it.id },
                label = { it.name },
                id = { it.id }
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(actualAmount.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                onConfirm(cents, cashAccountId)
            },
            enabled = actualAmount.toDoubleOrNull()?.let { it > 0 } == true && cashAccountId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("确认到账") }
    }
}
