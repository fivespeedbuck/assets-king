package com.assetsking.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.LoanPlanEntity
import com.assetsking.ledger.LoanCalculator
import com.assetsking.ledger.V5Metrics
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.LoanInstallment
import com.assetsking.model.LoanPlan
import com.assetsking.model.Money
import com.assetsking.model.RepaymentMethod
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.theme.IncomeGreen
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ── JSON helpers ──

internal fun installmentsToJson(list: List<LoanInstallment>): String =
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

internal fun jsonToInstallments(json: String): List<LoanInstallment> =
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
    onDelete: (String) -> Unit,
    v5: V5Metrics? = null,
    cardInstallments: List<CreditCardInstallmentEntity> = emptyList(),
    onSaveInstallment: (CreditCardInstallmentEntity) -> Unit = {},
    onDeleteInstallment: (String) -> Unit = {},
    onRecordPayment: (LoanPlanEntity) -> Unit = {},
    onPrepay: (String, String, Long, String?) -> Unit = { _, _, _, _ -> },
    onSettle: (String, String, Long, Long, Long, String?) -> Unit = { _, _, _, _, _, _ -> },
    onUpdateInstallment: (String, Int, Long?, Long?, Long?, String?) -> Unit = { _, _, _, _, _, _ -> },
    transactions: List<com.assetsking.database.TransactionEntity> = emptyList(),
    addPulse: Int = 0
) {
    var showSheet by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf<LoanPlanEntity?>(null) }
    var showInstallmentSheet by remember { mutableStateOf(false) }
    var prepaying by remember { mutableStateOf<LoanPlanEntity?>(null) }
    var settling by remember { mutableStateOf<LoanPlanEntity?>(null) }
    var showSettled by remember { mutableStateOf(false) }
    // 手风琴一次只展开一笔（REQ 贷款页§14）；完整计划与单期编辑
    var expandedPlanId by remember { mutableStateOf<String?>(null) }
    var fullPlan by remember { mutableStateOf<LoanPlanEntity?>(null) }
    var editingInstallment by remember { mutableStateOf<Pair<LoanPlanEntity, LoanInstallment>?>(null) }
    var deleteConfirm by remember { mutableStateOf<LoanPlanEntity?>(null) }

    // 右下角悬浮＋（REQ 贷款页§16）触发新增
    LaunchedEffect(addPulse) {
        if (addPulse > 0) { editingPlan = null; showSheet = true }
    }

    // 扣款日汇总：哪天扣多少（信用卡按本期待还，贷款按计划）
    data class DueItem(val label: String, val day: Int, val amount: Long)
    val dueItems = buildList {
        accounts.filter { it.type == com.assetsking.model.AccountType.CREDIT.name && it.dueDay != null }.forEach { acc ->
            // 统一口径：剩余应还（原始账单−已还），与首页/必须还一致
            val remaining = v5?.cardRemainingDueByCard?.get(acc.id) ?: acc.statementOriginalDueCents
            if (remaining > 0) add(DueItem(acc.name, acc.dueDay!!, remaining))
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

    // 本月还款列表（REQ 贷款页§2-3/§11）：逾期置顶 → 本月待还 → 本月已还（删除线）
    val activePlans = plans.filter { it.status != "PAID_OFF" }
    val todayDay = java.time.LocalDate.now().toEpochDay()
    val monthStartDay = java.time.YearMonth.now().atDay(1).toEpochDay()
    val monthEndDay = java.time.YearMonth.now().atEndOfMonth().toEpochDay()
    val zone = java.time.ZoneId.systemDefault()
    val monthStartMillis = java.time.YearMonth.now().atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEndMillis = java.time.YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    data class RepayRow(val label: String, val plan: LoanPlanEntity, val amount: Long, val dueDay: Long, val paid: Boolean)
    val repayRows = buildList {
        activePlans.forEach { plan ->
            val accName = accounts.firstOrNull { it.id == plan.accountId }?.name ?: "贷款"
            jsonToInstallments(plan.installmentsJson).forEach { inst ->
                when {
                    inst.status == InstallmentStatus.PAID && inst.dueDateEpochDay in monthStartDay..monthEndDay ->
                        add(RepayRow("$accName 第${inst.number}期", plan, inst.total.cents, inst.dueDateEpochDay, true))
                    inst.status != InstallmentStatus.PAID && inst.dueDateEpochDay <= monthEndDay ->
                        add(RepayRow("$accName 第${inst.number}期", plan, inst.total.cents, inst.dueDateEpochDay, false))
                }
            }
        }
    }.sortedWith(compareBy({ it.paid }, { it.dueDay }))

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "负债仪表盘",
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        }
        // V5 负债仪表盘（统一口径，来自 GetV5MetricsUseCase）
        item {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (v5 != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("当前总负债", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(v5.totalDebtCents), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本月必须还款", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(v5.mustRepayCents), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本月净降债", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (v5.netDebtReductionCents >= 0) "+${formatMoney(v5.netDebtReductionCents)}" else formatMoney(v5.netDebtReductionCents),
                            fontWeight = FontWeight.Bold,
                            color = if (v5.netDebtReductionCents > 0) IncomeGreen else MaterialTheme.colorScheme.error
                        )
                    }
                    // 剩余本金/剩余利息/本月已还（REQ 贷款页§10）
                    val remainP = activePlans.sumOf { if (it.remainingPrincipalCents > 0) it.remainingPrincipalCents else it.principalCents }
                    val remainI = activePlans.sumOf { p ->
                        jsonToInstallments(p.installmentsJson).filter { it.status != InstallmentStatus.PAID }.sumOf { it.interest.cents }
                    }
                    val monthPaid = transactions.filter { it.type == "LOAN_PAYMENT" && it.occurredAt in monthStartMillis..monthEndMillis }.sumOf { it.amountCents }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("剩余本金 / 剩余利息", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${formatMoney(remainP)} / ${formatMoney(remainI)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本月已还", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(monthPaid), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = IncomeGreen)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "${v5TrendLabel(v5.trend)} · ${v5StageLabel(v5.stage)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
        }

        // 还款成果（REQ 年终奖还债§6）：累计已还本金/已付利息/已付手续费/已结清项目
        item {
            val loanPayments = transactions.filter { it.type == "LOAN_PAYMENT" }
            val paidPrincipal = loanPayments.sumOf { if (it.principalCents > 0) it.principalCents else it.amountCents }
            val paidInterest = loanPayments.sumOf { it.interestCents }
            val paidFee = loanPayments.sumOf { it.feeCents }
            val settledCount = plans.count { it.status == "PAID_OFF" }
            GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("还款成果", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "已还本金 ${formatMoney(paidPrincipal)} · 已付利息 ${formatMoney(paidInterest)} · 已付手续费 ${formatMoney(paidFee)} · 已结清 ${settledCount} 笔",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (dueItems.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("每月扣款日", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        dueItems.forEachIndexed { index, due ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("每月${due.day}日  ${due.label}", style = MaterialTheme.typography.bodySmall)
                                Text(formatMoney(due.amount), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // ── 本月还款列表（REQ 贷款页§2-3/§10-11）：逾期置顶 → 本月待还 → 已还删除线 ──
        if (repayRows.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("本月还款", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        repayRows.forEach { r ->
                            val overdue = !r.paid && r.dueDay < todayDay
                            val dueDate = java.time.LocalDate.ofEpochDay(r.dueDay)
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !r.paid) { onRecordPayment(r.plan) }.padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    (if (r.paid) "已还 · " else if (overdue) "逾期 · " else "待还 · ") + r.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (overdue) FontWeight.Bold else null,
                                    color = when { overdue -> MaterialTheme.colorScheme.error; r.paid -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface },
                                    textDecoration = if (r.paid) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${dueDate.monthValue}月${dueDate.dayOfMonth}日", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    formatMoney(r.amount),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = when { overdue -> MaterialTheme.colorScheme.error; r.paid -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface },
                                    textDecoration = if (r.paid) TextDecoration.LineThrough else null
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 新增入口 = 右下角悬浮＋（REQ 贷款页§16），这里只留标题
                Text("贷款计划", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (plans.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        Text("暂无贷款计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            // 已结清移入底部折叠分组（REQ 贷款页§13/§17）
            val settledPlans = plans.filter { it.status == "PAID_OFF" }
            items(activePlans, key = { it.id }) { plan ->
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
                GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (plan.status == "PAID_OFF") "${account?.name ?: "未知账户"} · 已结清" else account?.name ?: "未知账户",
                            fontWeight = FontWeight.Medium,
                            color = if (plan.status == "PAID_OFF") IncomeGreen else MaterialTheme.colorScheme.onSurface
                        )
                        Text("本金 ${formatMoney(plan.principalCents)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    val rateInfo = if (plan.annualRateBps > 0) " · 利率 ${"%.2f".format(plan.annualRateBps / 100.0)}%" else ""
                    val remaining = if (plan.remainingPrincipalCents > 0) plan.remainingPrincipalCents else plan.principalCents
                    val remainingInfo = " · 剩 ${formatMoney(remaining)}"
                    val earlyInfo = if (plan.earlyRepaidCents > 0) " · 提前还 ${formatMoney(plan.earlyRepaidCents)}" else ""
                    val rDay = if (plan.repaymentDay != null) " · ${plan.repaymentDay}日还" else ""
                    val paidCount = installments.count { it.status == InstallmentStatus.PAID }
                    val paidInfo = if (paidCount > 0) " / 已还${paidCount}期" else ""
                    // 下一还款日（REQ 贷款页§13）
                    val nextDue = installments.firstOrNull { it.status != InstallmentStatus.PAID }?.dueDateEpochDay
                        ?.let { d -> " · 下次 ${java.time.LocalDate.ofEpochDay(d).monthValue}月${java.time.LocalDate.ofEpochDay(d).dayOfMonth}日" } ?: ""
                    Text(
                        "${installments.size}期${paidInfo}${rateInfo}${remainingInfo}${earlyInfo}${rDay}${nextDue} · 总还款 ${formatMoney(summary.totalRepayment.cents)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 手风琴原地展开，一次一笔（REQ 贷款页§14）
                    val expanded = expandedPlanId == plan.id
                    TextButton(onClick = { expandedPlanId = if (expanded) null else plan.id }) {
                        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (expanded && installments.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                            // 进度/已还/剩余（REQ 贷款页§14）
                            val paidInsts = installments.filter { it.status == InstallmentStatus.PAID }
                            val unpaidInsts = installments.filter { it.status != InstallmentStatus.PAID }
                            val paidP = paidInsts.sumOf { it.principal.cents }
                            val paidI = paidInsts.sumOf { it.interest.cents }
                            val remainP = if (plan.remainingPrincipalCents > 0) plan.remainingPrincipalCents else unpaidInsts.sumOf { it.principal.cents }
                            val remainI = unpaidInsts.sumOf { it.interest.cents }
                            Text(
                                "已还 ${paidInsts.size}/${installments.size} 期 · 已还本金 ${formatMoney(paidP)} · 已还利息 ${formatMoney(paidI)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "剩余本金 ${formatMoney(remainP)} · 剩余利息 ${formatMoney(remainI)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 最近 3 期（REQ 贷款页§14）
                            Spacer(Modifier.height(2.dp))
                            installments.takeLast(3).forEach { inst ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "${if (inst.status == InstallmentStatus.PAID) "已还" else "待还"} 第${inst.number}期",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (inst.status == InstallmentStatus.PAID) IncomeGreen else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text("本金${formatMoney(inst.principal.cents)} + 利息${formatMoney(inst.interest.cents)}", style = MaterialTheme.typography.bodySmall)
                                    Text(formatMoney(inst.total.cents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                            // 查看完整计划（REQ 贷款页§15）：逐期列表 + 点期编辑
                            TextButton(onClick = { fullPlan = plan }) { Text("查看完整计划", style = MaterialTheme.typography.labelSmall) }
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
                                Text("节省利息 ${formatMoney(r.savedInterest)} · 缩短 ${installments.size - r.newRemainingMonths} 个月", style = MaterialTheme.typography.labelSmall, color = IncomeGreen)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onRecordPayment(plan) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("记录还款") }
                        OutlinedButton(onClick = { prepaying = plan }) { Text("提前还款") }
                        OutlinedButton(onClick = { settling = plan }) { Text("结清") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            editingPlan = plan
                            showSheet = true
                        }) { Text("编辑") }
                        // 永久删除需二次确认（REQ 贷款页§17）
                        OutlinedButton(onClick = { deleteConfirm = plan }) { Text("删除") }
                    }
                }
                }
            }
            // 已结清贷款移入底部折叠分组（REQ 贷款页§13/§17：默认折叠，历史可查）
            if (settledPlans.isNotEmpty()) {
                item {
                    TextButton(onClick = { showSettled = !showSettled }) {
                        Text("已结清 ${settledPlans.size} 笔", style = MaterialTheme.typography.labelMedium)
                        Icon(
                            if (showSettled) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (showSettled) {
                    items(settledPlans, key = { it.id }) { plan ->
                        val account = accounts.firstOrNull { it.id == plan.accountId }
                        GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${account?.name ?: "未知账户"} · 已结清", color = IncomeGreen, fontWeight = FontWeight.Medium)
                                Text("总还款 ${formatMoney(LoanCalculator.summarize(LoanPlan(
                                    id = plan.id, accountId = plan.accountId,
                                    principal = Money(plan.principalCents),
                                    startDateEpochDay = plan.startDateEpochDay,
                                    repaymentMethod = runCatching { RepaymentMethod.valueOf(plan.repaymentMethod) }.getOrDefault(RepaymentMethod.CUSTOM),
                                    installments = jsonToInstallments(plan.installmentsJson)
                                )).totalRepayment.cents)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ── 信用卡分期（只展示与预测，绝不进总负债）──
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("信用卡分期", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { showInstallmentSheet = true }) { Text("＋ 添加") }
            }
        }
        if (cardInstallments.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        Text("暂无信用卡分期", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(cardInstallments, key = { it.id }) { inst ->
                val card = accounts.firstOrNull { it.id == inst.cardAccountId }
                GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${card?.name ?: "信用卡"} · ${inst.label}", fontWeight = FontWeight.Medium)
                            Text(
                                "剩 ${formatMoney(inst.remainingPrincipalCents)} · 每期 ${formatMoney(inst.monthlyPaymentCents)} · 剩${inst.periodsRemaining}期",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { onDeleteInstallment(inst.id) }) { Text("删除") }
                    }
                }
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

    if (showInstallmentSheet) {
        InstallmentSheet(
            accounts = accounts,
            onSave = { onSaveInstallment(it); showInstallmentSheet = false },
            onDismiss = { showInstallmentSheet = false }
        )
    }

    prepaying?.let { plan ->
        PrepaySheet(
            plan = plan,
            accounts = accounts,
            onConfirm = { cashId, principalCents, note ->
                onPrepay(cashId, plan.id, principalCents, note)
                prepaying = null
            },
            onDismiss = { prepaying = null }
        )
    }

    settling?.let { plan ->
        SettleSheet(
            plan = plan,
            accounts = accounts,
            onConfirm = { cashId, principalCents, interestCents, feeCents, note ->
                onSettle(cashId, plan.id, principalCents, interestCents, feeCents, note)
                settling = null
            },
            onDismiss = { settling = null }
        )
    }

    // 永久删除二次确认（REQ 贷款页§17）
    deleteConfirm?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("永久删除贷款计划？") },
            text = { Text("「${accounts.firstOrNull { it.id == plan.accountId }?.name ?: "贷款"}」的计划与还款历史将永久删除，已记流水保留。") },
            confirmButton = {
                TextButton(onClick = { onDelete(plan.id); deleteConfirm = null }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("取消") } }
        )
    }

    // 完整还款计划（REQ 贷款页§15）：逐期列表，点期数编辑
    fullPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { fullPlan = null },
            title = { Text("完整还款计划（${jsonToInstallments(plan.installmentsJson).size} 期）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    jsonToInstallments(plan.installmentsJson).forEach { inst ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editingInstallment = plan to inst }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${if (inst.status == InstallmentStatus.PAID) "已还" else "待还"} 第${inst.number}期",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (inst.status == InstallmentStatus.PAID) IncomeGreen else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text("本金${formatMoney(inst.principal.cents)} 利息${formatMoney(inst.interest.cents)} 费${formatMoney(inst.fee.cents)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMoney(inst.total.cents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fullPlan = null }) { Text("关闭") } },
            dismissButton = {}
        )
    }

    // 单期编辑（REQ 贷款页§5/§15）：金额与状态可改
    editingInstallment?.let { (plan, inst) ->
        var p by remember { mutableStateOf("%.2f".format(inst.principal.cents / 100.0)) }
        var i by remember { mutableStateOf("%.2f".format(inst.interest.cents / 100.0)) }
        var f by remember { mutableStateOf("%.2f".format(inst.fee.cents / 100.0)) }
        var st by remember { mutableStateOf(inst.status.name) }
        AlertDialog(
            onDismissRequest = { editingInstallment = null },
            title = { Text("第${inst.number}期") },
            text = {
                Column {
                    FormField(value = p, onValueChange = { p = it.filter { c -> c.isDigit() || c == '.' } }, label = "本金", isAmount = true)
                    FormField(value = i, onValueChange = { i = it.filter { c -> c.isDigit() || c == '.' } }, label = "利息", isAmount = true)
                    FormField(value = f, onValueChange = { f = it.filter { c -> c.isDigit() || c == '.' } }, label = "手续费", isAmount = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = st == "PAID", onClick = { st = "PAID" }, label = { Text("已还") })
                        FilterChip(selected = st == "UPCOMING", onClick = { st = "UPCOMING" }, label = { Text("待还") })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pc = runCatching { java.math.BigDecimal(p.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                    val ic = runCatching { java.math.BigDecimal(i.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                    val fc = runCatching { java.math.BigDecimal(f.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                    if (pc != null) {
                        onUpdateInstallment(plan.id, inst.number, pc, ic, fc, st)
                        editingInstallment = null
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingInstallment = null }) { Text("取消") } }
        )
    }
}

private fun planRemaining(plan: LoanPlanEntity): Long =
    if (plan.remainingPrincipalCents > 0) plan.remainingPrincipalCents
    else (plan.principalCents - plan.earlyRepaidCents).coerceAtLeast(0)

/** 提前还款：只减本金、不当消费、不标普通期次；银行给出新计划后到「编辑」更新 */
@Composable
private fun PrepaySheet(
    plan: LoanPlanEntity,
    accounts: List<AccountEntity>,
    onConfirm: (String, Long, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val assetAccounts = accounts.filter { it.type == com.assetsking.model.AccountType.ASSET.name }
    var cashAccountId by remember(assetAccounts) { mutableStateOf(assetAccounts.firstOrNull()?.id.orEmpty()) }
    var principal by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Sheet(title = "提前还款", onDismiss = onDismiss) {
        Text("剩余本金 ${formatMoney(planRemaining(plan))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("提前还款只减本金，不标普通期次；银行给出新还款计划后，用「编辑」更新计划", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FormField(value = principal, onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' } }, label = "提前还本金", isAmount = true)
        Spacer(Modifier.height(8.dp))
        Text("付款账户", fontWeight = FontWeight.Medium)
        val selected = assetAccounts.firstOrNull { it.id == cashAccountId }
        if (selected != null) {
            ChipRow(
                items = assetAccounts,
                selected = selected,
                onSelected = { cashAccountId = it.id },
                label = { it.name },
                id = { it.id }
            )
        }
        Spacer(Modifier.height(8.dp))
        FormField(value = note, onValueChange = { note = it }, label = "备注（可选）")
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(principal.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                onConfirm(cashAccountId, cents, note.trim().takeIf { it.isNotEmpty() })
            },
            enabled = principal.toDoubleOrNull()?.let { it > 0 } == true && cashAccountId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("确认提前还款") }
    }
}

/** 提前结清：本金归零 + 全期 PAID + 计划 PAID_OFF（V5 §36） */
@Composable
private fun SettleSheet(
    plan: LoanPlanEntity,
    accounts: List<AccountEntity>,
    onConfirm: (String, Long, Long, Long, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val assetAccounts = accounts.filter { it.type == com.assetsking.model.AccountType.ASSET.name }
    var cashAccountId by remember(assetAccounts) { mutableStateOf(assetAccounts.firstOrNull()?.id.orEmpty()) }
    var principal by remember { mutableStateOf("%.2f".format(planRemaining(plan) / 100.0)) }
    var interest by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Sheet(title = "提前结清", onDismiss = onDismiss) {
        Text("结清后本金归零、未来还款计划取消", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FormField(value = principal, onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' } }, label = "剩余本金", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = interest, onValueChange = { interest = it.filter { c -> c.isDigit() || c == '.' } }, label = "当期利息", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() || c == '.' } }, label = "结清手续费（可选）", isAmount = true)
        Spacer(Modifier.height(8.dp))
        Text("付款账户", fontWeight = FontWeight.Medium)
        val selected = assetAccounts.firstOrNull { it.id == cashAccountId }
        if (selected != null) {
            ChipRow(
                items = assetAccounts,
                selected = selected,
                onSelected = { cashAccountId = it.id },
                label = { it.name },
                id = { it.id }
            )
        }
        Spacer(Modifier.height(8.dp))
        FormField(value = note, onValueChange = { note = it }, label = "备注（可选）")
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val p = runCatching { java.math.BigDecimal(principal.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: return@Button
                val i = runCatching { java.math.BigDecimal(interest.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: 0L
                val f = runCatching { java.math.BigDecimal(fee.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: 0L
                onConfirm(cashAccountId, p, i, f, note.trim().takeIf { it.isNotEmpty() })
            },
            enabled = (principal.toDoubleOrNull() ?: 0.0) + (interest.toDoubleOrNull() ?: 0.0) + (fee.toDoubleOrNull() ?: 0.0) > 0 && cashAccountId.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("确认结清") }
    }
}

@Composable
private fun InstallmentSheet(
    accounts: List<AccountEntity>,
    onSave: (CreditCardInstallmentEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val cardAccounts = accounts.filter { it.type == com.assetsking.model.AccountType.CREDIT.name }
    var cardAccountId by remember { mutableStateOf(cardAccounts.firstOrNull()?.id.orEmpty()) }
    var label by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf("") }
    var monthly by remember { mutableStateOf("") }
    var feePerPeriod by remember { mutableStateOf("") }
    var periods by remember { mutableStateOf("") }

    Sheet(title = "添加信用卡分期", onDismiss = onDismiss) {
        Text("分期已在卡的余额内，这里只记录怎么还，不计入总负债", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("信用卡", fontWeight = FontWeight.Medium)
        val selectedCard = cardAccounts.firstOrNull { it.id == cardAccountId }
        if (selectedCard != null && cardAccounts.isNotEmpty()) {
            ChipRow(
                items = cardAccounts,
                selected = selectedCard,
                onSelected = { cardAccountId = it.id },
                label = { it.name },
                id = { it.id }
            )
        } else {
            Text("请先添加信用卡账户", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        FormField(value = label, onValueChange = { label = it }, label = "分期名称（如 iPhone 24期）")
        Spacer(Modifier.height(8.dp))
        FormField(value = original, onValueChange = { original = it.filter { c -> c.isDigit() || c == '.' } }, label = "原始分期本金", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = remaining, onValueChange = { remaining = it.filter { c -> c.isDigit() || c == '.' } }, label = "剩余分期本金", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = monthly, onValueChange = { monthly = it.filter { c -> c.isDigit() || c == '.' } }, label = "每期还款（含利息）", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = feePerPeriod, onValueChange = { feePerPeriod = it.filter { c -> c.isDigit() || c == '.' } }, label = "每期手续费（可选）", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = periods, onValueChange = { periods = it.filter(Char::isDigit).take(3) }, label = "剩余期数")
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val origCents = runCatching { java.math.BigDecimal(original.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: return@Button
                val remCents = runCatching { java.math.BigDecimal(remaining.ifBlank { original }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: origCents
                val monthlyCents = runCatching { java.math.BigDecimal(monthly.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: return@Button
                val feeCents = runCatching { java.math.BigDecimal(feePerPeriod.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: 0L
                val periodsInt = periods.toIntOrNull() ?: return@Button
                onSave(
                    CreditCardInstallmentEntity(
                        id = UUID.randomUUID().toString(),
                        cardAccountId = cardAccountId,
                        label = label.trim().ifBlank { "分期" },
                        originalPrincipalCents = origCents,
                        remainingPrincipalCents = remCents,
                        monthlyPaymentCents = monthlyCents,
                        feeCentsPerPeriod = feeCents,
                        periodsRemaining = periodsInt,
                        startDateEpochDay = java.time.LocalDate.now().toEpochDay()
                    )
                )
            },
            enabled = cardAccountId.isNotBlank() && original.toDoubleOrNull()?.let { it > 0 } == true &&
                monthly.toDoubleOrNull()?.let { it > 0 } == true && periods.toIntOrNull() != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
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
