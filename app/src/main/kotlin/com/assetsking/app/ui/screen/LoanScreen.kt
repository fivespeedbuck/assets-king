package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import com.assetsking.ui.component.FormField
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
import com.assetsking.database.LoanPlanEntity
import com.assetsking.ledger.LoanCalculator
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.LoanPlan
import com.assetsking.model.Money
import com.assetsking.model.RepaymentMethod
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.formatMoney
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ── JSON helpers ──

private fun installmentsToJson(list: List<LoanInstallment>): String =
    JSONArray().apply {
        list.forEach { inst ->
            put(JSONObject().apply {
                put("number", inst.number)
                put("dueDateEpochDay", inst.dueDateEpochDay)
                put("principal", inst.principal.cents)
                put("interest", inst.interest.cents)
                put("fee", inst.fee.cents)
                put("status", inst.status.name)
            })
        }
    }.toString()

private fun jsonToInstallments(json: String): List<LoanInstallment> =
    runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            LoanInstallment(
                number = obj.getInt("number"),
                dueDateEpochDay = obj.getLong("dueDateEpochDay"),
                principal = Money(obj.getLong("principal")),
                interest = Money(obj.getLong("interest")),
                fee = Money(obj.getLong("fee")),
                status = runCatching { InstallmentStatus.valueOf(obj.getString("status")) }.getOrDefault(InstallmentStatus.UPCOMING)
            )
        }
    }.getOrDefault(emptyList())

// ── Main Screen ──

@Composable
fun LoanScreen(
    plans: List<LoanPlanEntity>,
    accounts: List<AccountEntity>,
    onSave: (LoanPlanEntity) -> Unit,
    onDelete: (String) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf<LoanPlanEntity?>(null) }

    // 负债仪表盘
    val now = System.currentTimeMillis()
    val accountDebts = accounts.filter { it.type != com.assetsking.model.AccountType.ASSET.name }.sumOf { it.balanceCents }
    val loanPrincipalRemaining = plans.sumOf { p ->
        if (p.remainingPrincipalCents > 0) p.remainingPrincipalCents
        else (p.principalCents - p.earlyRepaidCents).coerceAtLeast(0)
    }
    val totalDebts = accountDebts + loanPrincipalRemaining
    // 贷款月供
    val loanMonthlyRepay = plans.sumOf { plan ->
        val unpaid = jsonToInstallments(plan.installmentsJson).filter { it.status != InstallmentStatus.PAID }
        if (unpaid.isNotEmpty()) unpaid.first().total.cents else 0L
    }
    // 信用卡应还（取余额，简化：全部待还就是当前余额）
    val creditCardDebts = accounts.filter { it.type == com.assetsking.model.AccountType.CREDIT.name }.sumOf { it.balanceCents }
    // 每月总计待还 = 贷款月供 + 信用卡（按最低还款10%估算）
    val monthlyRepay = loanMonthlyRepay + (creditCardDebts / 10)
    val debtRatio = if (totalDebts > 0) (monthlyRepay * 100 / (totalDebts / 12).coerceAtLeast(1)) else 0L
    // 扣款日汇总：哪天扣多少
    data class DueItem(val label: String, val day: Int, val amount: Long)
    val dueItems = buildList {
        accounts.filter { it.type == com.assetsking.model.AccountType.CREDIT.name && it.dueDay != null }.forEach { acc ->
            add(DueItem(acc.name, acc.dueDay!!, acc.balanceCents))
        }
        accounts.filter { it.type == com.assetsking.model.AccountType.LOAN.name }.forEach { acc ->
            if (acc.balanceCents > 0) add(DueItem(acc.name, acc.dueDay ?: 1, acc.balanceCents))
        }
        plans.forEach { plan ->
            if (plan.repaymentDay != null) {
                val unpaidInsts = jsonToInstallments(plan.installmentsJson).filter { it.status != InstallmentStatus.PAID }
                val monthlyAmount = if (unpaidInsts.isNotEmpty()) unpaidInsts.first().total.cents else 0L
                val rDay = plan.repaymentDay
                if (monthlyAmount > 0 && rDay != null) add(DueItem(accounts.firstOrNull { it.id == plan.accountId }?.name ?: "贷款", rDay, monthlyAmount))
            } else {
                val insts = jsonToInstallments(plan.installmentsJson)
                insts.filter { it.dueDateEpochDay > java.time.LocalDate.now().toEpochDay() }.take(3).forEach { inst ->
                    val date = java.time.LocalDate.ofEpochDay(inst.dueDateEpochDay)
                    val label = accounts.firstOrNull { it.id == plan.accountId }?.name ?: "贷款"
                    add(DueItem("$label 第${inst.number}期", date.dayOfMonth, inst.total.cents))
                }
            }
        }
    }.sortedBy { it.day }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 负债仪表盘
        if (totalDebts > 0) {
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("负债仪表盘", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("负债总额", style = MaterialTheme.typography.bodyMedium)
                        Text(formatMoney(totalDebts), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("每月总计待还", style = MaterialTheme.typography.bodyMedium)
                        Text(formatMoney(monthlyRepay), fontWeight = FontWeight.Bold)
                    }
                    if (dueItems.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("每月扣款日", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        dueItems.forEach { item ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("每月${item.day}日  ${item.label}", style = MaterialTheme.typography.bodySmall)
                                Text(formatMoney(item.amount), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("贷款计划", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { showSheet = true }) { Text("＋ 新增") }
            }
        }
        if (plans.isEmpty()) {
            item { Text("暂无贷款计划", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(plans, key = { it.id }) { plan ->
                val account = accounts.firstOrNull { it.id == plan.accountId }
                val installments = jsonToInstallments(plan.installmentsJson)
                val summary = LoanCalculator.summarize(
                    LoanPlan(
                        id = plan.id, accountId = plan.accountId,
                        principal = Money(plan.principalCents),
                        startDateEpochDay = plan.startDateEpochDay,
                        repaymentMethod = runCatching { RepaymentMethod.valueOf(plan.repaymentMethod) }.getOrDefault(RepaymentMethod.CUSTOM),
                        installments = installments
                    )
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(account?.name ?: "未知账户", fontWeight = FontWeight.Medium)
                        Text("本金 ${formatMoney(plan.principalCents)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    val rateInfo = if (plan.annualRateBps > 0) " · 利率 ${"%.2f".format(plan.annualRateBps / 100.0)}%" else ""
                    val remaining = if (plan.remainingPrincipalCents > 0) plan.remainingPrincipalCents else plan.principalCents
                    val remainingInfo = " · 剩 ${formatMoney(remaining)}"
                    val earlyInfo = if (plan.earlyRepaidCents > 0) " · 提前还 ${formatMoney(plan.earlyRepaidCents)}" else ""
                    val rDay = if (plan.repaymentDay != null) " · ${plan.repaymentDay}日还" else ""
                    val paidCount = installments.count { it.status == InstallmentStatus.PAID }
                    val paidInfo = if (paidCount > 0) " / 已还${paidCount}期" else ""
                    Text(
                        "${installments.size}期${paidInfo}${rateInfo}${remainingInfo}${earlyInfo}${rDay} · 总还款 ${formatMoney(summary.totalRepayment.cents)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 还款计划展开
                    var expanded by remember { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起还款计划 ▲" else "查看还款计划 ▼", style = MaterialTheme.typography.labelSmall)
                    }
                    if (expanded && installments.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                            installments.take(12).forEach { inst ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    val prefix = if (inst.status == InstallmentStatus.PAID) "✅" else "○"
                                    Text("$prefix 第${inst.number}期", style = MaterialTheme.typography.bodySmall)
                                    Text("本金${formatMoney(inst.principal.cents)} + 利息${formatMoney(inst.interest.cents)}", style = MaterialTheme.typography.bodySmall)
                                    Text(formatMoney(inst.total.cents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                            if (installments.size > 12) {
                                Text("...共 ${installments.size} 期", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // 提前还款计算
                            var extraPayment by remember { mutableStateOf("") }
                            var earlyResult by remember { mutableStateOf<com.assetsking.ledger.EarlyRepaymentResult?>(null) }
                            Spacer(Modifier.height(4.dp))
                            Text("提前还款计算", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                FormField(value = extraPayment, onValueChange = { extraPayment = it.filter { c -> c.isDigit() || c == '.' } }, label = "额外还款金额", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    val extra = runCatching { java.math.BigDecimal(extraPayment.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                                    if (extra != null && extra > 0 && plan.annualRateBps > 0) {
                                        val remainingPrincipal = if (plan.remainingPrincipalCents > 0) plan.remainingPrincipalCents else plan.principalCents
                                        earlyResult = LoanCalculator.earlyRepaymentSavings(
                                            remainingPrincipalCents = remainingPrincipal,
                                            annualRateBps = plan.annualRateBps,
                                            remainingMonths = installments.size,
                                            extraPaymentCents = extra
                                        )
                                    }
                                }) { Text("计算") }
                            }
                            earlyResult?.let { r ->
                                Text("节省利息 ${formatMoney(r.savedInterest)} · 缩短 ${installments.size - r.newRemainingMonths} 个月", style = MaterialTheme.typography.labelSmall, color = Color(0xFF66BB6A))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            editingPlan = plan
                            showSheet = true
                        }) { Text("编辑") }
                        OutlinedButton(onClick = { onDelete(plan.id) }) { Text("删除") }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (showSheet) {
        LoanPlanSheet(
            existingPlan = editingPlan,
            accounts = accounts,
            onSave = { onSave(it); showSheet = false; editingPlan = null },
            onDismiss = { showSheet = false; editingPlan = null }
        )
    }
}

@Composable
private fun LoanPlanSheet(
    existingPlan: LoanPlanEntity?,
    accounts: List<AccountEntity>,
    onSave: (LoanPlanEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = existingPlan != null
    val existingInstallments = remember(existingPlan) {
        existingPlan?.let { jsonToInstallments(it.installmentsJson) } ?: emptyList()
    }

    var accountId by remember { mutableStateOf(existingPlan?.accountId ?: accounts.firstOrNull()?.id.orEmpty()) }
    var principal by remember { mutableStateOf(existingPlan?.let { "%.2f".format(it.principalCents / 100.0) } ?: "") }
    var installmentCount by remember { mutableStateOf(existingInstallments.size.toString()) }
    var method by remember {
        mutableStateOf(
            existingPlan?.let { runCatching { RepaymentMethod.valueOf(it.repaymentMethod) }.getOrDefault(RepaymentMethod.CUSTOM) }
                ?: RepaymentMethod.CUSTOM
        )
    }

    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    var paidMonths by remember { mutableStateOf("") }

    Sheet(title = if (isEdit) "编辑贷款计划" else "新增贷款计划", onDismiss = onDismiss) {
        if (selectedAccount == null) {
            Text("请先在首页添加账户", color = MaterialTheme.colorScheme.error)
            return@Sheet
        }
        Text("关联账户", fontWeight = FontWeight.Medium)
        ChipRow(
            items = accounts,
            selected = selectedAccount,
            onSelected = { accountId = it.id },
            label = { it.name },
            id = { it.id }
        )

        Spacer(Modifier.height(8.dp))
        FormField(value = principal, onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' } }, label = "贷款本金")

        Spacer(Modifier.height(8.dp))
        FormField(value = installmentCount, onValueChange = { installmentCount = it.filter(Char::isDigit) }, label = "总期数（可选）")
        FormField(value = paidMonths, onValueChange = { paidMonths = it.filter(Char::isDigit) }, label = "已还期数（0=全部待还）")

        var rateStr by remember { mutableStateOf(existingPlan?.let { "%.2f".format(it.annualRateBps / 100.0) } ?: "") }
        Spacer(Modifier.height(8.dp))
        FormField(value = rateStr, onValueChange = { rateStr = it.filter { c -> c.isDigit() || c == '.' } }, label = "年利率%（可选）")

        var remainingStr by remember { mutableStateOf(existingPlan?.let { if (it.remainingPrincipalCents > 0) "%.2f".format(it.remainingPrincipalCents / 100.0) else "" } ?: "") }
        Spacer(Modifier.height(8.dp))
        FormField(value = remainingStr, onValueChange = { remainingStr = it.filter { c -> c.isDigit() || c == '.' } }, label = "剩余本金（可选）")
        var earlyRepaidStr by remember { mutableStateOf(existingPlan?.let { if (it.earlyRepaidCents > 0) "%.2f".format(it.earlyRepaidCents / 100.0) else "" } ?: "") }
        FormField(value = earlyRepaidStr, onValueChange = { earlyRepaidStr = it.filter { c -> c.isDigit() || c == '.' } }, label = "已提前还款（可选）")
        var repayDayStr by remember { mutableStateOf(existingPlan?.repaymentDay?.toString() ?: "") }
        FormField(value = repayDayStr, onValueChange = { repayDayStr = it.filter(Char::isDigit).take(2) }, label = "每月还款日（几号，可选）")

        Spacer(Modifier.height(8.dp))
        Text("还款方式", fontWeight = FontWeight.Medium)
        ChipRow(
            items = RepaymentMethod.entries,
            selected = method,
            onSelected = { method = it },
            label = { m ->
                when (m) {
                    RepaymentMethod.CUSTOM -> "自定义"
                    RepaymentMethod.EQUAL_PAYMENT -> "等额本息"
                    RepaymentMethod.EQUAL_PRINCIPAL -> "等额本金"
                    RepaymentMethod.INTEREST_ONLY -> "先息后本"
                }
            },
            id = { it.name }
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(principal.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                if (cents <= 0) return@Button

                val rateBps = (rateStr.toDoubleOrNull()?.times(100))?.toInt() ?: 0
                val remaining = runCatching {
                    java.math.BigDecimal(remainingStr.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: 0L
                // Auto-generate repayment schedule if rate and count specified
                val count = installmentCount.toIntOrNull() ?: 0
                val paid = paidMonths.toIntOrNull() ?: 0
                val schedule = if (rateBps > 0 && count > 0 && method != RepaymentMethod.CUSTOM) {
                    val raw = when (method) {
                        RepaymentMethod.EQUAL_PAYMENT -> LoanCalculator.equalPaymentSchedule(cents, rateBps, count, java.time.LocalDate.now().toEpochDay())
                        RepaymentMethod.EQUAL_PRINCIPAL -> LoanCalculator.equalPrincipalSchedule(cents, rateBps, count, java.time.LocalDate.now().toEpochDay())
                        RepaymentMethod.INTEREST_ONLY -> LoanCalculator.interestOnlySchedule(cents, rateBps, count, java.time.LocalDate.now().toEpochDay())
                        else -> emptyList()
                    }
                    raw.mapIndexed { idx, inst ->
                        if (idx < paid) inst.copy(status = InstallmentStatus.PAID) else inst
                    }
                } else existingInstallments.mapIndexed { idx, inst ->
                    if (idx < paid) inst.copy(status = InstallmentStatus.PAID) else inst
                }
                onSave(
                    LoanPlanEntity(
                        id = existingPlan?.id ?: UUID.randomUUID().toString(),
                        accountId = accountId,
                        principalCents = cents,
                        startDateEpochDay = java.time.LocalDate.now().toEpochDay(),
                        repaymentMethod = method.name,
                        installmentsJson = installmentsToJson(schedule),
                        annualRateBps = rateBps,
                        remainingPrincipalCents = if (remaining > 0) remaining else cents,
                        earlyRepaidCents = runCatching { java.math.BigDecimal(earlyRepaidStr.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: 0L,
                        repaymentDay = repayDayStr.toIntOrNull()
                    )
                )
            },
            enabled = principal.toDoubleOrNull()?.let { it > 0 } == true && accountId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}
