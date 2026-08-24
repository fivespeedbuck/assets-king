package com.assetsking.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.TrendingDown
import com.assetsking.database.AccountEntity
import com.assetsking.database.CreditCardInstallmentAllocationEntity
import com.assetsking.database.CreditCardInstallmentDraft
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.CreditCardInstallmentPaymentMatchEntity
import com.assetsking.database.CreditCardInstallmentScheduleEntity
import com.assetsking.database.CreditCardInstallmentScheduleDraft
import com.assetsking.database.CreditCardInstallmentTerms
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.ledger.CardInstallmentAllocationRequest
import com.assetsking.ledger.cardStatementCycle
import com.assetsking.ledger.estimateInstallmentCost
import com.assetsking.ledger.LoanCalculator
import com.assetsking.ledger.V5Metrics
import com.assetsking.model.InstallmentStatus
import com.assetsking.model.AccountType
import com.assetsking.model.LoanInstallment
import com.assetsking.model.LoanPlan
import com.assetsking.model.Money
import com.assetsking.model.RepaymentMethod
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.Sheet
import com.assetsking.app.ui.privacy.LocalPrivacyChaosFrame
import com.assetsking.app.ui.privacy.animatePrivacyValue
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.app.ui.privacy.privacyScrambleText
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatMoneyCompact
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.theme.DeficitRed
import com.assetsking.ui.theme.AccruedChargeDebtColor
import com.assetsking.ui.theme.ExpenseRed
import com.assetsking.ui.theme.ForecastChargeYellow
import com.assetsking.ui.theme.IncomeGreen
import com.assetsking.ui.theme.PendingOrange
import com.assetsking.ui.theme.RepaymentPurple
import com.assetsking.ui.theme.debtAmountColor
import com.assetsking.ui.theme.debtCompositionSemanticColor
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private enum class CardInstallmentPricingInput {
    FIXED_PAYMENT,
    SEPARATE_CHARGES,
    CUSTOM_SCHEDULE
}

private fun cardInstallmentPricingLabel(input: CardInstallmentPricingInput): String = when (input) {
    CardInstallmentPricingInput.FIXED_PAYMENT -> "统一每期待还"
    CardInstallmentPricingInput.SEPARATE_CHARGES -> "息费分开填写"
    CardInstallmentPricingInput.CUSTOM_SCHEDULE -> "逐期自填"
}

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

internal fun eligibleLoanAccounts(accounts: List<AccountEntity>): List<AccountEntity> =
    accounts.filter { it.type == AccountType.LOAN.name && !it.archived }

internal fun initialLoanPlanAccountId(
    existingAccountId: String?,
    eligibleAccounts: List<AccountEntity>
): String = existingAccountId
    ?.takeIf { existingId -> eligibleAccounts.any { it.id == existingId } }
    ?: eligibleAccounts.firstOrNull()?.id.orEmpty()

internal fun nearbyLoanInstallments(installments: List<LoanInstallment>): List<LoanInstallment> {
    val ordered = installments.sortedBy { it.number }
    val firstUnpaid = ordered.indexOfFirst { it.status != InstallmentStatus.PAID }
    if (firstUnpaid < 0) return ordered.takeLast(3)
    return ordered.drop((firstUnpaid - 1).coerceAtLeast(0)).take(3)
}

internal fun nearbyCardInstallmentSchedules(
    schedules: List<CreditCardInstallmentScheduleEntity>
): List<CreditCardInstallmentScheduleEntity> {
    val ordered = schedules.sortedBy { it.number }
    val firstUnpaid = ordered.indexOfFirst { it.status != "PAID" }
    if (firstUnpaid < 0) return ordered.takeLast(3)
    return ordered.drop((firstUnpaid - 1).coerceAtLeast(0)).take(3)
}

internal fun visibleLoanCreditAccounts(
    accounts: List<AccountEntity>,
    cardRemainingDueByCard: Map<String, Long>
): List<AccountEntity> = accounts
    .filter { account ->
        account.type == AccountType.CREDIT.name &&
            !account.archived &&
            account.balanceCents > 0L
    }
    .sortedWith(
        compareByDescending<AccountEntity> { (cardRemainingDueByCard[it.id] ?: 0L) > 0L }
            .thenBy { it.dueDay ?: 32 }
            .thenBy { it.name }
    )

// ── Main Screen ──

@Composable
fun LoanScreen(
    plans: List<LoanPlanEntity>,
    accounts: List<AccountEntity>,
    onSave: (LoanPlanEntity) -> Unit,
    onDelete: (String) -> Unit,
    onAddLoanAccount: () -> Unit = {},
    onAddCreditAccount: () -> Unit = {},
    v5: V5Metrics? = null,
    cardInstallments: List<CreditCardInstallmentEntity> = emptyList(),
    cardInstallmentAllocations: List<CreditCardInstallmentAllocationEntity> = emptyList(),
    cardInstallmentSchedules: List<CreditCardInstallmentScheduleEntity> = emptyList(),
    cardInstallmentPaymentMatches: List<CreditCardInstallmentPaymentMatchEntity> = emptyList(),
    transfers: List<TransferEntity> = emptyList(),
    onCreateInstallment: (CreditCardInstallmentDraft, (Result<String>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("未连接分期服务"))) },
    onAdjustInstallment: (String, CreditCardInstallmentTerms, (Result<Unit>) -> Unit) -> Unit = { _, _, callback -> callback(Result.failure(IllegalStateException("未连接分期服务"))) },
    onCancelInstallment: (String, (Result<Unit>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("未连接分期服务"))) },
    onConfirmInstallmentPayment: (String, String, Long, (Result<Unit>) -> Unit) -> Unit = { _, _, _, callback -> callback(Result.failure(IllegalStateException("未连接还款匹配服务"))) },
    onOpenCreditAccount: (AccountEntity) -> Unit = {},
    onRecordPayment: (LoanPlanEntity) -> Unit = {},
    onPrepay: (String, String, Long, Long, String?) -> Unit = { _, _, _, _, _ -> },
    onSettle: (String, String, Long, Long, Long, String?) -> Unit = { _, _, _, _, _, _ -> },
    onUpdateInstallment: (String, Int, Long?, Long?, Long?, Long?, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    transactions: List<com.assetsking.database.TransactionEntity> = emptyList()
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    var showSheet by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf<LoanPlanEntity?>(null) }
    var showCreditInstallmentSheet by remember { mutableStateOf(false) }
    var showDueCalendar by remember { mutableStateOf(false) }
    var showDebtDetails by remember { mutableStateOf(false) }
    var expandedCreditAccountId by remember { mutableStateOf<String?>(null) }
    var expandedCardInstallmentId by remember { mutableStateOf<String?>(null) }
    var fullCardInstallment by remember { mutableStateOf<CreditCardInstallmentEntity?>(null) }
    var adjustingCardInstallment by remember { mutableStateOf<CreditCardInstallmentEntity?>(null) }
    var cancellingCardInstallment by remember { mutableStateOf<CreditCardInstallmentEntity?>(null) }
    var installmentMessage by remember { mutableStateOf<String?>(null) }
    var prepaying by remember { mutableStateOf<LoanPlanEntity?>(null) }
    var settling by remember { mutableStateOf<LoanPlanEntity?>(null) }
    // 手风琴一次只展开一笔（REQ 贷款页§14）；完整计划与单期编辑
    var expandedPlanId by remember { mutableStateOf<String?>(null) }
    var fullPlan by remember { mutableStateOf<LoanPlanEntity?>(null) }
    var editingInstallment by remember { mutableStateOf<Pair<LoanPlanEntity, LoanInstallment>?>(null) }
    var deleteConfirm by remember { mutableStateOf<LoanPlanEntity?>(null) }

    // 本月还款列表（REQ 贷款页§2-3/§11）：逾期置顶 → 本月待还 → 本月已还（删除线）
    val todayDay = java.time.LocalDate.now().toEpochDay()
    val zone = java.time.ZoneId.systemDefault()
    val monthStartMillis = java.time.YearMonth.now().atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEndMillis = java.time.YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val installmentCandidates = remember(transactions, accounts, cardInstallments, cardInstallmentAllocations) {
        cardInstallmentCandidates(transactions, accounts, cardInstallments, cardInstallmentAllocations)
    }
    val creditAccounts = remember(accounts, v5?.cardRemainingDueByCard) {
        visibleLoanCreditAccounts(accounts, v5?.cardRemainingDueByCard.orEmpty())
    }
    val pendingPaymentConfirmations = remember(
        cardInstallmentPaymentMatches,
        transfers,
        cardInstallmentSchedules,
        cardInstallments,
        accounts
    ) {
        pendingCardPaymentConfirmations(
            matches = cardInstallmentPaymentMatches,
            transfers = transfers,
            schedules = cardInstallmentSchedules,
            plans = cardInstallments,
            accounts = accounts
        )
    }
    val repayRows = monthRepaymentItems(
        plans = plans,
        accounts = accounts,
        cardInstallments = cardInstallments,
        cardSchedules = cardInstallmentSchedules,
        cardRemainingDueByCard = v5?.cardRemainingDueByCard.orEmpty(),
        transactions = transactions,
        transfers = transfers
    )
    val monthOutstandingRows = repayRows.filterNot { it.paid }
    // 日历与“本月还款”读取同一权威清单，避免固定还款日再派生出另一套金额。
    val dueItems = monthOutstandingRows
    val monthOutstandingCents = monthOutstandingRows.sumOf { it.amount }
    val monthPaidCents = repayRows.filter { it.paid }.sumOf { it.amount }
    val due7DaysCents = monthRepaymentItems(
        plans = plans,
        accounts = accounts,
        cardInstallments = cardInstallments,
        cardSchedules = cardInstallmentSchedules,
        cardRemainingDueByCard = v5?.cardRemainingDueByCard.orEmpty(),
        transactions = transactions,
        transfers = transfers,
        outstandingThrough = java.time.LocalDate.now().plusDays(7)
    ).filter {
        !it.paid && it.dueDay in java.time.LocalDate.now().toEpochDay()..java.time.LocalDate.now().plusDays(7).toEpochDay()
    }.sumOf { it.amount }
    val accountsById = accounts.associateBy { it.id }
    val validLoanPlans = plans.filter { plan ->
        accountsById[plan.accountId]?.let { it.type == AccountType.LOAN.name && !it.archived } == true
    }
    val activePlans = validLoanPlans.filter { it.status != "PAID_OFF" }
    val invalidActivePlans = plans.filter { plan ->
        plan.status != "PAID_OFF" && plan !in validLoanPlans
    }
    val dashboard = loanDashboardUi(
        v5,
        transactions,
        monthStartMillis,
        monthEndMillis,
        monthOutstandingCents = monthOutstandingCents,
        monthPaidCents = monthPaidCents,
        due7DaysCents = due7DaysCents
    )
    val visibleCreditAccountIds = creditAccounts.mapTo(mutableSetOf()) { it.id }
    val currentCardInstallments = cardInstallments.filter {
        it.status == "ACTIVE" && it.cardAccountId in visibleCreditAccountIds
    }
    val currentCardForecastChargeCents = currentCardInstallments.sumOf { installment ->
        creditInstallmentForecastUi(installment, cardInstallmentSchedules).forecastChargeCents
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LoanDashboardCard(
                dashboard = dashboard,
                metrics = v5,
                transactions = transactions,
                plans = validLoanPlans,
                cardInstallmentForecastChargeCents = currentCardForecastChargeCents,
                calendarCount = dueItems.size,
                calendarExpanded = showDueCalendar,
                onToggleCalendar = { showDueCalendar = !showDueCalendar },
                detailsExpanded = showDebtDetails,
                onToggleDetails = { showDebtDetails = !showDebtDetails }
            )
        }

        if (dueItems.isNotEmpty() && showDueCalendar) {
                item {
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("还款日历", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            dueItems.forEachIndexed { index, due ->
                                val dueDate = java.time.LocalDate.ofEpochDay(due.dueDay)
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (privacyEnabled) privacyObfuscatedText(due.label, 1_010 + index) else due.label,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (privacyEnabled) privacyFakeDateTime(1_030 + index).substringBefore(' ')
                                            else "${dueDate.monthValue}月${dueDate.dayOfMonth}日",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = PendingOrange
                                        )
                                    }
                                    Text(
                                        if (privacyEnabled) privacyFakeAmount(1_050 + index) else formatMoney(due.amount),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = ExpenseRed
                                    )
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("本月还款", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (privacyEnabled) "本月总待还 ${privacyFakeCount(1_080)}笔"
                                    else "本月总待还 ${monthOutstandingRows.size}笔",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (privacyEnabled) privacyFakeAmount(1_081) else formatMoney(monthOutstandingCents),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = ExpenseRed
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(2.dp))
                        Column(Modifier.fillMaxWidth()) {
                            repayRows.forEachIndexed { index, r ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                                }
                                val overdue = !r.paid && r.dueDay < todayDay
                                val dueDate = java.time.LocalDate.ofEpochDay(r.dueDay)
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = !r.paid && !privacyEnabled) {
                                        when (r.source) {
                                            MonthRepaymentSource.LOAN_PLAN -> plans.firstOrNull { it.id == r.sourceId }?.let(onRecordPayment)
                                            MonthRepaymentSource.CREDIT_STATEMENT,
                                            MonthRepaymentSource.CREDIT_INSTALLMENT -> accounts.firstOrNull { it.id == r.sourceId }?.let(onOpenCreditAccount)
                                        }
                                    }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    (if (r.paid) "已还 · " else if (overdue) "逾期 · " else "待还 · ") +
                                        if (privacyEnabled) privacyObfuscatedText(r.label, 1_100 + index) else r.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (overdue) FontWeight.Bold else null,
                                    color = when { overdue -> DeficitRed; r.paid -> RepaymentPurple; else -> MaterialTheme.colorScheme.onSurface },
                                    textDecoration = if (r.paid) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        if (privacyEnabled) privacyFakeDateTime(1_120 + index).substringBefore(' ') else "${dueDate.monthValue}月${dueDate.dayOfMonth}日",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when { overdue -> DeficitRed; r.paid -> RepaymentPurple; else -> PendingOrange }
                                    )
                                    Text(
                                        if (privacyEnabled) privacyFakeAmount(1_140 + index) else formatMoney(r.amount),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when { overdue -> DeficitRed; r.paid -> RepaymentPurple; else -> ExpenseRed },
                                        textDecoration = if (r.paid) TextDecoration.LineThrough else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("信用账户", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onAddCreditAccount,
                                enabled = !privacyEnabled
                            ) { Text("新增信用卡") }
                            Button(
                                onClick = { showCreditInstallmentSheet = true },
                                enabled = installmentCandidates.isNotEmpty() && !privacyEnabled
                            ) { Text("创建分期") }
                        }
                    }
                    installmentMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = if (message.startsWith("失败")) DeficitRed else IncomeGreen)
                    }
                    if (creditAccounts.isEmpty()) {
                        Text(
                            "暂无未结清的信用账户",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        creditAccounts.forEach { account ->
                            val accountInstallments = currentCardInstallments.filter { it.cardAccountId == account.id }
                            CreditAccountPayableCard(
                                account = account,
                                statementRemainingCents = v5?.cardRemainingDueByCard?.get(account.id)
                                    ?: account.statementOriginalDueCents,
                                installments = accountInstallments,
                                schedules = cardInstallmentSchedules,
                                expanded = expandedCreditAccountId == account.id,
                                enabled = !privacyEnabled,
                                onToggle = {
                                    val collapsing = expandedCreditAccountId == account.id
                                    expandedCreditAccountId = if (collapsing) null else account.id
                                    if (collapsing) expandedCardInstallmentId = null
                                },
                                onOpenAccount = { onOpenCreditAccount(account) }
                            ) {
                                accountInstallments.forEachIndexed { index, installment ->
                                    if (index > 0) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                    CardInstallmentCard(
                                        installment = installment,
                                        schedules = cardInstallmentSchedules.filter { it.planId == installment.id },
                                        expanded = expandedCardInstallmentId == installment.id,
                                        actionsEnabled = !privacyEnabled,
                                        embedded = true,
                                        onToggle = {
                                            expandedCardInstallmentId = if (expandedCardInstallmentId == installment.id) null else installment.id
                                        },
                                        onViewPlan = { fullCardInstallment = installment }
                                    )
                                }
                                }
                            }
                        }
                    }
                    if (pendingPaymentConfirmations.isNotEmpty()) {
                        Text("待确认还款", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        pendingPaymentConfirmations.forEach { payment ->
                            GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("这笔还款对应多个分期，请确认归属", fontWeight = FontWeight.Medium)
                                    Text(
                                        if (privacyEnabled) {
                                            "${privacyObfuscatedText(payment.cardLabel, 610)} · ${privacyFakeAmount(611)}"
                                        } else {
                                            "${payment.cardLabel} · ${formatMoney(payment.paymentCents)}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    payment.candidates.forEachIndexed { candidateIndex, candidate ->
                                        OutlinedButton(
                                            onClick = {
                                                onConfirmInstallmentPayment(
                                                    payment.transferId,
                                                    candidate.scheduleId,
                                                    candidate.principalCents
                                                ) { result ->
                                                    installmentMessage = result.fold(
                                                        onSuccess = {
                                                            if (privacyEnabled) "还款已完成匹配，只推进本金"
                                                            else "还款已匹配到 ${candidate.planLabel}，只推进本金"
                                                        },
                                                        onFailure = { "失败：${it.message ?: "还款匹配未完成"}" }
                                                    )
                                                }
                                            },
                                            enabled = !privacyEnabled,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                if (privacyEnabled) {
                                                    "${privacyObfuscatedText(candidate.planLabel, 620 + candidateIndex)} · ${privacyFakeDateTime(640 + candidateIndex).substringBefore(' ')} · 本金 ${privacyFakeAmount(660 + candidateIndex)}"
                                                } else {
                                                    "${candidate.planLabel} · ${java.time.LocalDate.ofEpochDay(candidate.dueDateEpochDay)} · 本金 ${formatMoney(candidate.principalCents)}"
                                                },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("贷款计划", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = { editingPlan = null; showSheet = true },
                            enabled = !privacyEnabled
                        ) { Text("＋ 添加") }
                    }
                    invalidActivePlans.forEach { plan ->
                        val legacyAccountName = accountsById[plan.accountId]?.name ?: "未知账户"
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(PendingOrange.copy(alpha = 0.10f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("$legacyAccountName · 需修复关联账户", fontWeight = FontWeight.Bold, color = PendingOrange)
                            Text(
                                "该旧计划未关联有效贷款账户，暂不参与总负债、待还和利息统计。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { editingPlan = plan; showSheet = true },
                                enabled = !privacyEnabled,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("修复关联") }
                        }
                    }
        if (activePlans.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        Text("暂无贷款计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
        } else {
            activePlans.forEach { plan ->
                val account = accounts.firstOrNull { it.id == plan.accountId }
                val accountNeedsRepair = account == null || account.type != AccountType.LOAN.name || account.archived
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
                    val remaining = planRemaining(plan)
                    val unpaidInstallments = installments.filter { it.status != InstallmentStatus.PAID }
                    val remainingScheduledPrincipalCents = unpaidInstallments.sumOf { it.principal.cents }
                    val remainingForecastChargeCents = unpaidInstallments.sumOf { it.interest.cents + it.fee.cents }
                    val remainingScheduledTotalCents = unpaidInstallments.sumOf { it.total.cents }
                    val principalGapCents = remaining - remainingScheduledPrincipalCents
                    val nextInstallment = installments.firstOrNull { it.status != InstallmentStatus.PAID }
                    val nextDueDate = nextInstallment?.dueDateEpochDay?.let(java.time.LocalDate::ofEpochDay)
                    val expanded = expandedPlanId == plan.id
                    val remainingRepaymentLabel = if (principalGapCents == 0L) "预计剩余总还款" else "当前计划待还"
                    val remainingChargeLabel = if (principalGapCents == 0L) "预计剩余息费" else "已排期预计息费"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(enabled = !privacyEnabled) {
                                expandedPlanId = if (expanded) null else plan.id
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (privacyEnabled) {
                                privacyObfuscatedText(account?.name ?: "未知账户", 1_200 + plan.id.hashCode())
                            } else if (plan.status == "PAID_OFF") {
                                "${account?.name ?: "未知账户"} · 已结清"
                            } else if (accountNeedsRepair) {
                                "${account?.name ?: "未知账户"} · 需改关联账户"
                            } else {
                                account.name
                            },
                            fontWeight = FontWeight.Medium,
                            color = when {
                                plan.status == "PAID_OFF" -> IncomeGreen
                                accountNeedsRepair -> PendingOrange
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Clip
                        )
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起贷款详情" else "展开贷款详情"
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                remainingRepaymentLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (privacyEnabled) privacyFakeAmount(1_246 + plan.id.hashCode()) else formatMoney(remainingScheduledTotalCents),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = debtAmountColor(remainingScheduledTotalCents),
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                            Text(
                                "$remainingChargeLabel ${if (privacyEnabled) privacyFakeAmount(1_245 + plan.id.hashCode()) else formatMoney(remainingForecastChargeCents)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Text(
                                "剩余本金 ${if (privacyEnabled) privacyFakeAmount(1_240 + plan.id.hashCode()) else formatMoney(remaining)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("下次还款", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (privacyEnabled) privacyFakeDateTime(1_220 + plan.id.hashCode()).substringBefore(' ')
                                else nextDueDate?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: "暂无日期",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (nextDueDate == null) MaterialTheme.colorScheme.onSurfaceVariant else PendingOrange,
                                maxLines = 1
                            )
                            Text(
                                if (privacyEnabled) privacyFakeAmount(1_230 + plan.id.hashCode())
                                else nextInstallment?.let { formatMoney(it.total.cents) } ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = nextInstallment?.let { debtAmountColor(it.total.cents) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    val paidCount = installments.count { it.status == InstallmentStatus.PAID }
                    if (expanded) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val paidInsts = installments.filter { it.status == InstallmentStatus.PAID }
                            val unpaidInsts = installments.filter { it.status != InstallmentStatus.PAID }
                            val paidP = paidInsts.sumOf { it.principal.cents }
                            val remainP = if (plan.remainingPrincipalCents > 0) plan.remainingPrincipalCents else unpaidInsts.sumOf { it.principal.cents }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LoanPlanMetric(
                                    label = "还款进度",
                                    value = if (privacyEnabled) "${privacyFakeCount(1_270)}/${privacyFakeCount(1_271)}期" else "$paidCount/${installments.size}期",
                                    color = RepaymentPurple,
                                    modifier = Modifier.weight(1f)
                                )
                                LoanPlanMetric(
                                    label = "年利率",
                                    value = if (privacyEnabled) "${privacyFakeCount(1_272)}%" else if (plan.annualRateBps > 0) "${"%.2f".format(plan.annualRateBps / 100.0)}%" else "未填写",
                                    color = if (plan.annualRateBps > 0 || privacyEnabled) MaterialTheme.colorScheme.onSurface else PendingOrange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LoanPlanMetric(
                                    label = "已还本金",
                                    value = if (privacyEnabled) privacyFakeAmount(1_273) else formatMoney(paidP),
                                    color = RepaymentPurple,
                                    modifier = Modifier.weight(1f)
                                )
                                LoanPlanMetric(
                                    label = "剩余本金",
                                    value = if (privacyEnabled) privacyFakeAmount(1_274) else formatMoney(remainP),
                                    color = ExpenseRed,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LoanPlanMetric(
                                    label = if (principalGapCents == 0L) "预计总息费" else "当前计划息费",
                                    value = if (privacyEnabled) privacyFakeAmount(1_275) else formatMoney(summary.totalInterest.cents + summary.totalFees.cents),
                                    color = PendingOrange,
                                    modifier = Modifier.weight(1f)
                                )
                                LoanPlanMetric(
                                    label = if (principalGapCents == 0L) "预计总还款" else "当前计划合计",
                                    value = if (privacyEnabled) privacyFakeAmount(1_276) else formatMoney(summary.totalRepayment.cents),
                                    color = ExpenseRed,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (!privacyEnabled) {
                                val principalGap = loanSchedulePrincipalGapCents(plan, installments)
                                if (principalGap != 0L) {
                                    Text(
                                        if (principalGap > 0L) {
                                            "另有剩余本金 ${formatMoney(principalGap)} 未排入计划，请核对"
                                        } else {
                                            "计划本金比当前剩余本金多 ${formatMoney(-principalGap)}，请核对"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = PendingOrange
                                    )
                                }
                            }
                            Text("近期计划", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            nearbyLoanInstallments(installments).forEachIndexed { index, inst ->
                                val due = java.time.LocalDate.ofEpochDay(inst.dueDateEpochDay)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${if (inst.status == InstallmentStatus.PAID) "已还" else "待还"} · 第${if (privacyEnabled) privacyFakeCount(1_280 + index) else inst.number}期",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = if (inst.status == InstallmentStatus.PAID) RepaymentPurple else PendingOrange
                                        )
                                        Text(
                                            if (privacyEnabled) privacyFakeDateTime(1_290 + index).substringBefore(' ')
                                            else "${due.year}年${due.monthValue}月${due.dayOfMonth}日",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        if (privacyEnabled) privacyFakeAmount(1_310 + index) else formatMoney(inst.total.cents),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (inst.status == InstallmentStatus.PAID) RepaymentPurple else ExpenseRed
                                    )
                                }
                            }
                            OutlinedButton(onClick = { fullPlan = plan }, enabled = !privacyEnabled, modifier = Modifier.fillMaxWidth()) {
                                Text("查看完整计划")
                            }
                            Button(
                                onClick = { onRecordPayment(plan) },
                                enabled = !privacyEnabled,
                                colors = ButtonDefaults.buttonColors(containerColor = DeficitRed),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("记录还款", maxLines = 1) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { prepaying = plan }, enabled = !privacyEnabled, modifier = Modifier.weight(1f)) { Text("提前还款", maxLines = 1) }
                                OutlinedButton(onClick = { settling = plan }, enabled = !privacyEnabled, modifier = Modifier.weight(1f)) { Text("结清", maxLines = 1) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    editingPlan = plan
                                    showSheet = true
                                }, enabled = !privacyEnabled, modifier = Modifier.weight(1f)) { Text("编辑") }
                                // 永久删除需二次确认（REQ 贷款页§17）
                                OutlinedButton(onClick = { deleteConfirm = plan }, enabled = !privacyEnabled, modifier = Modifier.weight(1f)) { Text("删除") }
                            }
                        }
                    }
                }
                }
            }
        }
                }
            }
        }

    }

    if (showSheet && !privacyEnabled) {
        LoanPlanSheet(
            existingPlan = editingPlan,
            accounts = eligibleLoanAccounts(accounts),
            // 保留底层贷款计划表单；新建账户 Sheet 关闭后原输入和编辑上下文仍在，
            // accounts 更新会自动把新贷款账户带回当前表单。
            onAddLoanAccount = onAddLoanAccount,
            onSave = { onSave(it); showSheet = false; editingPlan = null },
            onDismiss = { showSheet = false; editingPlan = null }
        )
    }

    if (showCreditInstallmentSheet && !privacyEnabled) {
        CreditInstallmentSheet(
            candidates = installmentCandidates,
            accounts = accounts,
            cardRemainingDueByCard = v5?.cardRemainingDueByCard.orEmpty(),
            onCreate = { draft, callback ->
                onCreateInstallment(draft) { result ->
                    result.onSuccess {
                        installmentMessage = if (draft.installmentType == "STATEMENT_INSTALLMENT") {
                            "账单分期已创建，本期应还已按分期本金重排；原流水和总负债没有重复变动"
                        } else {
                            "信用分期已创建，原流水和总负债没有重复变动"
                        }
                        showCreditInstallmentSheet = false
                    }.onFailure { error ->
                        installmentMessage = "失败：${error.message ?: "无法创建分期"}"
                    }
                    callback(result)
                }
            },
            onDismiss = { showCreditInstallmentSheet = false }
        )
    }

    adjustingCardInstallment?.takeUnless { privacyEnabled }?.let { installment ->
        AdjustCardInstallmentSheet(
            installment = installment,
            schedules = cardInstallmentSchedules.filter { it.planId == installment.id },
            onSave = { terms, callback ->
                onAdjustInstallment(installment.id, terms) { result ->
                    result.onSuccess {
                        installmentMessage = "分期条款已调整，旧期次保留为审计记录"
                        adjustingCardInstallment = null
                    }.onFailure { error ->
                        installmentMessage = "失败：${error.message ?: "无法调整分期"}"
                    }
                    callback(result)
                }
            },
            onDismiss = { adjustingCardInstallment = null }
        )
    }

    fullCardInstallment?.takeUnless { privacyEnabled }?.let { installment ->
        val currentAllocations = cardInstallmentAllocations.filter { it.planId == installment.id }
        val currentSchedule = cardInstallmentSchedules
            .filter {
                it.planId == installment.id &&
                    it.revision == installment.scheduleRevision &&
                    it.status != "CANCELLED"
            }
            .sortedBy { it.number }
        Sheet(
            title = "完整分期计划 · ${currentSchedule.size}期",
            onDismiss = { fullCardInstallment = null }
        ) {
            Text("计划息费为预计值，实际账单入账后按真实金额记账。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (currentAllocations.isNotEmpty()) {
                Text("关联账款", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                currentAllocations.forEach { allocation ->
                    val transaction = transactions.firstOrNull { it.id == allocation.transactionId }
                    DashboardDetailRow(
                        transaction?.merchant?.takeIf { it.isNotBlank() } ?: transaction?.category ?: "原消费",
                        formatMoney(allocation.allocatedPrincipalCents)
                    )
                }
            }
            if (currentSchedule.isEmpty()) {
                Text("当前还没有逐期计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                currentSchedule.forEachIndexed { index, schedule ->
                    val paid = schedule.status == "PAID"
                    val dueDate = java.time.LocalDate.ofEpochDay(schedule.dueDateEpochDay)
                    val forecastCharge = schedule.expectedInterestCents + schedule.expectedFeeCents +
                        schedule.expectedUnclassifiedChargeCents
                    val expected = schedule.principalDueCents + forecastCharge
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (paid) IncomeGreen.copy(alpha = 0.08f) else PendingOrange.copy(alpha = 0.08f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "第${schedule.number}期 · ${if (paid) "已还" else "待还"}",
                                fontWeight = FontWeight.Bold,
                                color = if (paid) IncomeGreen else PendingOrange
                            )
                            Text(
                                "${dueDate.year}年${dueDate.monthValue}月${dueDate.dayOfMonth}日",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "本金 ${formatMoney(schedule.principalDueCents)} · 预计息费 ${formatMoney(forecastCharge)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            formatMoney(expected),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (paid) RepaymentPurple else ExpenseRed
                        )
                    }
                    if (index < currentSchedule.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
            if (installment.status == "ACTIVE") {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        fullCardInstallment = null
                        adjustingCardInstallment = installment
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("修改分期计划") }
                OutlinedButton(
                    onClick = {
                        fullCardInstallment = null
                        cancellingCardInstallment = installment
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("取消分期", color = DeficitRed) }
            }
        }
    }

    cancellingCardInstallment?.takeUnless { privacyEnabled }?.let { installment ->
        AlertDialog(
            onDismissRequest = { cancellingCardInstallment = null },
            title = { Text("取消消费分期？") },
            text = { Text("原消费流水不会删除，总负债也不会变化；计划、关联和操作记录将保留，剩余本金可重新分期。") },
            confirmButton = {
                TextButton(onClick = {
                    onCancelInstallment(installment.id) { result ->
                        result.onSuccess {
                            installmentMessage = "分期已取消，原流水和审计关联均已保留"
                            cancellingCardInstallment = null
                        }.onFailure { error ->
                            installmentMessage = "失败：${error.message ?: "无法取消分期"}"
                        }
                    }
                }) { Text("确认取消", color = DeficitRed) }
            },
            dismissButton = { TextButton(onClick = { cancellingCardInstallment = null }) { Text("返回") } }
        )
    }

    prepaying?.takeUnless { privacyEnabled }?.let { plan ->
        PrepaySheet(
            plan = plan,
            accounts = accounts,
            onConfirm = { cashId, principalCents, feeCents, note ->
                onPrepay(cashId, plan.id, principalCents, feeCents, note)
                prepaying = null
            },
            onDismiss = { prepaying = null }
        )
    }

    settling?.takeUnless { privacyEnabled }?.let { plan ->
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
    deleteConfirm?.takeUnless { privacyEnabled }?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("永久删除贷款计划？") },
            text = {
                val accountLabel = accounts.firstOrNull { it.id == plan.accountId }?.name ?: "贷款"
                Text(
                    "「${if (privacyEnabled) privacyObfuscatedText(accountLabel, 700) else accountLabel}」的计划与还款历史将永久删除，已记流水保留。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(plan.id); deleteConfirm = null }) { Text("永久删除", color = DeficitRed) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("取消") } }
        )
    }

    // 完整计划使用宽松的逐期列表：主列表只放期数、日期、应还金额和状态，
    // 本金/利息/费用留到点进单期后编辑，避免所有数字挤在同一行。
    fullPlan?.takeUnless { privacyEnabled }?.let { plan ->
        val schedule = jsonToInstallments(plan.installmentsJson)
        Sheet(
            title = "完整还款计划 · ${schedule.size}期",
            onDismiss = { fullPlan = null }
        ) {
            if (schedule.isEmpty()) {
                Text("当前还没有逐期计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                schedule.forEachIndexed { index, inst ->
                    val paid = inst.status == InstallmentStatus.PAID
                    val dueDate = java.time.LocalDate.ofEpochDay(inst.dueDateEpochDay)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (paid) IncomeGreen.copy(alpha = 0.08f)
                                else PendingOrange.copy(alpha = 0.08f)
                            )
                            .clickable {
                                fullPlan = null
                                editingInstallment = plan to inst
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "第${inst.number}期 · ${if (paid) "已还" else "待还"}",
                                fontWeight = FontWeight.Bold,
                                color = if (paid) IncomeGreen else PendingOrange
                            )
                            Text(
                                "${dueDate.year}年${dueDate.monthValue}月${dueDate.dayOfMonth}日",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            formatMoney(inst.total.cents),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (paid) RepaymentPurple else ExpenseRed
                        )
                    }
                    if (index < schedule.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // 单期编辑（REQ 贷款页§5/§15）：金额与状态可改
    editingInstallment?.takeUnless { privacyEnabled }?.let { (plan, inst) ->
        var due by remember { mutableStateOf(java.time.LocalDate.ofEpochDay(inst.dueDateEpochDay).toString()) }
        var total by remember { mutableStateOf("%.2f".format(inst.total.cents / 100.0)) }
        var p by remember { mutableStateOf("%.2f".format(inst.principal.cents / 100.0)) }
        var i by remember { mutableStateOf("%.2f".format(inst.interest.cents / 100.0)) }
        var f by remember { mutableStateOf("%.2f".format(inst.fee.cents / 100.0)) }
        var st by remember { mutableStateOf(inst.status.name) }
        var showSplit by remember { mutableStateOf(false) }
        var editError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editingInstallment = null },
            title = { Text("第${inst.number}期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField(value = due, onValueChange = { due = it.filter { c -> c.isDigit() || c == '-' }.take(10); editError = null }, label = "还款日期（YYYY-MM-DD）")
                    FormField(value = total, onValueChange = { total = it.filter { c -> c.isDigit() || c == '.' }; editError = null }, label = "本期待还金额", isAmount = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = st == "PAID", onClick = { st = "PAID" }, label = { Text("已还") })
                        FilterChip(selected = st == "UPCOMING", onClick = { st = "UPCOMING" }, label = { Text("待还") })
                    }
                    TextButton(onClick = { showSplit = !showSplit }) { Text(if (showSplit) "收起高级拆分" else "高级拆分（可选）") }
                    if (showSplit) {
                        FormField(value = p, onValueChange = { p = it.filter { c -> c.isDigit() || c == '.' }; editError = null }, label = "本金", isAmount = true)
                        FormField(value = i, onValueChange = { i = it.filter { c -> c.isDigit() || c == '.' }; editError = null }, label = "利息", isAmount = true)
                        FormField(value = f, onValueChange = { f = it.filter { c -> c.isDigit() || c == '.' }; editError = null }, label = "手续费", isAmount = true)
                    }
                    editError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeficitRed) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dueEpochDay = runCatching { java.time.LocalDate.parse(due).toEpochDay() }.getOrNull()
                    if (dueEpochDay == null) {
                        editError = "还款日期格式不正确"
                        return@TextButton
                    }
                    val pc: Long?
                    val ic: Long?
                    val fc: Long?
                    if (showSplit) {
                        pc = moneyInputToCents(p, allowZero = true)
                        ic = moneyInputToCents(i, allowZero = true)
                        fc = moneyInputToCents(f, allowZero = true)
                    } else {
                        val totalCents = moneyInputToCents(total)
                        val principalCents = inst.principal.cents
                        val feeCents = inst.fee.cents
                        if (totalCents == null || totalCents < principalCents + feeCents) {
                            editError = "本期待还不能低于本期本金；如需调整本金请展开高级拆分"
                            return@TextButton
                        }
                        pc = principalCents
                        ic = totalCents - principalCents - feeCents
                        fc = feeCents
                    }
                    if (pc == null || ic == null || fc == null || pc + ic + fc <= 0L) {
                        editError = "本期金额格式不正确"
                        return@TextButton
                    }
                    onUpdateInstallment(plan.id, inst.number, dueEpochDay, pc, ic, fc, st)
                    editingInstallment = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingInstallment = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun LoanDashboardCard(
    dashboard: LoanDashboardUi?,
    metrics: V5Metrics?,
    transactions: List<com.assetsking.database.TransactionEntity>,
    plans: List<LoanPlanEntity>,
    cardInstallmentForecastChargeCents: Long,
    calendarCount: Int,
    calendarExpanded: Boolean,
    onToggleCalendar: () -> Unit,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyFrame = LocalPrivacyChaosFrame.current
    GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (dashboard == null || metrics == null) {
                Text("加载负债数据…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val loanForecastChargeCents = plans.filter { it.status != "PAID_OFF" }.sumOf { plan ->
                jsonToInstallments(plan.installmentsJson)
                    .filter { it.status != InstallmentStatus.PAID && it.dueDateEpochDay > java.time.LocalDate.now().toEpochDay() }
                    .sumOf { it.interest.cents + it.fee.cents }
            }
            val futurePlanChargeCents = loanForecastChargeCents + cardInstallmentForecastChargeCents
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("当前总负债", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (privacyEnabled) privacyFakeAmount(0) else formatMoney(dashboard.totalDebtCents),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = debtAmountColor(dashboard.totalDebtCents)
                    )
                }
                Text(
                    if (privacyEnabled) privacyScrambleText(v5TrendLabel(metrics.trend), 800) else v5TrendLabel(metrics.trend),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (dashboard.netDebtReductionCents >= 0L) IncomeGreen else DeficitRed,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background((if (dashboard.netDebtReductionCents >= 0L) IncomeGreen else DeficitRed).copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric(
                    label = "本月待还",
                    cents = dashboard.mustRepayCents,
                    color = ExpenseRed,
                    icon = Icons.Filled.CalendarMonth,
                    modifier = Modifier.weight(1f)
                )
                DashboardMetric(
                    label = "本月已还",
                    cents = dashboard.monthPaidCents,
                    color = RepaymentPurple,
                    icon = Icons.Filled.Paid,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric(
                    label = "本月净降债",
                    cents = dashboard.netDebtReductionCents,
                    color = if (dashboard.netDebtReductionCents >= 0L) IncomeGreen else DeficitRed,
                    icon = Icons.Filled.TrendingDown,
                    signed = dashboard.netDebtReductionCents > 0L,
                    modifier = Modifier.weight(1f)
                )
                DashboardMetric(
                    label = "7日内到期",
                    cents = dashboard.due7DaysCents,
                    color = PendingOrange,
                    icon = Icons.Filled.AccountBalanceWallet,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("负债构成", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                DebtCompositionBar(dashboard.composition, privacyEnabled)
                if (privacyEnabled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("贷款", "信用卡", "其他").forEachIndexed { index, label ->
                            val color = debtCompositionSemanticColor(listOf("LOAN", "CARD", "OTHER")[index])
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(privacyFakeAmount(index + 1), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else if (dashboard.composition.isEmpty()) {
                    Text("暂无负债", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dashboard.composition.chunked(2).forEach { rowItems ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowItems.forEach { item ->
                                    val color = debtCompositionSemanticColor(item.key)
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                                            Text(item.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(formatMoney(item.cents), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (privacyEnabled || futurePlanChargeCents > 0L) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(ForecastChargeYellow))
                            Text("未来息费（未入账）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (privacyEnabled) privacyFakeAmount(4) else formatMoney(futurePlanChargeCents),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = ForecastChargeYellow,
                            maxLines = 1
                        )
                        Text(
                            if (privacyEnabled) {
                                "息费组成　普贷利费 ${privacyFakeAmount(7)} ＋ 信贷利费 ${privacyFakeAmount(8)}"
                            } else {
                                "息费组成　普贷利费 ${formatMoney(loanForecastChargeCents)} ＋ 信贷利费 ${formatMoney(cardInstallmentForecastChargeCents)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("未计入当前总负债", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val privacyRepaymentProgress = animatePrivacyValue(
                    privacyFrame.progressFractions[0],
                    "privacy-loan-repayment"
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("本月还款进度", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (privacyEnabled) "${(privacyRepaymentProgress * 100).toInt()}%" else "${(dashboard.repaymentProgress * 100).toInt()}%",
                        color = RepaymentPurple,
                        fontWeight = FontWeight.Bold
                    )
                }
                LinearProgressIndicator(
                    progress = { if (privacyEnabled) privacyRepaymentProgress else dashboard.repaymentProgress },
                    modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(50)),
                    color = RepaymentPurple,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    if (privacyEnabled) {
                        "已还 ${privacyFakeAmount(5)} · 待还 ${privacyFakeAmount(6)}"
                    } else {
                        "已还 ${formatMoney(dashboard.monthPaidCents)} · 待还 ${formatMoney(dashboard.mustRepayCents)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onToggleCalendar,
                    enabled = calendarCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (calendarExpanded) "收起还款日历"
                        else if (privacyEnabled) "还款日历（${privacyFakeCount(1_000)}）"
                        else "还款日历（$calendarCount）",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = onToggleDetails, modifier = Modifier.weight(1f)) {
                    Text(if (detailsExpanded) "收起详细口径" else "查看详细口径", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (detailsExpanded) {
                val loanPayments = transactions.filter { it.type == "LOAN_PAYMENT" || it.type == "LOAN_PREPAYMENT" }
                val paidPrincipal = loanPayments.sumOf { if (it.principalCents > 0L) it.principalCents else it.amountCents }
                val paidInterest = loanPayments.sumOf { it.interestCents }
                val paidFee = loanPayments.sumOf { it.feeCents }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DashboardDetailRow(
                    "当前阶段",
                    if (privacyEnabled) privacyScrambleText(v5StageLabel(metrics.stage), 801) else v5StageLabel(metrics.stage)
                )
                DashboardDetailRow(
                    "累计已还本金",
                    if (privacyEnabled) privacyFakeAmount(802) else formatMoney(paidPrincipal),
                    RepaymentPurple
                )
                DashboardDetailRow(
                    "累计已付利息 / 手续费",
                    if (privacyEnabled) "${privacyFakeAmount(803)} / ${privacyFakeAmount(804)}"
                    else "${formatMoney(paidInterest)} / ${formatMoney(paidFee)}"
                )
                DashboardDetailRow(
                    "逾期息费",
                    if (privacyEnabled) privacyFakeAmount(805) else formatMoney(metrics.accruedInterestCents),
                    AccruedChargeDebtColor
                )
                DashboardDetailRow(
                    "普通贷款未来息费",
                    if (privacyEnabled) privacyFakeAmount(807) else formatMoney(loanForecastChargeCents),
                    PendingOrange
                )
                DashboardDetailRow(
                    "信用分期未来息费",
                    if (privacyEnabled) privacyFakeAmount(808) else formatMoney(cardInstallmentForecastChargeCents),
                    PendingOrange
                )
                DashboardDetailRow(
                    "已结清项目",
                    if (privacyEnabled) "${privacyFakeCount(806)} 笔" else "${plans.count { it.status == "PAID_OFF" }} 笔",
                    IncomeGreen
                )
            }
        }
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    cents: Long,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    signed: Boolean = false
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.09f))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (LocalPrivacyEnabled.current) {
                privacyFakeAmount(label.hashCode())
            } else {
                (if (signed && cents > 0L) "+" else "") + formatMoneyCompact(cents)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }

}

@Composable
private fun LoanPlanMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun DebtCompositionBar(items: List<DebtCompositionItem>, privacyEnabled: Boolean) {
    val total = items.sumOf { it.cents }.toDouble()
    val privacyFractions = LocalPrivacyChaosFrame.current.innerRingFractions.take(3).mapIndexed { index, fraction ->
        animatePrivacyValue(fraction, "privacy-loan-composition-$index")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(50)),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        if (privacyEnabled) {
            privacyFractions.forEachIndexed { index, fraction ->
                Box(
                    Modifier
                        .weight(fraction.coerceAtLeast(0.015f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(debtCompositionSemanticColor(listOf("LOAN", "CARD", "OTHER")[index]))
                )
            }
        } else if (total > 0.0) {
            items.forEach { item ->
                Box(
                    Modifier
                        .weight((item.cents / total).toFloat().coerceAtLeast(0.015f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(debtCompositionSemanticColor(item.key))
                )
            }
        }
    }
}

@Composable
private fun DashboardDetailRow(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}

internal fun currentCreditDueDate(account: AccountEntity, today: java.time.LocalDate): java.time.LocalDate? {
    val statementDay = account.statementDay ?: return null
    val dueDay = account.dueDay ?: return null
    val currentMonth = java.time.YearMonth.from(today)
    val thisStatement = currentMonth.atDay(statementDay.coerceAtMost(currentMonth.lengthOfMonth())).let { candidate ->
        if (candidate.isAfter(today)) candidate.minusMonths(1) else candidate
    }
    val statementMonth = java.time.YearMonth.from(thisStatement)
    val sameMonthDue = statementMonth.atDay(dueDay.coerceAtMost(statementMonth.lengthOfMonth()))
    if (sameMonthDue.isAfter(thisStatement)) return sameMonthDue
    val nextMonth = statementMonth.plusMonths(1)
    return nextMonth.atDay(dueDay.coerceAtMost(nextMonth.lengthOfMonth()))
}

internal fun nextCreditDueDate(account: AccountEntity, today: java.time.LocalDate): java.time.LocalDate? {
    if (account.statementDay == null || account.dueDay == null) return null
    return currentCreditDueDate(account, cardStatementCycle(account.statementDay, today).nextStatementDate)
}

@Composable
private fun CreditAccountPayableCard(
    account: AccountEntity,
    statementRemainingCents: Long,
    installments: List<CreditCardInstallmentEntity>,
    schedules: List<CreditCardInstallmentScheduleEntity>,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenAccount: () -> Unit,
    installmentContent: @Composable () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyIndex = account.id.hashCode()
    val projection = creditAccountRepaymentProjection(
        account = account,
        statementRemainingCents = statementRemainingCents,
        cardInstallments = installments,
        cardSchedules = schedules
    )
    val today = java.time.LocalDate.now()
    val nextDate = projection.nextDueDay?.let(java.time.LocalDate::ofEpochDay)
    val nextOverdue = projection.nextTotalCents > 0L && nextDate?.isBefore(today) == true
    val nearDue = projection.nextTotalCents > 0L && nextDate != null && !nextOverdue && !nextDate.isAfter(today.plusDays(3))
    GlassCard(
        Modifier.fillMaxWidth(),
        contentPadding = Modifier
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(enabled = enabled, onClick = onToggle),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (privacyEnabled) privacyObfuscatedText(account.name, 1_320 + privacyIndex) else account.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "当前总欠款",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (privacyEnabled) privacyFakeAmount(1_330 + privacyIndex) else formatMoney(projection.currentDebtCents),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = debtAmountColor(projection.currentDebtCents)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (privacyEnabled) "每月${privacyFakeCount(1_340 + privacyIndex)}日出账" else account.statementDay?.let { "每月${it}日出账" } ?: "未设置出账日",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (privacyEnabled) "每月${privacyFakeCount(1_341 + privacyIndex)}日还款" else account.dueDay?.let { "每月${it}日还款" } ?: "未设置还款日",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            nextOverdue -> DeficitRed
                            nearDue -> PendingOrange
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起信用账户详情" else "展开信用账户详情"
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CreditAccountHeadlineMetric(
                    label = "下次预计还款",
                    value = if (privacyEnabled) {
                        privacyFakeAmount(1_351 + privacyIndex)
                    } else if (projection.nextMonthNeedsReview) {
                        "待核对"
                    } else {
                        formatMoney(projection.nextMonthTotalCents)
                    },
                    color = if (projection.nextMonthNeedsReview && !privacyEnabled) PendingOrange else debtAmountColor(projection.nextMonthTotalCents),
                    modifier = Modifier.weight(1f)
                )
                CreditAccountHeadlineMetric(
                    label = "本次还款",
                    value = if (privacyEnabled) privacyFakeAmount(1_350 + privacyIndex) else formatMoney(projection.statementDebtCents),
                    color = if (projection.statementDebtCents > 0L) ExpenseRed else IncomeGreen,
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("按账期", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DashboardDetailRow(
                    "本期已出账应还",
                    if (privacyEnabled) privacyFakeAmount(1_353 + privacyIndex) else formatMoney(projection.statementDebtCents),
                    if (projection.statementDebtCents > 0L) ExpenseRed else IncomeGreen
                )
                DashboardDetailRow(
                    "未出账消费",
                    if (privacyEnabled) privacyFakeAmount(1_360 + privacyIndex)
                    else if (projection.nextBreakdownNeedsReview) "待核对"
                    else formatMoney(projection.unbilledOrdinaryDebtCents),
                    if (projection.nextBreakdownNeedsReview && !privacyEnabled) PendingOrange else debtAmountColor(projection.unbilledOrdinaryDebtCents)
                )
                Text("欠款构成", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DashboardDetailRow(
                    "普通账款",
                    if (privacyEnabled) privacyFakeAmount(1_364 + privacyIndex) else formatMoney(projection.otherDebtCents),
                    debtAmountColor(projection.otherDebtCents)
                )
                DashboardDetailRow(
                    "分期剩余本金",
                    if (privacyEnabled) privacyFakeAmount(1_365 + privacyIndex) else formatMoney(projection.installmentPrincipalCents),
                    debtAmountColor(projection.installmentPrincipalCents)
                )
                DashboardDetailRow(
                    "预计未来息费",
                    if (privacyEnabled) privacyFakeAmount(1_366 + privacyIndex) else formatMoney(projection.forecastChargeCents),
                    PendingOrange
                )
                DashboardDetailRow(
                    "预计全部还款",
                    if (privacyEnabled) privacyFakeAmount(1_363 + privacyIndex)
                    else if (projection.accountDebtNeedsReview) "待核对"
                    else formatMoney(projection.forecastTotalRepaymentCents),
                    if (projection.accountDebtNeedsReview && !privacyEnabled) PendingOrange else debtAmountColor(projection.forecastTotalRepaymentCents)
                )
                if (!privacyEnabled && projection.accountDebtNeedsReview) {
                    Text("账单与账面待核对", style = MaterialTheme.typography.labelSmall, color = PendingOrange)
                }
                if (projection.nextTotalCents > 0L) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text(
                                if (projection.nextIsStatementPayment) "本期账单" else "下次预计还款",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (privacyEnabled) privacyFakeDateTime(1_367 + privacyIndex).substringBefore(' ')
                                else nextDate?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: "日期待定",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            if (privacyEnabled) privacyFakeAmount(1_368 + privacyIndex) else formatMoney(projection.nextTotalCents),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (nextOverdue) DeficitRed else ExpenseRed
                        )
                    }
                    if (!privacyEnabled && projection.nextBreakdownNeedsReview) {
                        Text("账单拆分待核对", style = MaterialTheme.typography.labelSmall, color = PendingOrange)
                    } else {
                        DashboardDetailRow("其中普通账款", if (privacyEnabled) privacyFakeAmount(1_369 + privacyIndex) else formatMoney(projection.nextOtherDebtCents))
                        DashboardDetailRow("其中分期本金", if (privacyEnabled) privacyFakeAmount(1_370 + privacyIndex) else formatMoney(projection.nextInstallmentPrincipalCents))
                        DashboardDetailRow("其中预计息费", if (privacyEnabled) privacyFakeAmount(1_371 + privacyIndex) else formatMoney(projection.nextInstallmentChargeCents), PendingOrange)
                    }
                }
                if (installments.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        if (privacyEnabled) "分期 · ${privacyFakeCount(605 + privacyIndex)} 笔" else "分期 · ${installments.size} 笔",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    installmentContent()
                }
                OutlinedButton(onClick = onOpenAccount, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("查看账户明细")
                }
                if (!privacyEnabled && (account.statementDay == null || account.dueDay == null)) {
                    Text("请补充出账日和还款日", style = MaterialTheme.typography.labelSmall, color = PendingOrange)
                }
            }
        }
    }
}

@Composable
private fun CreditAccountHeadlineMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = horizontalAlignment) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        supporting?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

internal data class CreditInstallmentForecastUi(
    val remainingPrincipalCents: Long,
    val forecastChargeCents: Long,
    val totalRepaymentCents: Long,
    val unclassifiedChargeCents: Long
)

internal fun creditInstallmentForecastUi(
    installment: CreditCardInstallmentEntity,
    schedules: List<CreditCardInstallmentScheduleEntity>
): CreditInstallmentForecastUi {
    val upcoming = schedules.filter {
        it.revision == installment.scheduleRevision && it.status == "UPCOMING"
    }
    val forecastCharges = upcoming.sumOf { schedule ->
        (schedule.expectedInterestCents - schedule.interestPaidCents).coerceAtLeast(0L) +
            (schedule.expectedFeeCents - schedule.feePaidCents).coerceAtLeast(0L) +
            schedule.expectedUnclassifiedChargeCents.coerceAtLeast(0L)
    }
    val unclassifiedCharges = upcoming.sumOf { it.expectedUnclassifiedChargeCents.coerceAtLeast(0L) }
    val remainingPrincipal = installment.remainingPrincipalCents.coerceAtLeast(0L)
    return CreditInstallmentForecastUi(
        remainingPrincipalCents = remainingPrincipal,
        forecastChargeCents = forecastCharges,
        totalRepaymentCents = remainingPrincipal + forecastCharges,
        unclassifiedChargeCents = unclassifiedCharges
    )
}

@Composable
private fun CardInstallmentCard(
    installment: CreditCardInstallmentEntity,
    schedules: List<CreditCardInstallmentScheduleEntity>,
    expanded: Boolean,
    actionsEnabled: Boolean,
    embedded: Boolean = false,
    onToggle: () -> Unit,
    onViewPlan: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyIndex = installment.id.hashCode()
    val currentSchedules = schedules
        .filter { it.revision == installment.scheduleRevision && it.status != "CANCELLED" }
        .sortedBy { it.number }
    val nextSchedule = currentSchedules.firstOrNull { it.status == "UPCOMING" }
    val nextScheduleTotal = nextSchedule?.let {
        (it.principalDueCents - it.principalPaidCents).coerceAtLeast(0L) +
            (it.expectedInterestCents - it.interestPaidCents).coerceAtLeast(0L) +
            (it.expectedFeeCents - it.feePaidCents).coerceAtLeast(0L) +
            it.expectedUnclassifiedChargeCents.coerceAtLeast(0L)
    } ?: 0L
    val forecast = creditInstallmentForecastUi(installment, schedules)
    val content: @Composable () -> Unit = {
        Column(
            modifier = if (embedded) {
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
            } else {
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
            },
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(enabled = actionsEnabled, onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (privacyEnabled) privacyObfuscatedText(installment.label, 1_400 + privacyIndex) else installment.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (privacyEnabled) {
                            privacyObfuscatedText(if (installment.installmentType == "STATEMENT_INSTALLMENT") "账单分期" else "单笔消费分期", 1_410 + privacyIndex)
                        } else {
                            if (installment.installmentType == "STATEMENT_INSTALLMENT") "账单分期" else "单笔消费分期"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起分期详情" else "展开分期详情"
                )
            }
            DashboardDetailRow(
                "分期剩余本金",
                if (privacyEnabled) privacyFakeAmount(1_420 + privacyIndex) else formatMoney(installment.remainingPrincipalCents),
                debtAmountColor(installment.remainingPrincipalCents)
            )
            DashboardDetailRow(
                if (privacyEnabled) {
                    "下期 ${privacyFakeDateTime(1_450 + privacyIndex).substringBefore(' ')}"
                } else {
                    nextSchedule?.let {
                        val date = java.time.LocalDate.ofEpochDay(it.dueDateEpochDay)
                        "下期 ${date.monthValue}月${date.dayOfMonth}日"
                    } ?: "暂无下期"
                },
                if (privacyEnabled) privacyFakeAmount(1_430 + privacyIndex) else formatMoney(nextScheduleTotal),
                debtAmountColor(nextScheduleTotal)
            )
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DashboardDetailRow(
                    "预计剩余息费",
                    if (privacyEnabled) privacyFakeAmount(1_455 + privacyIndex) else formatMoney(forecast.forecastChargeCents),
                    PendingOrange
                )
                DashboardDetailRow(
                    "预计分期总还款",
                    if (privacyEnabled) privacyFakeAmount(1_456 + privacyIndex) else formatMoney(forecast.totalRepaymentCents),
                    debtAmountColor(forecast.totalRepaymentCents)
                )
                if (currentSchedules.isNotEmpty()) {
                    Text("最近三期", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    nearbyCardInstallmentSchedules(currentSchedules).forEachIndexed { index, schedule ->
                        val date = java.time.LocalDate.ofEpochDay(schedule.dueDateEpochDay)
                        val forecastCharge = schedule.expectedInterestCents + schedule.expectedFeeCents +
                            schedule.expectedUnclassifiedChargeCents
                        val expected = schedule.principalDueCents + forecastCharge
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (privacyEnabled) "第${privacyFakeCount(1_490 + index)}期 · ${privacyFakeDateTime(1_500 + index).substringBefore(' ')}"
                                    else "第${schedule.number}期 · ${date.monthValue}月${date.dayOfMonth}日",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!privacyEnabled && forecastCharge > 0L) {
                                    Text(
                                        "本金 ${formatMoney(schedule.principalDueCents)} · 预计息费 ${formatMoney(forecastCharge)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                if (privacyEnabled) privacyFakeAmount(1_510 + index) else formatMoney(expected),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                OutlinedButton(onClick = onViewPlan, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("查看完整计划")
                }
            }
        }
    }
    if (embedded) {
        content()
    } else {
        GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) { content() }
    }
}

internal fun planRemaining(plan: LoanPlanEntity): Long =
    if (plan.remainingPrincipalCents > 0) plan.remainingPrincipalCents
    else (plan.principalCents - plan.earlyRepaidCents).coerceAtLeast(0)

/**
 * 正数表示还有本金未排入未来计划，负数表示未来计划本金超过当前剩余本金。
 * 旧数据可能只有部分期次；这种情况不能把期次合计冒充为整笔贷款的预计总还款。
 */
internal fun loanSchedulePrincipalGapCents(
    plan: LoanPlanEntity,
    installments: List<LoanInstallment>
): Long = planRemaining(plan) - installments
    .filter { it.status != InstallmentStatus.PAID }
    .sumOf { it.principal.cents }

@Composable
private fun PrepaySheet(
    plan: LoanPlanEntity,
    accounts: List<AccountEntity>,
    onConfirm: (String, Long, Long, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val assetAccounts = accounts.filter { it.type == com.assetsking.model.AccountType.ASSET.name }
    var cashAccountId by remember(assetAccounts) { mutableStateOf(assetAccounts.firstOrNull()?.id.orEmpty()) }
    var principal by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val principalCents = moneyInputToCents(principal)
    val feeCents = if (fee.isBlank()) 0L else moneyInputToCents(fee)
    val actualPaymentCents = if (principalCents != null && feeCents != null) principalCents + feeCents else null

    Sheet(title = "提前还款", onDismiss = onDismiss) {
        Text("剩余本金 ${formatMoney(planRemaining(plan))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("本金用于冲减贷款余额；手续费/违约金只计入本次实际支出，不冲减本金。银行给出新计划后，用「编辑」更新计划。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FormField(value = principal, onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' } }, label = "提前还本金", isAmount = true)
        Spacer(Modifier.height(8.dp))
        FormField(value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() || c == '.' } }, label = "手续费 / 违约金（可选）", isAmount = true)
        actualPaymentCents?.let {
            Spacer(Modifier.height(8.dp))
            LoanPlanMetric("本次实际扣款", formatMoney(it), ExpenseRed, Modifier.fillMaxWidth())
        }
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
                val parsedPrincipal = principalCents ?: return@Button
                val parsedFee = feeCents ?: return@Button
                onConfirm(cashAccountId, parsedPrincipal, parsedFee, note.trim().takeIf { it.isNotEmpty() })
            },
            enabled = principalCents?.let { it in 1..planRemaining(plan) } == true && feeCents != null && cashAccountId.isNotBlank(),
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
private fun CreditInstallmentSheet(
    candidates: List<CardInstallmentCandidate>,
    accounts: List<AccountEntity>,
    cardRemainingDueByCard: Map<String, Long>,
    onCreate: (CreditCardInstallmentDraft, (Result<String>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var billingStatus by remember { mutableStateOf(CardInstallmentBillingStatus.POSTED) }
    val isPosted = billingStatus == CardInstallmentBillingStatus.POSTED
    val statementCards = remember(candidates, accounts, cardRemainingDueByCard) {
        eligibleStatementInstallmentAccounts(accounts, cardRemainingDueByCard, candidates)
    }
    fun suggestedFirstDue(card: AccountEntity?): java.time.LocalDate {
        val today = java.time.LocalDate.now()
        return currentCreditDueDate(card ?: return today.plusMonths(1), today)?.plusMonths(1)
            ?: today.plusMonths(1)
    }
    var selectedIds by remember(billingStatus) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedCardId by remember(billingStatus, statementCards) {
        mutableStateOf(if (isPosted) statementCards.firstOrNull()?.id.orEmpty() else "")
    }
    var query by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var principal by remember { mutableStateOf("") }
    var periods by remember { mutableStateOf("12") }
    var periodSelection by remember(billingStatus) { mutableStateOf("12") }
    var firstDueDate by remember(billingStatus, statementCards) {
        mutableStateOf(
            if (isPosted) {
                suggestedFirstDue(statementCards.firstOrNull()).toString()
            } else {
                java.time.LocalDate.now().plusMonths(1).toString()
            }
        )
    }
    var pricingInput by remember { mutableStateOf(CardInstallmentPricingInput.FIXED_PAYMENT) }
    var fixedPayment by remember { mutableStateOf("") }
    var interestPerPeriod by remember { mutableStateOf("") }
    var feePerPeriod by remember { mutableStateOf("") }
    var customDrafts by remember(billingStatus) { mutableStateOf<List<CustomLoanInstallmentDraft>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val eligibleCandidates = if (isPosted) {
        val eligibleCardIds = statementCards.mapTo(mutableSetOf()) { it.id }
        candidates.filter {
            it.billingStatus == CardInstallmentBillingStatus.POSTED && it.cardAccountId in eligibleCardIds
        }
    } else {
        candidates.filter { it.billingStatus == CardInstallmentBillingStatus.UNBILLED }
    }
    val selectedCandidates = eligibleCandidates.filter { it.transactionId in selectedIds }
    val cardOptions = remember(billingStatus, eligibleCandidates, statementCards) {
        if (isPosted) {
            statementCards.map { it.id to it.name }
        } else {
            listOf("" to "全部信用账户") + eligibleCandidates.distinctBy { it.cardAccountId }.map { it.cardAccountId to it.cardName }
        }
    }
    val selectedCardOption = cardOptions.firstOrNull { it.first == selectedCardId }
        ?: cardOptions.firstOrNull()
        ?: ("" to "暂无可分期账单")
    val filteredCandidates = filterCardInstallmentCandidates(eligibleCandidates, selectedCardId, query)
    val statementRemainingCents = cardRemainingDueByCard[selectedCardId] ?: 0L
    val selectedAvailableCents = selectedCandidates.sumOf { it.availablePrincipalCents }
    val principalCents = moneyInputToCents(principal)
    val periodCount = periods.toIntOrNull()
    val dueEpochDay = runCatching { java.time.LocalDate.parse(firstDueDate).toEpochDay() }.getOrNull()
    val interestCents = moneyInputToCents(interestPerPeriod, allowZero = true) ?: 0L
    val feeCents = moneyInputToCents(feePerPeriod, allowZero = true) ?: 0L
    val fixedPaymentCents = moneyInputToCents(fixedPayment)
    val costEstimate = if (principalCents != null && fixedPaymentCents != null && periodCount != null) {
        estimateInstallmentCost(principalCents, fixedPaymentCents, periodCount)
    } else null
    val customValidation = if (principalCents != null && periodCount != null) {
        validateCustomLoanSchedule(customDrafts, periodCount, principalCents)
    } else null
    val principalLimit = cardInstallmentPrincipalLimit(billingStatus, statementRemainingCents, selectedCandidates)
    val valid = selectedCandidates.isNotEmpty() && principalCents != null && principalCents in 1..principalLimit &&
        periodCount != null && periodCount in 1..360 && dueEpochDay != null &&
        when (pricingInput) {
            CardInstallmentPricingInput.FIXED_PAYMENT -> costEstimate != null
            CardInstallmentPricingInput.SEPARATE_CHARGES -> true
            CardInstallmentPricingInput.CUSTOM_SCHEDULE -> customValidation?.error == null
        }

    Sheet(
        title = "创建信用分期",
        onDismiss = onDismiss,
        swipeToDismissEnabled = false
    ) {
        Text(
            if (isPosted) {
                "把本期账单中的多笔消费统一改成跨期偿还；原流水和信用账户总欠款都不会重复增加。"
            } else {
                "只改变这笔消费的还款条款：原流水、分类、消费时间和信用账户总欠款都不会重复增加。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("账单状态", fontWeight = FontWeight.Bold)
        ChipRow(
            items = CardInstallmentBillingStatus.entries,
            selected = billingStatus,
            onSelected = { status ->
                billingStatus = status
                selectedIds = emptySet()
                selectedCardId = if (status == CardInstallmentBillingStatus.POSTED) {
                    statementCards.firstOrNull()?.id.orEmpty()
                } else {
                    ""
                }
                principal = ""
                label = ""
                firstDueDate = if (status == CardInstallmentBillingStatus.POSTED) {
                    suggestedFirstDue(statementCards.firstOrNull()).toString()
                } else {
                    java.time.LocalDate.now().plusMonths(1).toString()
                }
                error = null
            },
            label = { if (it == CardInstallmentBillingStatus.POSTED) "已出账" else "未出账" },
            id = { it.name }
        )
        Text(
            if (isPosted) "已出账支持同一信用账户单选或多选，并冲减本期账单本金。" else "未出账仅支持单笔消费，不冲减当前本期账单。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(if (isPosted) "选择本期账单消费" else "选择原消费", fontWeight = FontWeight.Bold)
        if (eligibleCandidates.isEmpty()) {
            Text("当前没有可分期的未还信用卡消费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                if (isPosted) {
                    "先选信用账户，再逐笔勾选本期账单消费；勾选即表示你已核对该笔已经出账，未出账消费不要勾选。"
                } else {
                    "先筛选并选中一笔消费，选中后再填写分期条款。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ChipRow(
                items = cardOptions,
                selected = selectedCardOption,
                onSelected = {
                    selectedCardId = it.first
                    selectedIds = emptySet()
                    principal = ""
                    label = if (isPosted) "${it.second}账单分期" else ""
                    if (isPosted) {
                        firstDueDate = suggestedFirstDue(accounts.firstOrNull { account -> account.id == it.first }).toString()
                    }
                    error = null
                },
                label = { it.second },
                id = { it.first.ifBlank { "ALL" } }
            )
            Spacer(Modifier.height(8.dp))
            FormField(
                value = query,
                onValueChange = { query = it },
                label = "搜索商户或金额"
            )
            Spacer(Modifier.height(8.dp))
            filteredCandidates.take(6).forEach { candidate ->
                val picked = candidate.transactionId in selectedIds
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .clickable {
                            if (isPosted) {
                                val nextIds = if (picked) selectedIds - candidate.transactionId else selectedIds + candidate.transactionId
                                selectedIds = nextIds
                                val nextTotal = eligibleCandidates
                                    .filter { it.transactionId in nextIds }
                                    .sumOf { it.availablePrincipalCents }
                                val nextLimit = minOf(cardRemainingDueByCard[candidate.cardAccountId] ?: 0L, nextTotal)
                                principal = if (nextLimit > 0L) "%.2f".format(nextLimit / 100.0) else ""
                                if (label.isBlank()) label = "${candidate.cardName}账单分期"
                            } else {
                                selectedIds = setOf(candidate.transactionId)
                                label = "${candidate.title}分期"
                                principal = "%.2f".format(candidate.availablePrincipalCents / 100.0)
                            }
                            error = null
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = picked,
                        onCheckedChange = null
                    )
                    Column(Modifier.weight(1f)) {
                        Text(candidate.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val date = java.time.Instant.ofEpochMilli(candidate.occurredAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        Text("${candidate.cardName} · ${date.monthValue}月${date.dayOfMonth}日", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatMoney(candidate.availablePrincipalCents), fontWeight = FontWeight.Bold)
                        Text("可分本金", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (filteredCandidates.isEmpty()) {
                Text("没有符合条件的原消费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (filteredCandidates.size > 6) {
                Text(
                    "还有 ${filteredCandidates.size - 6} 笔未显示，请继续输入商户或金额缩小范围。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PendingOrange
                )
            }
        }
        if (selectedCandidates.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isPosted) "已选 ${selectedCandidates.size} 笔本期消费" else "已选原消费",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (isPosted) {
                                selectedCandidates.first().cardName
                            } else {
                                selectedCandidates.first().title
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (isPosted) {
                                "本期剩余应还 ${formatMoney(statementRemainingCents)}"
                            } else {
                                selectedCandidates.first().cardName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(formatMoney(selectedAvailableCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ExpenseRed)
                }
            }
            Spacer(Modifier.height(10.dp))
            FormField(value = label, onValueChange = { label = it }, label = "分期名称")
            Spacer(Modifier.height(8.dp))
            FormField(
                value = principal,
                onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' }; error = null },
                label = "纳入分期本金",
                isAmount = true
            )
            Text(
                if (isPosted) {
                    "已选合计 ${formatMoney(selectedAvailableCents)} · 本期可分 ${formatMoney(statementRemainingCents)} · 最终上限 ${formatMoney(principalLimit)}；本金按最早消费优先分摊。"
                } else {
                    "最多 ${formatMoney(principalLimit)}，已扣除退款和其他有效分期。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            SelectDropdownField(
                label = "常用期数",
                selectedLabel = if (periodSelection == "OTHER") "其他期数" else "${periodSelection}期",
                options = listOf(3, 6, 12, 24, 36).map { it.toString() to "${it}期" } + ("OTHER" to "其他期数"),
                onSelected = { selectedPeriod ->
                    periodSelection = selectedPeriod
                    if (selectedPeriod != "OTHER") periods = selectedPeriod
                }
            )
            if (periodSelection == "OTHER") {
                Spacer(Modifier.height(8.dp))
                FormField(value = periods, onValueChange = { periods = it.filter(Char::isDigit).take(3) }, label = "其他期数（1—360）")
            }
            Spacer(Modifier.height(8.dp))
            FormField(value = firstDueDate, onValueChange = { firstDueDate = it.filter { c -> c.isDigit() || c == '-' }.take(10) }, label = "首期还款日（YYYY-MM-DD）")
            Spacer(Modifier.height(8.dp))
            Text("计算方式", fontWeight = FontWeight.Medium)
            ChipRow(
                items = CardInstallmentPricingInput.entries,
                selected = pricingInput,
                onSelected = { pricingInput = it; error = null },
                label =(::cardInstallmentPricingLabel),
                id = { it.name }
            )
            Text(
                when (pricingInput) {
                    CardInstallmentPricingInput.FIXED_PAYMENT -> "每期金额相同；系统反推总息费和估算年化成本率。"
                    CardInstallmentPricingInput.SEPARATE_CHARGES -> "适合利息与手续费已分别给出的分期。"
                    CardInstallmentPricingInput.CUSTOM_SCHEDULE -> "每一期金额或日期不同时，生成列表后逐期修改。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        if (selectedCandidates.isNotEmpty() && pricingInput == CardInstallmentPricingInput.FIXED_PAYMENT) {
            FormField(
                value = fixedPayment,
                onValueChange = { fixedPayment = it.filter { c -> c.isDigit() || c == '.' }; error = null },
                label = "每期总还款",
                isAmount = true
            )
            if (fixedPaymentCents != null && principalCents != null && periodCount != null && costEstimate == null) {
                Text("每期总还款 × 期数不能小于分期本金。", style = MaterialTheme.typography.labelSmall, color = DeficitRed)
            }
        } else if (selectedCandidates.isNotEmpty() && pricingInput == CardInstallmentPricingInput.SEPARATE_CHARGES) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField(
                    value = interestPerPeriod,
                    onValueChange = { interestPerPeriod = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "每期预计利息",
                    isAmount = true,
                    modifier = Modifier.weight(1f)
                )
                FormField(
                    value = feePerPeriod,
                    onValueChange = { feePerPeriod = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "每期预计手续费",
                    isAmount = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else if (selectedCandidates.isNotEmpty() && pricingInput == CardInstallmentPricingInput.CUSTOM_SCHEDULE) {
            OutlinedButton(
                onClick = {
                    val count = periodCount ?: return@OutlinedButton
                    val cents = principalCents ?: return@OutlinedButton
                    val due = runCatching { java.time.LocalDate.parse(firstDueDate) }.getOrNull() ?: return@OutlinedButton
                    if (count in 1..MaxDirectCustomLoanInstallments) {
                        customDrafts = generateCustomLoanInstallmentDrafts(count, cents, due)
                    }
                },
                enabled = periodCount?.let { it in 1..MaxDirectCustomLoanInstallments } == true && principalCents != null && dueEpochDay != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (customDrafts.isEmpty()) "生成逐期填写表" else "按当前本金和期数重新生成") }
            if (customDrafts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                EditableInstallmentScheduleList(
                    drafts = customDrafts,
                    onDraftsChange = { customDrafts = it },
                    allowStatus = false
                )
            }
            customValidation?.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeficitRed) }
        }
        if (principalCents != null && periodCount != null && periodCount > 0 &&
            (pricingInput == CardInstallmentPricingInput.SEPARATE_CHARGES || costEstimate != null || customValidation?.error == null)
        ) {
            val firstPrincipal = principalCents / periodCount + if (principalCents % periodCount > 0L) 1L else 0L
            GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val estimatedPayment = when (pricingInput) {
                        CardInstallmentPricingInput.FIXED_PAYMENT -> fixedPaymentCents ?: 0L
                        CardInstallmentPricingInput.SEPARATE_CHARGES -> firstPrincipal + interestCents + feeCents
                        CardInstallmentPricingInput.CUSTOM_SCHEDULE -> customValidation?.installments?.firstOrNull()?.total?.cents ?: 0L
                    }
                    Text("预计首期 ${formatMoney(estimatedPayment)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = RepaymentPurple)
                    costEstimate?.let { estimate ->
                        Text("预计总息费 ${formatMoney(estimate.totalChargeCents)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text("估算年化成本率 ${"%.2f".format(estimate.effectiveAnnualRate * 100)}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("这是还款预测，不是实际息费流水；实际账单入库后再按真实分类记账。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DeficitRed) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val sources = selectedCandidates.ifEmpty { return@Button }
                val amount = principalCents ?: return@Button
                val count = periodCount ?: return@Button
                val due = dueEpochDay ?: return@Button
                val source = sources.first()
                val allocations = if (isPosted) {
                    runCatching { allocateStatementPrincipal(sources, amount) }
                        .getOrElse {
                            error = it.message ?: "账单本金无法分摊到已选消费"
                            return@Button
                        }
                } else {
                    listOf(CardInstallmentAllocationRequest(source.transactionId, amount))
                }
                saving = true
                error = null
                onCreate(
                    CreditCardInstallmentDraft(
                        cardAccountId = source.cardAccountId,
                        label = label.trim().ifBlank {
                            if (isPosted) "${source.cardName}账单分期" else "${source.title}分期"
                        },
                        allocations = allocations,
                        installmentCount = count,
                        firstDueDateEpochDay = due,
                        installmentType = if (isPosted) {
                            "STATEMENT_INSTALLMENT"
                        } else {
                            "POST_PURCHASE_INSTALLMENT"
                        },
                        expectedInterestCentsPerPeriod = if (pricingInput == CardInstallmentPricingInput.SEPARATE_CHARGES) interestCents else 0L,
                        expectedFeeCentsPerPeriod = if (pricingInput == CardInstallmentPricingInput.SEPARATE_CHARGES) feeCents else 0L,
                        expectedPaymentCentsPerPeriod = if (pricingInput == CardInstallmentPricingInput.FIXED_PAYMENT) fixedPaymentCents else null,
                        customSchedule = if (pricingInput == CardInstallmentPricingInput.CUSTOM_SCHEDULE) {
                            customValidation?.installments.orEmpty().map { line ->
                                CreditCardInstallmentScheduleDraft(line.dueDateEpochDay, line.total.cents)
                            }
                        } else emptyList()
                    )
                ) { result ->
                    saving = false
                    result.exceptionOrNull()?.let { error = it.message ?: "无法创建分期" }
                }
            },
            enabled = valid && !saving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "正在创建…" else "创建分期计划") }
    }
}

@Composable
private fun AdjustCardInstallmentSheet(
    installment: CreditCardInstallmentEntity,
    schedules: List<CreditCardInstallmentScheduleEntity>,
    onSave: (CreditCardInstallmentTerms, (Result<Unit>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val currentLines = schedules
        .filter { it.revision == installment.scheduleRevision && it.status == "UPCOMING" }
        .sortedBy { it.number }
    val currentLine = currentLines.firstOrNull()
    var periods by remember { mutableStateOf(installment.periodsRemaining.toString()) }
    var periodSelection by remember(installment) {
        mutableStateOf(if (installment.periodsRemaining in listOf(3, 6, 12, 24, 36)) installment.periodsRemaining.toString() else "OTHER")
    }
    var firstDueDate by remember {
        mutableStateOf(
            (installment.nextDueDateEpochDay ?: java.time.LocalDate.now().plusMonths(1).toEpochDay())
                .let(java.time.LocalDate::ofEpochDay)
                .toString()
        )
    }
    var pricingInput by remember {
        mutableStateOf(
            if ((currentLine?.expectedInterestCents ?: 0L) > 0L || (currentLine?.expectedFeeCents ?: 0L) > 0L) {
                CardInstallmentPricingInput.SEPARATE_CHARGES
            } else {
                CardInstallmentPricingInput.FIXED_PAYMENT
            }
        )
    }
    var fixedPayment by remember {
        mutableStateOf(
            currentLine?.let {
                "%.2f".format(
                    (it.principalDueCents + it.expectedInterestCents + it.expectedFeeCents + it.expectedUnclassifiedChargeCents) / 100.0
                )
            }.orEmpty()
        )
    }
    var interest by remember { mutableStateOf(currentLine?.let { "%.2f".format(it.expectedInterestCents / 100.0) }.orEmpty()) }
    var fee by remember { mutableStateOf(currentLine?.let { "%.2f".format(it.expectedFeeCents / 100.0) }.orEmpty()) }
    var customDrafts by remember(installment, schedules) {
        mutableStateOf(
            loanInstallmentsToDrafts(
                currentLines.map { line ->
                    LoanInstallment(
                        number = line.number,
                        dueDateEpochDay = line.dueDateEpochDay,
                        principal = Money(line.principalDueCents),
                        interest = Money(line.expectedInterestCents + line.expectedUnclassifiedChargeCents),
                        fee = Money(line.expectedFeeCents)
                    )
                }
            )
        )
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val count = periods.toIntOrNull()
    val due = runCatching { java.time.LocalDate.parse(firstDueDate).toEpochDay() }.getOrNull()
    val interestCents = moneyInputToCents(interest, allowZero = true)
    val feeCents = moneyInputToCents(fee, allowZero = true)
    val fixedPaymentCents = moneyInputToCents(fixedPayment)
    val costEstimate = if (count != null && fixedPaymentCents != null) {
        estimateInstallmentCost(installment.remainingPrincipalCents, fixedPaymentCents, count)
    } else null
    val customValidation = if (count != null) {
        validateCustomLoanSchedule(customDrafts, count, installment.remainingPrincipalCents)
    } else null
    val valid = count != null && count in 1..360 && due != null &&
        when (pricingInput) {
            CardInstallmentPricingInput.FIXED_PAYMENT -> costEstimate != null
            CardInstallmentPricingInput.SEPARATE_CHARGES -> interestCents != null && feeCents != null
            CardInstallmentPricingInput.CUSTOM_SCHEDULE -> customValidation?.error == null
        }

    Sheet(title = "修改分期计划", onDismiss = onDismiss, swipeToDismissEnabled = false) {
        Text("只重排剩余本金的未来期次；旧期次、原消费关联和审计记录不会被删除。需要逐期修改日期和金额时，选择「逐期自填」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SelectDropdownField(
            label = "剩余期数",
            selectedLabel = if (periodSelection == "OTHER") "其他期数" else "${periodSelection}期",
            options = listOf(3, 6, 12, 24, 36).map { it.toString() to "${it}期" } + ("OTHER" to "其他期数"),
            onSelected = { selectedPeriod ->
                periodSelection = selectedPeriod
                if (selectedPeriod != "OTHER") periods = selectedPeriod
            }
        )
        if (periodSelection == "OTHER") {
            Spacer(Modifier.height(8.dp))
            FormField(value = periods, onValueChange = { periods = it.filter(Char::isDigit).take(3) }, label = "其他期数（1—360）")
        }
        Spacer(Modifier.height(8.dp))
        FormField(value = firstDueDate, onValueChange = { firstDueDate = it.filter { c -> c.isDigit() || c == '-' }.take(10) }, label = "下一期还款日（YYYY-MM-DD）")
        Spacer(Modifier.height(8.dp))
        Text("计算方式", fontWeight = FontWeight.Medium)
        ChipRow(
            items = CardInstallmentPricingInput.entries,
            selected = pricingInput,
            onSelected = { pricingInput = it; error = null },
            label =(::cardInstallmentPricingLabel),
            id = { it.name }
        )
        if (pricingInput == CardInstallmentPricingInput.FIXED_PAYMENT) {
            FormField(value = fixedPayment, onValueChange = { fixedPayment = it.filter { c -> c.isDigit() || c == '.' } }, label = "每期总还款", isAmount = true)
            costEstimate?.let {
                Text("预计总息费 ${formatMoney(it.totalChargeCents)} · 估算年化成本率 ${"%.2f".format(it.effectiveAnnualRate * 100)}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (pricingInput == CardInstallmentPricingInput.SEPARATE_CHARGES) {
            FormField(value = interest, onValueChange = { interest = it.filter { c -> c.isDigit() || c == '.' } }, label = "每期预计利息", isAmount = true)
            Spacer(Modifier.height(8.dp))
            FormField(value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() || c == '.' } }, label = "每期预计手续费", isAmount = true)
        } else {
            OutlinedButton(
                onClick = {
                    val nextCount = count ?: return@OutlinedButton
                    val nextDue = runCatching { java.time.LocalDate.parse(firstDueDate) }.getOrNull() ?: return@OutlinedButton
                    if (nextCount in 1..MaxDirectCustomLoanInstallments) {
                        customDrafts = generateCustomLoanInstallmentDrafts(nextCount, installment.remainingPrincipalCents, nextDue)
                    }
                },
                enabled = count?.let { it in 1..MaxDirectCustomLoanInstallments } == true && due != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (customDrafts.size == count) "按当前本金和期数重新生成" else "生成逐期填写表") }
            if (customDrafts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                EditableInstallmentScheduleList(
                    drafts = customDrafts,
                    onDraftsChange = { customDrafts = it },
                    allowStatus = false
                )
            }
            customValidation?.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeficitRed) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DeficitRed) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val nextCount = count ?: return@Button
                val nextDue = due ?: return@Button
                val nextInterest = if (pricingInput == CardInstallmentPricingInput.SEPARATE_CHARGES) interestCents ?: return@Button else 0L
                val nextFee = if (pricingInput == CardInstallmentPricingInput.SEPARATE_CHARGES) feeCents ?: return@Button else 0L
                saving = true
                onSave(
                    CreditCardInstallmentTerms(
                        installmentCount = nextCount,
                        firstDueDateEpochDay = nextDue,
                        expectedInterestCentsPerPeriod = nextInterest,
                        expectedFeeCentsPerPeriod = nextFee,
                        expectedPaymentCentsPerPeriod = if (pricingInput == CardInstallmentPricingInput.FIXED_PAYMENT) fixedPaymentCents else null,
                        customSchedule = if (pricingInput == CardInstallmentPricingInput.CUSTOM_SCHEDULE) {
                            customValidation?.installments.orEmpty().map { line ->
                                CreditCardInstallmentScheduleDraft(line.dueDateEpochDay, line.total.cents)
                            }
                        } else emptyList()
                    )
                ) { result ->
                    saving = false
                    result.exceptionOrNull()?.let { error = it.message ?: "无法调整条款" }
                }
            },
            enabled = valid && !saving,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "正在保存…" else "保存新条款") }
    }
}

private fun moneyInputToCents(value: String, allowZero: Boolean = false): Long? = runCatching {
    java.math.BigDecimal(value.ifBlank { "0" }.trim())
        .movePointRight(2)
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()?.takeIf { if (allowZero) it >= 0L else it > 0L }

@Composable
private fun LoanPlanSheet(
    existingPlan: LoanPlanEntity?,
    accounts: List<AccountEntity>,
    onAddLoanAccount: () -> Unit,
    onSave: (LoanPlanEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = existingPlan != null
    val existingInstallments = remember(existingPlan) {
        existingPlan?.let { jsonToInstallments(it.installmentsJson) } ?: emptyList()
    }
    var accountId by remember(existingPlan, accounts) {
        mutableStateOf(initialLoanPlanAccountId(existingPlan?.accountId, accounts))
    }
    var principal by remember { mutableStateOf(existingPlan?.let { "%.2f".format(it.principalCents / 100.0) } ?: "") }
    var loanStartDate by remember(existingPlan) {
        mutableStateOf(
            existingPlan?.let { java.time.LocalDate.ofEpochDay(it.startDateEpochDay).toString() }
                ?: java.time.LocalDate.now().toString()
        )
    }
    val initialCount = existingInstallments.size.takeIf { it > 0 } ?: 12
    var installmentCount by remember(existingPlan) { mutableStateOf(initialCount.toString()) }
    var installmentCountSelection by remember(existingPlan) {
        mutableStateOf(if (initialCount in CommonLoanInstallmentCounts) initialCount.toString() else "OTHER")
    }
    var firstDueDate by remember {
        mutableStateOf(
            existingInstallments.firstOrNull()?.let { java.time.LocalDate.ofEpochDay(it.dueDateEpochDay).toString() }
                ?: java.time.LocalDate.now().plusMonths(1).toString()
        )
    }
    var method by remember {
        mutableStateOf(
            existingPlan?.let { runCatching { RepaymentMethod.valueOf(it.repaymentMethod) }.getOrDefault(RepaymentMethod.CUSTOM) }
                ?: RepaymentMethod.EQUAL_PAYMENT
        )
    }
    var uniformPaymentStr by remember { mutableStateOf("") }
    var uniformPaymentError by remember { mutableStateOf<String?>(null) }
    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    var paidMonths by remember { mutableStateOf(existingInstallments.count { it.status == InstallmentStatus.PAID }.toString()) }
    var showAdvanced by remember { mutableStateOf(false) }
    var customDrafts by remember(existingPlan) {
        mutableStateOf(
            if (existingPlan?.repaymentMethod == RepaymentMethod.CUSTOM.name) loanInstallmentsToDrafts(existingInstallments)
            else emptyList()
        )
    }
    var editingCustomIndex by remember { mutableStateOf<Int?>(null) }
    var rateStr by remember { mutableStateOf(existingPlan?.let { "%.2f".format(it.annualRateBps / 100.0) } ?: "") }
    var remainingStr by remember {
        mutableStateOf(existingPlan?.let { if (it.remainingPrincipalCents > 0) "%.2f".format(it.remainingPrincipalCents / 100.0) else "" } ?: "")
    }
    var earlyRepaidStr by remember {
        mutableStateOf(existingPlan?.let { if (it.earlyRepaidCents > 0) "%.2f".format(it.earlyRepaidCents / 100.0) else "" } ?: "")
    }
    var repayDayStr by remember(existingPlan) {
        mutableStateOf(
            existingPlan?.repaymentDay?.toString()
                ?: existingInstallments.firstOrNull()?.let { java.time.LocalDate.ofEpochDay(it.dueDateEpochDay).dayOfMonth.toString() }
                ?: java.time.LocalDate.now().plusMonths(1).dayOfMonth.toString()
        )
    }

    val enteredInstallmentCount = installmentCount.toIntOrNull()
    val effectivePaidCount = if (method == RepaymentMethod.CUSTOM) 0 else paidMonths.toIntOrNull()
    val countError = loanInstallmentCountError(enteredInstallmentCount, effectivePaidCount)
    val repaymentDay = repayDayStr.toIntOrNull()
    val parsedFirstDueDate = runCatching { java.time.LocalDate.parse(firstDueDate) }.getOrNull()
    val repaymentDayError = when {
        repaymentDay !in 1..31 -> "每月固定还款日须为 1—31 日"
        parsedFirstDueDate != null && loanDueDate(parsedFirstDueDate, repaymentDay!!, 0) != parsedFirstDueDate ->
            "首期日期须落在固定还款日；月底不足 29—31 日时可选当月最后一天"
        else -> null
    }
    val principalCents = moneyInputToCents(principal)
    val customValidation = if (method == RepaymentMethod.CUSTOM && principalCents != null && enteredInstallmentCount != null) {
        validateCustomLoanSchedule(customDrafts, enteredInstallmentCount, principalCents)
    } else null

    Sheet(
        title = if (isEdit) "编辑贷款计划" else "新增贷款计划",
        onDismiss = onDismiss,
        swipeToDismissEnabled = false
    ) {
        if (selectedAccount == null) {
            Text("贷款计划只能关联贷款账户", fontWeight = FontWeight.Bold)
            Text(
                "储蓄卡、信用卡和花呗不会出现在这里。请先新建一个贷款账户，再回来创建计划。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAddLoanAccount, modifier = Modifier.fillMaxWidth()) {
                Text("新建贷款账户")
            }
            return@Sheet
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            SelectDropdownField(
                label = "关联贷款账户",
                selectedLabel = selectedAccount.name,
                options = accounts.map { it.id to it.name },
                onSelected = { accountId = it },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAddLoanAccount) { Text("＋ 新建") }
        }

        Spacer(Modifier.height(8.dp))
        FormField(value = principal, onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' } }, label = "贷款本金")
        Spacer(Modifier.height(8.dp))
        FormField(
            value = loanStartDate,
            onValueChange = { loanStartDate = it.filter { c -> c.isDigit() || c == '-' }.take(10) },
            label = "借款日期（YYYY-MM-DD）"
        )

        Spacer(Modifier.height(8.dp))
        Text("还款方式", fontWeight = FontWeight.Medium)
        ChipRow(
            items = RepaymentMethod.entries,
            selected = method,
            onSelected = { method = it },
            label = { m ->
                when (m) {
                    RepaymentMethod.CUSTOM -> "逐期自填"
                    RepaymentMethod.EQUAL_PAYMENT -> "等额本息"
                    RepaymentMethod.EQUAL_PRINCIPAL -> "等额本金"
                    RepaymentMethod.INTEREST_ONLY -> "先息后本"
                }
            },
            id = { it.name }
        )

        if (method == RepaymentMethod.EQUAL_PAYMENT) {
            Spacer(Modifier.height(8.dp))
            FormField(
                value = uniformPaymentStr,
                onValueChange = {
                    uniformPaymentStr = it.filter { c -> c.isDigit() || c == '.' }
                    uniformPaymentError = null
                },
                label = "统一每期还款金额（可选）"
            )
            Text(
                "填入后保存，会一次应用到所有未还期；已还期保持不变。留空则按年利率自动计算。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            uniformPaymentError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeficitRed) }
        }

        Spacer(Modifier.height(8.dp))
        SelectDropdownField(
            label = "常用期数",
            selectedLabel = if (installmentCountSelection == "OTHER") "其他期数" else loanInstallmentCountLabel(installmentCountSelection.toInt()),
            options = CommonLoanInstallmentCounts.map { it.toString() to loanInstallmentCountLabel(it) } + ("OTHER" to "其他期数"),
            onSelected = { selected ->
                installmentCountSelection = selected
                if (selected != "OTHER") installmentCount = selected
            }
        )
        if (installmentCountSelection == "OTHER") {
            Spacer(Modifier.height(8.dp))
            FormField(value = installmentCount, onValueChange = { installmentCount = it.filter(Char::isDigit).take(3) }, label = "其他期数（1—600）")
        }
        if (method != RepaymentMethod.CUSTOM) {
            Spacer(Modifier.height(8.dp))
            FormField(value = paidMonths, onValueChange = { paidMonths = it.filter(Char::isDigit).take(3) }, label = "已还期数（0=全部待还）")
        }
        Spacer(Modifier.height(8.dp))
        FormField(
            value = firstDueDate,
            onValueChange = { firstDueDate = it.filter { c -> c.isDigit() || c == '-' }.take(10) },
            label = "首期还款日（YYYY-MM-DD）"
        )
        Spacer(Modifier.height(8.dp))
        FormField(
            value = repayDayStr,
            onValueChange = { repayDayStr = it.filter(Char::isDigit).take(2) },
            label = "每月固定还款日（必填）"
        )
        repaymentDayError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeficitRed) }

        if (method == RepaymentMethod.CUSTOM) {
            Spacer(Modifier.height(10.dp))
            Text("逐期待还", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(
                "先生成逐期列表，再像“完整计划”一样点每一期；默认只填日期、待还总额和状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val count = enteredInstallmentCount ?: return@OutlinedButton
                    val cents = principalCents ?: return@OutlinedButton
                    val firstDue = runCatching { java.time.LocalDate.parse(firstDueDate) }.getOrNull() ?: return@OutlinedButton
                    if (count in 1..MaxDirectCustomLoanInstallments) {
                        customDrafts = generateCustomLoanInstallmentDrafts(count, cents, firstDue, repaymentDay!!)
                    }
                },
                enabled = enteredInstallmentCount?.let { it in 1..MaxDirectCustomLoanInstallments } == true && principalCents != null &&
                    runCatching { java.time.LocalDate.parse(firstDueDate) }.isSuccess && repaymentDayError == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (customDrafts.isEmpty()) "生成逐期填写表" else "按当前本金和期数重新生成") }
            if (customDrafts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                customDrafts.forEachIndexed { index, draft ->
                    val paid = draft.status == InstallmentStatus.PAID
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (paid) IncomeGreen.copy(alpha = 0.08f) else PendingOrange.copy(alpha = 0.08f))
                            .clickable { editingCustomIndex = index }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("第${draft.number}期 · ${if (paid) "已还" else "待还"}", fontWeight = FontWeight.Bold, color = if (paid) IncomeGreen else PendingOrange)
                            Text(draft.dueDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("¥${customLoanDraftTotal(draft)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (paid) RepaymentPurple else ExpenseRed)
                    }
                    if (index < customDrafts.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
            customValidation?.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeficitRed) }
        }

        Spacer(Modifier.height(8.dp))
        FormField(value = rateStr, onValueChange = { rateStr = it.filter { c -> c.isDigit() || c == '.' } }, label = "年利率%（可选）")

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "收起余额修正" else "展开余额修正（可选）")
        }
        if (showAdvanced) {
            FormField(value = remainingStr, onValueChange = { remainingStr = it.filter { c -> c.isDigit() || c == '.' } }, label = "当前剩余本金")
            Spacer(Modifier.height(8.dp))
            FormField(value = earlyRepaidStr, onValueChange = { earlyRepaidStr = it.filter { c -> c.isDigit() || c == '.' } }, label = "累计提前还款")
        }

        if (countError != null) {
            Text(countError, style = MaterialTheme.typography.labelSmall, color = DeficitRed)
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = principalCents ?: return@Button

                val rateBps = parseAnnualRateBps(rateStr)
                val remaining = runCatching {
                    java.math.BigDecimal(remainingStr.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: 0L
                // Auto-generate repayment schedule if rate and count specified
                val count = installmentCount.toIntOrNull() ?: 0
                val paid = if (method == RepaymentMethod.CUSTOM) 0 else paidMonths.toIntOrNull() ?: 0
                val firstDue = runCatching { java.time.LocalDate.parse(firstDueDate) }.getOrNull()
                val startDate = runCatching { java.time.LocalDate.parse(loanStartDate) }.getOrNull() ?: return@Button
                val schedule = if (method == RepaymentMethod.CUSTOM) {
                    customValidation?.installments ?: return@Button
                } else if (count > 0 && firstDue != null) {
                    val raw = when (method) {
                        RepaymentMethod.EQUAL_PAYMENT -> LoanCalculator.equalPaymentSchedule(cents, rateBps, count, firstDue.minusDays(30).toEpochDay())
                        RepaymentMethod.EQUAL_PRINCIPAL -> LoanCalculator.equalPrincipalSchedule(cents, rateBps, count, firstDue.minusDays(30).toEpochDay())
                        RepaymentMethod.INTEREST_ONLY -> LoanCalculator.interestOnlySchedule(cents, rateBps, count, firstDue.minusDays(30).toEpochDay())
                        else -> emptyList()
                    }
                    val generated = raw.mapIndexed { idx, inst ->
                        inst.copy(
                            dueDateEpochDay = loanDueDate(firstDue, repaymentDay!!, idx).toEpochDay(),
                            status = if (idx < paid) InstallmentStatus.PAID else inst.status
                        )
                    }
                    if (method == RepaymentMethod.EQUAL_PAYMENT && uniformPaymentStr.isNotBlank()) {
                        val uniformPayment = moneyInputToCents(uniformPaymentStr)
                        if (uniformPayment == null) {
                            uniformPaymentError = "统一每期金额格式不正确"
                            return@Button
                        }
                        applyUniformPaymentToUpcoming(generated, uniformPayment) ?: run {
                            uniformPaymentError = "统一金额不能低于某期本金和手续费"
                            return@Button
                        }
                    } else generated
                } else return@Button
                onSave(
                    LoanPlanEntity(
                        id = existingPlan?.id ?: UUID.randomUUID().toString(),
                        accountId = accountId,
                        principalCents = cents,
                        startDateEpochDay = startDate.toEpochDay(),
                        repaymentMethod = method.name,
                        installmentsJson = installmentsToJson(schedule),
                        annualRateBps = rateBps,
                        remainingPrincipalCents = if (remaining > 0) remaining else cents,
                        earlyRepaidCents = runCatching { java.math.BigDecimal(earlyRepaidStr.ifBlank { "0" }.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull() ?: 0L,
                        repaymentDay = repaymentDay
                    )
                )
            },
            enabled = principalCents != null && accountId.isNotBlank() &&
                runCatching { java.time.LocalDate.parse(loanStartDate) }.isSuccess &&
                runCatching { java.time.LocalDate.parse(firstDueDate) }.isSuccess &&
                countError == null &&
                repaymentDayError == null &&
                (method != RepaymentMethod.CUSTOM || customValidation?.error == null),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }

    editingCustomIndex?.let { index ->
        customDrafts.getOrNull(index)?.let { draft ->
            CustomLoanInstallmentEditDialog(
                draft = draft,
                onSave = { changed ->
                    customDrafts = customDrafts.toMutableList().also { it[index] = changed }
                    editingCustomIndex = null
                },
                onDismiss = { editingCustomIndex = null }
            )
        }
    }
}

@Composable
private fun EditableInstallmentScheduleList(
    drafts: List<CustomLoanInstallmentDraft>,
    onDraftsChange: (List<CustomLoanInstallmentDraft>) -> Unit,
    allowStatus: Boolean
) {
    var editingIndex by remember(drafts.size) { mutableStateOf<Int?>(null) }
    drafts.forEachIndexed { index, draft ->
        val paid = draft.status == InstallmentStatus.PAID
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (paid) IncomeGreen.copy(alpha = 0.08f) else PendingOrange.copy(alpha = 0.08f))
                .clickable { editingIndex = index }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "第${draft.number}期${if (allowStatus) " · ${if (paid) "已还" else "待还"}" else ""}",
                    fontWeight = FontWeight.Bold,
                    color = if (paid) IncomeGreen else PendingOrange
                )
                Text(draft.dueDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "¥${customLoanDraftTotal(draft)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (paid) RepaymentPurple else ExpenseRed
            )
        }
        if (index < drafts.lastIndex) Spacer(Modifier.height(8.dp))
    }
    editingIndex?.let { index ->
        drafts.getOrNull(index)?.let { draft ->
            CustomLoanInstallmentEditDialog(
                draft = draft,
                allowStatus = allowStatus,
                onSave = { changed ->
                    onDraftsChange(drafts.toMutableList().also { it[index] = changed })
                    editingIndex = null
                },
                onDismiss = { editingIndex = null }
            )
        }
    }
}

@Composable
private fun CustomLoanInstallmentEditDialog(
    draft: CustomLoanInstallmentDraft,
    allowStatus: Boolean = true,
    onSave: (CustomLoanInstallmentDraft) -> Unit,
    onDismiss: () -> Unit
) {
    var dueDate by remember(draft) { mutableStateOf(draft.dueDate) }
    var total by remember(draft) { mutableStateOf(customLoanDraftTotal(draft)) }
    var principal by remember(draft) { mutableStateOf(draft.principal) }
    var interest by remember(draft) { mutableStateOf(draft.interest) }
    var fee by remember(draft) { mutableStateOf(draft.fee) }
    var status by remember(draft) { mutableStateOf(draft.status) }
    var showSplit by remember(draft) { mutableStateOf(false) }
    var error by remember(draft) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("第${draft.number}期待还") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(value = dueDate, onValueChange = { dueDate = it.filter { c -> c.isDigit() || c == '-' }.take(10); error = null }, label = "还款日期（YYYY-MM-DD）")
                FormField(value = total, onValueChange = { total = it.filter { c -> c.isDigit() || c == '.' }; error = null }, label = "本期待还金额", isAmount = true)
                if (allowStatus) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = status == InstallmentStatus.UPCOMING, onClick = { status = InstallmentStatus.UPCOMING }, label = { Text("待还") })
                        FilterChip(selected = status == InstallmentStatus.PAID, onClick = { status = InstallmentStatus.PAID }, label = { Text("已还") })
                    }
                }
                TextButton(onClick = { showSplit = !showSplit }) { Text(if (showSplit) "收起高级拆分" else "高级拆分（可选）") }
                if (showSplit) {
                    FormField(value = principal, onValueChange = { principal = it.filter { c -> c.isDigit() || c == '.' }; error = null }, label = "本金", isAmount = true)
                    FormField(value = interest, onValueChange = { interest = it.filter { c -> c.isDigit() || c == '.' }; error = null }, label = "利息", isAmount = true)
                    FormField(value = fee, onValueChange = { fee = it.filter { c -> c.isDigit() || c == '.' }; error = null }, label = "手续费", isAmount = true)
                }
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = DeficitRed) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (runCatching { java.time.LocalDate.parse(dueDate) }.isFailure) {
                    error = "还款日期格式不正确"
                    return@TextButton
                }
                val changed = if (showSplit) {
                    val p = moneyInputToCents(principal, allowZero = true)
                    val i = moneyInputToCents(interest, allowZero = true)
                    val f = moneyInputToCents(fee, allowZero = true)
                    if (p == null || i == null || f == null || p + i + f <= 0L) null
                    else draft.copy(dueDate = dueDate, principal = principal, interest = interest, fee = fee, status = status)
                } else {
                    customLoanDraftWithTotal(draft.copy(dueDate = dueDate, status = status), total)
                }
                if (changed == null) {
                    error = if (showSplit) "本金、利息或手续费格式不正确" else "本期待还不能低于本期已分配本金；如需调整请展开高级拆分"
                } else onSave(changed)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
