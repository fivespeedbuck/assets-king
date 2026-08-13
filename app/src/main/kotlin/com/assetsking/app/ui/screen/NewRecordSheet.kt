package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerUiState
import com.assetsking.app.RecordMode
import com.assetsking.database.AccountEntity
import com.assetsking.database.LoanPlanEntity
import com.assetsking.model.AccountType
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.TransactionCategory
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRecordSheet(
    state: LedgerUiState,
    categorize: (String?, String?) -> TransactionCategory,
    onSaveTransaction: (String, String, RecordMode, String, String?, String?, Long, Boolean) -> Unit,
    onSaveTransfer: (String, String, String, String?, Long) -> Unit,
    onSaveLoanDisbursement: (String, String, String?, String?, Long) -> Unit,
    onSaveLoanPayment: (String, String, String, String, String, String, String?, Long) -> Unit,
    onAddAccount: (String, AccountType, String, String?, Int?, Int?, Long) -> Unit,
    onDismiss: () -> Unit,
    loanPlans: List<LoanPlanEntity> = emptyList(),
    customCategoryNames: List<String> = emptyList(),
    initialMode: RecordMode = RecordMode.EXPENSE,
    initialPlanId: String? = null
) {
    var activeTab by remember { mutableStateOf(0) }
    val accounts = state.accounts

    Sheet(title = "记一笔", onDismiss = onDismiss) {
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("记录", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Text("加账户", modifier = Modifier.padding(12.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        when (activeTab) {
            0 -> RecordTab(
                accounts, categorize, onSaveTransaction, onSaveTransfer,
                onSaveLoanDisbursement, onSaveLoanPayment, loanPlans, customCategoryNames,
                initialMode, initialPlanId
            )
            1 -> AddAccountTab { name, type, balance, tail, stmtDay, dueDay, limit ->
                onAddAccount(name, type, balance, tail, stmtDay, dueDay, limit)
                onDismiss()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordTab(
    accounts: List<AccountEntity>,
    categorize: (String?, String?) -> TransactionCategory,
    onSaveTransaction: (String, String, RecordMode, String, String?, String?, Long, Boolean) -> Unit,
    onSaveTransfer: (String, String, String, String?, Long) -> Unit,
    onSaveLoanDisbursement: (String, String, String?, String?, Long) -> Unit,
    onSaveLoanPayment: (String, String, String, String, String, String, String?, Long) -> Unit,
    loanPlans: List<LoanPlanEntity> = emptyList(),
    customCategoryNames: List<String> = emptyList(),
    initialMode: RecordMode = RecordMode.EXPENSE,
    initialPlanId: String? = null
) {
    val allCatNames = com.assetsking.ui.format.allCategories(customCategoryNames)
    var mode by remember { mutableStateOf(initialMode) }
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var occurredAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var accountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var targetId by remember(accounts) { mutableStateOf(accounts.drop(1).firstOrNull()?.id ?: accounts.firstOrNull()?.id.orEmpty()) }
    var categoryStr by remember { mutableStateOf(TransactionCategory.UNCATEGORIZED.name) }
    var categoryWasChosen by remember { mutableStateOf(false) }
    var isReimbursable by remember { mutableStateOf(false) }
    // V5 借款/还款
    var selectedPlan by remember(initialPlanId, loanPlans) { mutableStateOf(loanPlans.firstOrNull { it.id == initialPlanId }) }
    var total by remember { mutableStateOf("") }
    var principal by remember { mutableStateOf("") }
    var interest by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()) }

    LaunchedEffect(merchant, note) {
        if (!categoryWasChosen) categoryStr = categorize(merchant, note).name
    }

    // 贷款还款：选中计划时用最早未还分期预填拆分
    LaunchedEffect(mode, selectedPlan?.id) {
        if (mode == RecordMode.LOAN_PAYMENT) {
            val next = selectedPlan?.let { p ->
                jsonToInstallments(p.installmentsJson).firstOrNull { it.status != InstallmentStatus.PAID }
            }
            total = next?.let { centsStr(it.total.cents) } ?: ""
            principal = next?.let { centsStr(it.principal.cents) } ?: ""
            interest = next?.let { centsStr(it.interest.cents) } ?: ""
            fee = next?.let { centsStr(it.fee.cents) } ?: ""
        }
    }

    ChipRow(
        items = RecordMode.entries,
        selected = mode,
        onSelected = { mode = it },
        label = { it.label }
    )

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("日期", fontWeight = FontWeight.Medium)
        TextButton(onClick = { showDatePicker = true }) {
            Text(dateFormat.format(java.util.Date(occurredAt)))
        }
    }
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = occurredAt)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { occurredAt = it }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) { DatePicker(state = datePickerState) }
    }

    Spacer(Modifier.height(8.dp))
    if (mode != RecordMode.LOAN_PAYMENT) {
        FormField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = "金额", isAmount = true)
    }

    Spacer(Modifier.height(8.dp))
    Text(
        if (mode == RecordMode.TRANSFER) "转出账户" else "账户",
        fontWeight = FontWeight.Medium
    )
    val selectableAccounts = if (mode == RecordMode.LOAN_DISBURSEMENT || mode == RecordMode.LOAN_PAYMENT)
        accounts.filter { it.type == AccountType.ASSET.name } else accounts
    val selectedAccount = selectableAccounts.firstOrNull { it.id == accountId }
    if (selectedAccount != null) {
        ChipRow(
            items = selectableAccounts,
            selected = selectedAccount,
            onSelected = { accountId = it.id },
            label = { it.name },
            id = { it.id }
        )
    }

    when (mode) {
        RecordMode.TRANSFER -> {
            Spacer(Modifier.height(8.dp))
            Text("转入账户", fontWeight = FontWeight.Medium)
            val selectedTarget = accounts.firstOrNull { it.id == targetId }
            if (selectedTarget != null) {
                ChipRow(
                    items = accounts,
                    selected = selectedTarget,
                    onSelected = { targetId = it.id },
                    label = { it.name },
                    id = { it.id }
                )
            }
        }
        RecordMode.LOAN_DISBURSEMENT -> {
            Spacer(Modifier.height(8.dp))
            Text("关联贷款计划（可选）", fontWeight = FontWeight.Medium)
            ChipRow(
                items = listOf<LoanPlanEntity?>(null) + loanPlans,
                selected = selectedPlan,
                onSelected = { selectedPlan = it },
                label = { p ->
                    p?.let {
                        val name = accounts.firstOrNull { a -> a.id == it.accountId }?.name ?: "贷款"
                        val rem = if (it.remainingPrincipalCents > 0) it.remainingPrincipalCents
                        else (it.principalCents - it.earlyRepaidCents).coerceAtLeast(0)
                        "$name · 剩余${formatMoney(rem)}"
                    } ?: "不关联计划"
                },
                id = { it?.id ?: "none" }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (selectedPlan == null) "提示：不关联计划时只记现金，负债会少算" else "借款不是收入：现金+、该计划剩余本金+",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RecordMode.LOAN_PAYMENT -> {
            Spacer(Modifier.height(8.dp))
            Text("关联贷款计划（必选）", fontWeight = FontWeight.Medium)
            val nullablePlans: List<LoanPlanEntity?> = loanPlans
            ChipRow(
                items = nullablePlans,
                selected = selectedPlan,
                onSelected = { selectedPlan = it },
                label = { p ->
                    if (p == null) {
                        "请选择计划"
                    } else {
                        val name = accounts.firstOrNull { a -> a.id == p.accountId }?.name ?: "贷款"
                        val rem = if (p.remainingPrincipalCents > 0) p.remainingPrincipalCents
                        else (p.principalCents - p.earlyRepaidCents).coerceAtLeast(0)
                        "$name · 剩余${formatMoney(rem)}"
                    }
                },
                id = { it?.id ?: "none" }
            )
            if (loanPlans.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("请先到「贷款」页创建贷款计划", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            FormField(value = principal, onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' } }, label = "本金", isAmount = true)
            Spacer(Modifier.height(8.dp))
            FormField(value = interest, onValueChange = { interest = it.filter { c -> c.isDigit() || c == '.' } }, label = "利息（成本）", isAmount = true)
            Spacer(Modifier.height(8.dp))
            FormField(value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() || c == '.' } }, label = "手续费（成本）", isAmount = true)
            Spacer(Modifier.height(4.dp))
            Text(
                "还款不是消费：本金降负债，利息/手续费是真实成本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> {
            Spacer(Modifier.height(8.dp))
            FormField(value = merchant, onValueChange = { merchant = it }, label = "商户/来源")

            if (mode == RecordMode.EXPENSE || mode == RecordMode.REFUND) {
                Spacer(Modifier.height(8.dp))
                Text("分类（自动识别，可修改）", fontWeight = FontWeight.Medium)
                ChipRow(
                    items = allCatNames,
                    selected = categoryStr,
                    onSelected = { categoryStr = it; categoryWasChosen = true },
                    label = { com.assetsking.ui.format.categoryLabelOrName(it, customCategoryNames) },
                    id = { it }
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    FormField(value = note, onValueChange = { note = it }, label = "备注（可选）")

    if (mode != RecordMode.TRANSFER && mode != RecordMode.LOAN_DISBURSEMENT && mode != RecordMode.LOAN_PAYMENT) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = isReimbursable, onCheckedChange = { isReimbursable = it })
            Text("可报销", style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            when (mode) {
                RecordMode.TRANSFER -> onSaveTransfer(accountId, targetId, amount, note, occurredAt)
                RecordMode.LOAN_DISBURSEMENT -> onSaveLoanDisbursement(accountId, amount, selectedPlan?.id, note, occurredAt)
                RecordMode.LOAN_PAYMENT -> selectedPlan?.let { p ->
                    onSaveLoanPayment(accountId, p.id, total, principal, interest, fee, note, occurredAt)
                }
                else -> onSaveTransaction(accountId, amount, mode, categoryStr, merchant, note, occurredAt, isReimbursable)
            }
        },
        enabled = when (mode) {
            RecordMode.TRANSFER -> amount.toDoubleOrNull()?.let { it > 0 } == true && accountId.isNotBlank() && targetId != accountId
            RecordMode.LOAN_DISBURSEMENT -> amount.toDoubleOrNull()?.let { it > 0 } == true && accountId.isNotBlank()
            RecordMode.LOAN_PAYMENT -> selectedPlan != null && accountId.isNotBlank() &&
                principal.toDoubleOrNull()?.let { it >= 0 } == true &&
                interest.toDoubleOrNull()?.let { it >= 0 } == true &&
                fee.toDoubleOrNull()?.let { it >= 0 } == true &&
                (principal.toDoubleOrNull() ?: 0.0) + (interest.toDoubleOrNull() ?: 0.0) + (fee.toDoubleOrNull() ?: 0.0) > 0
            else -> amount.toDoubleOrNull()?.let { it > 0 } == true && accountId.isNotBlank()
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("保存") }
}

private fun centsStr(cents: Long): String = "%.2f".format(cents / 100.0)

@Composable
private fun AddAccountTab(
    onAddAccount: (String, AccountType, String, String?, Int?, Int?, Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.ASSET) }
    var balance by remember { mutableStateOf("") }
    var cardNumber by remember { mutableStateOf("") }
    var statementDay by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("") }
    var creditLimit by remember { mutableStateOf("") }

    val typeItems = AccountType.entries.toList()
    ChipRow(
        items = typeItems,
        selected = type,
        onSelected = { type = it },
        label = { t ->
            when (t) {
                AccountType.ASSET -> "资产（储蓄/借记卡）"
                AccountType.CREDIT -> "信用卡"
                AccountType.LOAN -> "贷款"
            }
        }
    )

    Spacer(Modifier.height(8.dp))
    FormField(value = name, onValueChange = { name = it }, label = "账户名称")

    Spacer(Modifier.height(8.dp))
    FormField(value = balance, onValueChange = { balance = it.filter { c -> c.isDigit() || c == '.' } }, label = "当前余额（可选，可为 0）", isAmount = true)

    Spacer(Modifier.height(8.dp))
    FormField(value = cardNumber, onValueChange = { cardNumber = it.filter { c -> c.isDigit() } }, label = "卡号末四位（可选）")

    if (type == AccountType.CREDIT) {
        Spacer(Modifier.height(8.dp))
        FormField(value = statementDay, onValueChange = { statementDay = it.filter { c -> c.isDigit() }.take(2) }, label = "出账日（1-28）")
        Spacer(Modifier.height(8.dp))
        FormField(value = dueDay, onValueChange = { dueDay = it.filter { c -> c.isDigit() }.take(2) }, label = "还款日（1-31）")
        Spacer(Modifier.height(8.dp))
        FormField(value = creditLimit, onValueChange = { creditLimit = it.filter { c -> c.isDigit() || c == '.' } }, label = "信用额度（可选）", isAmount = true)
    }

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            val openingBalance = balance.ifBlank { "0" }
            val stmtDay = statementDay.toIntOrNull()
            val due = dueDay.toIntOrNull()
            val limit = runCatching {
                java.math.BigDecimal(creditLimit.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
            }.getOrNull() ?: 0L
            onAddAccount(name, type, openingBalance, cardNumber.ifBlank { null }, stmtDay, due, limit)
        },
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("添加账户") }
}
