package com.assetsking.app.ui.screen

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.database.AccountEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.MerchantEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.ledger.AmountExpression
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.IconLibrary
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.usecase.AccountInference
import kotlin.math.roundToLong
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

// ── 编辑器顶层类型（REQ 编辑器§11）──
private enum class EditorKind(val label: String) { EXPENSE("支出"), INCOME("入账"), TRANSFER("转账"), REPAY("还款") }
private enum class IncomeSub(val label: String, val type: TransactionType) {
    INCOME("收入", TransactionType.INCOME),
    REFUND("退款", TransactionType.REFUND),
    REIMBURSEMENT("报销到账", TransactionType.REIMBURSEMENT)
}
private enum class RepaySub(val label: String) { CREDIT_CARD("信用卡还款"), LOAN("贷款还款") }

private val channelOptions = listOf("微信支付", "支付宝", "银行短信", "其他")

/**
 * 统一全屏交易编辑器（REQ 编辑器 §1-29）：手动记账与待确认复用同一流程。
 * 类型顶部切换、分类宫格原地展开二级、计算键盘、动态字段、必填校验确认按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TransactionEditorScreen(
    pendingItem: PendingItem?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    merchants: List<MerchantEntity>,
    loanPlans: List<LoanPlanEntity>,
    transactions: List<TransactionEntity>,
    reimbursableTxs: List<TransactionEntity>,
    merchantLastAccount: Map<String, String>,
    ignoredItems: List<com.assetsking.database.RawNotificationEntity>,
    viewModel: LedgerViewModel,
    repository: LedgerRepository,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val parsed = pendingItem?.parsed
    var kind by remember {
        mutableStateOf(
            when {
                pendingItem == null -> EditorKind.EXPENSE
                parsed?.isExpense == false -> EditorKind.INCOME
                else -> EditorKind.EXPENSE
            }
        )
    }
    var incomeSub by remember { mutableStateOf(IncomeSub.INCOME) }
    var repaySub by remember { mutableStateOf(RepaySub.CREDIT_CARD) }
    var amountExpr by remember { mutableStateOf(parsed?.amountCents?.let { "%.2f".format(it / 100.0) } ?: "") }
    var occurredAt by remember { mutableStateOf(pendingItem?.notification?.postedAt ?: System.currentTimeMillis()) }
    val inferredAccountId = remember(pendingItem, kind) {
        if (pendingItem != null) AccountInference.infer(
            bankMatchedAccountId = parsed?.bankHint?.let { hint -> accounts.firstOrNull { it.name.contains(hint) || hint.contains(it.name) }?.id },
            merchantHistoryAccountId = parsed?.merchant?.let { merchantLastAccount[it] },
            sourcePackage = pendingItem.notification.packageName,
            candidates = accounts.map { AccountInference.Candidate(it.id, it.name) }
        ) else null
    }
    var accountId by remember { mutableStateOf(inferredAccountId ?: accounts.firstOrNull { it.type == AccountType.ASSET.name }?.id ?: accounts.firstOrNull()?.id.orEmpty()) }
    var toAccountId by remember { mutableStateOf(accounts.firstOrNull { it.type == AccountType.CREDIT.name }?.id.orEmpty()) }
    var channel by remember { mutableStateOf(if (pendingItem != null) AccountInference.channelLabel(pendingItem.notification.packageName, pendingItem.notification.sourceLabel) else "微信支付") }
    var merchantText by remember { mutableStateOf(parsed?.merchant ?: "") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var necessity by remember { mutableStateOf<Boolean?>(null) } // null = 按分类默认
    var isReimbursable by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var keypadExpanded by remember { mutableStateOf(pendingItem == null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCategoryManage by remember { mutableStateOf(false) }
    var showNewCategory by remember { mutableStateOf(false) }
    var newCategoryParentId by remember { mutableStateOf<String?>(null) }
    // 贷款还款
    var loanPlanId by remember { mutableStateOf<String?>(null) }
    var loanSuggestion by remember { mutableStateOf<Pair<LoanPlanEntity, com.assetsking.model.LoanInstallment>?>(null) }
    var principalCents by remember { mutableStateOf(0L) }
    var interestCents by remember { mutableStateOf(0L) }
    var feeCents by remember { mutableStateOf(0L) }
    // 报销垫付多选
    val expenseIds = remember { mutableStateOf(listOf<String>()) }

    val evaluated = AmountExpression.evaluate(amountExpr)
    val amountCents = evaluated?.let { (it * 100).roundToLong() } ?: 0L

    // 贷款还款：金额变化时自动匹配期次（REQ 贷款页§6）
    LaunchedEffect(amountCents, repaySub, kind) {
        if (kind == EditorKind.REPAY && repaySub == RepaySub.LOAN && amountCents > 0) {
            loanSuggestion = repository.suggestLoanMatch(amountCents, occurredAt)
            loanSuggestion?.let { (plan, inst) ->
                if (inst.total.cents == amountCents) {
                    loanPlanId = plan.id
                    principalCents = inst.principal.cents
                    interestCents = inst.interest.cents
                    feeCents = inst.fee.cents
                }
            }
        }
    }

    // 二级分类：最近 30 天使用频率排序（REQ 编辑器§9）
    val catUsage = remember(transactions) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        transactions.filter { it.occurredAt >= cutoff }.groupingBy { it.category }.eachCount()
    }
    // 收入用独立分类库（REQ 预期收入§4）；切换类型时清掉上一类型的已选分类，防止错带
    val catKind = if (kind == EditorKind.INCOME) "INCOME" else "EXPENSE"
    LaunchedEffect(kind) { categoryId = null; necessity = null }
    val parents = categories.filter { it.parentId == null && !it.isArchived && it.kind == catKind }
    val childrenOf = { parentId: String? ->
        categories.filter { it.parentId == parentId && !it.isArchived && it.kind == catKind }
            .sortedByDescending { catUsage[it.name] ?: 0 }
    }

    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    val selectedCategoryName = selectedCategory?.name ?: ""

    // ── 必填校验（REQ 编辑器§19）──
    val missing = buildList {
        if (amountCents <= 0) add("金额")
        when (kind) {
            EditorKind.EXPENSE -> {
                if (accountId.isBlank()) add("账户")
                if (merchantText.isBlank()) add("商户")
                if (categoryId == null) add("分类")
            }
            EditorKind.INCOME -> {
                if (accountId.isBlank()) add("账户")
                if (merchantText.isBlank()) add("收入来源")
                if (categoryId == null && incomeSub == IncomeSub.INCOME) add("收入分类")
            }
            EditorKind.TRANSFER -> {
                if (accountId.isBlank() || toAccountId.isBlank()) add("转出/转入账户")
                if (accountId == toAccountId) add("转出与转入账户不能相同")
            }
            EditorKind.REPAY -> {
                if (accountId.isBlank()) add("付款账户")
                if (repaySub == RepaySub.CREDIT_CARD && toAccountId.isBlank()) add("信用卡")
                if (repaySub == RepaySub.LOAN && loanPlanId == null) add("贷款计划")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (kind == EditorKind.EXPENSE || kind == EditorKind.INCOME) {
                        TextButton(onClick = { showCategoryManage = true }) { Text("分类管理") }
                    }
                }
            )
        },
        // REQ 编辑器§19：确认按钮吸底固定；必填缺失或校验冲突时禁用并列缺项
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .imePadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 12.dp)
            ) {
                if (missing.isNotEmpty() && (amountExpr.isNotBlank() || pendingItem != null)) {
                    Text(
                        "还需补充：${missing.joinToString("、")}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = {
                        if (missing.isEmpty()) {
                            doSave(
                                kind, incomeSub, repaySub, amountCents, occurredAt, accountId, toAccountId, channel,
                                merchantText.trim(), selectedCategoryName, necessity, isReimbursable, note,
                                loanPlanId, principalCents, interestCents, feeCents, expenseIds.value,
                                pendingItem, viewModel
                            )
                            onDone()
                        }
                    },
                    enabled = amountCents > 0 && missing.isEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("确认入账", fontWeight = FontWeight.Bold) }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── 类型切换（REQ 编辑器§2/§11/§24）──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorKind.entries.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = k },
                        label = { Text(k.label) }
                    )
                }
            }
            when (kind) {
                EditorKind.INCOME -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IncomeSub.entries.forEach { s ->
                        FilterChip(selected = incomeSub == s, onClick = { incomeSub = s }, label = { Text(s.label) })
                    }
                }
                EditorKind.REPAY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepaySub.entries.forEach { s ->
                        FilterChip(selected = repaySub == s, onClick = { repaySub = s }, label = { Text(s.label) })
                    }
                }
                else -> Unit
            }

            // ── 金额 + 计算键盘（REQ 编辑器§4/§12）──
            OutlinedTextField(
                value = amountExpr,
                onValueChange = { amountExpr = it.filter { c -> c.isDigit() || c in ".-+×÷*/" } },
                label = { Text("金额") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (evaluated != null && amountExpr.isNotBlank()) {
                Text(
                    "= ${formatMoney((evaluated * 100).roundToLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (keypadExpanded) {
                CalculatorKeypad(expr = amountExpr, onExpr = { amountExpr = it })
            } else {
                TextButton(onClick = { keypadExpanded = true }) { Text("展开键盘修改金额") }
            }

            // ── 日期 ──
            Row(
                Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("日期", style = MaterialTheme.typography.bodyMedium)
                Text(formatTime(occurredAt), color = MaterialTheme.colorScheme.primary)
            }

            // ── 资金账户（转账/信用卡还款两个）──
            if (kind == EditorKind.TRANSFER || (kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD)) {
                Text("转出账户", fontWeight = FontWeight.Medium)
                AccountChips(accounts.filter { it.type == AccountType.ASSET.name && !it.archived }, accountId) { accountId = it.id }
                Text("转入账户", fontWeight = FontWeight.Medium)
                val toTarget = if (kind == EditorKind.TRANSFER) accounts.filter { !it.archived } else accounts.filter { it.type == AccountType.CREDIT.name && !it.archived }
                AccountChips(toTarget, toAccountId) { toAccountId = it.id }
            } else {
                Text("实际资金账户", fontWeight = FontWeight.Medium)
                AccountChips(accounts.filter { !it.archived }, accountId) { accountId = it.id }
            }

            // ── 支付渠道（REQ 流水§5，与账户分开）──
            Text("支付渠道", fontWeight = FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                channelOptions.forEach { c ->
                    FilterChip(selected = channel == c, onClick = { channel = c }, label = { Text(c) })
                }
            }

            // ── 商户 / 收入来源（REQ 商户库§4/编辑器§18）──
            MerchantField(
                value = merchantText,
                onValueChange = { merchantText = it },
                suggestions = merchants.map { it.id }.filter { it.contains(merchantText) }.take(6),
                label = if (kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME) "收入来源" else "商户",
                required = kind == EditorKind.EXPENSE || kind == EditorKind.INCOME
            )

            // ── 分类宫格（REQ 编辑器§3/§25-29）──
            if (kind == EditorKind.EXPENSE || (kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME)) {
                CategoryGrid(
                    parents = parents,
                    childrenOf = childrenOf,
                    selectedCategoryId = categoryId,
                    onSelect = { categoryId = it.id },
                    onAddChild = { parentId -> newCategoryParentId = parentId; showNewCategory = true },
                    onReorder = { viewModel.reorderCategories(it) }
                )
            }

            // ── 必要性（REQ 分类§2：默认来自二级分类，单笔可改）──
            if (kind == EditorKind.EXPENSE) {
                Text("必要性", fontWeight = FontWeight.Medium)
                val defaultNec = selectedCategory?.defaultNecessary
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = necessity == true || (necessity == null && defaultNec == true), onClick = { necessity = true }, label = { Text("必要") })
                    FilterChip(selected = necessity == false || (necessity == null && defaultNec == false), onClick = { necessity = false }, label = { Text("非必要") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { isReimbursable = !isReimbursable }) {
                    Icon(if (isReimbursable) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("待报销（到账前仍计入本月支出）", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ── 贷款还款：计划 + 明细（REQ 贷款页§7-8）──
            if (kind == EditorKind.REPAY && repaySub == RepaySub.LOAN) {
                Text("贷款计划", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    loanPlans.filter { it.status == "ACTIVE" }.forEach { plan ->
                        FilterChip(selected = loanPlanId == plan.id, onClick = { loanPlanId = plan.id }, label = { Text(plan.id.take(12)) })
                    }
                }
                loanSuggestion?.let { (plan, inst) ->
                    if (plan.id == loanPlanId) {
                        Text(
                            "第 ${inst.number} 期 · 本金 ${formatMoney(inst.principal.cents)} · 利息 ${formatMoney(inst.interest.cents)} · 费用 ${formatMoney(inst.fee.cents)}（确认前可改）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 报销到账：垫付多选（REQ 报销§3-4）──
            if (kind == EditorKind.INCOME && incomeSub == IncomeSub.REIMBURSEMENT) {
                Text("勾选本次报销的垫付", fontWeight = FontWeight.Medium)
                if (reimbursableTxs.isEmpty()) {
                    Text("没有待报销的消费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                reimbursableTxs.take(10).forEach { tx ->
                    val picked2 = tx.id in expenseIds.value
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            expenseIds.value = if (picked2) expenseIds.value - tx.id else expenseIds.value + tx.id
                        }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${tx.merchant ?: "未命名"} · ${formatMoney(tx.amountCents)} · ${formatTime(tx.occurredAt)}")
                        Icon(if (picked2) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null, tint = if (picked2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 备注 ──
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth()
            )

            // ── 证据 + 余额校验预览（待确认模式，REQ 归并§6/§17-18）──
            if (pendingItem != null) {
                EvidenceSectionInEditor(pendingItem, ignoredItems, viewModel)
                BalancePreviewInEditor(pendingItem, accounts, accountId, amountCents, kind, incomeSub)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = occurredAt)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { occurredAt = it }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) { DatePicker(state = state) }
    }

    if (showCategoryManage) {
        CategoryManageDialog(
            categories = categories,
            catKind = catKind,
            onDismiss = { showCategoryManage = false },
            onAddChild = { parentId -> newCategoryParentId = parentId; showNewCategory = true; showCategoryManage = false },
            viewModel = viewModel
        )
    }

    if (showNewCategory) {
        NewCategoryDialog(
            parentId = newCategoryParentId,
            parents = parents,
            catKind = catKind,
            onDismiss = { showNewCategory = false },
            onCreate = { name, shortName, parentId, iconKey, defaultNecessary ->
                viewModel.addCategoryEntity(name, shortName, parentId, iconKey, defaultNecessary, catKind)
                showNewCategory = false
            }
        )
    }
}

private fun doSave(
    kind: EditorKind, incomeSub: IncomeSub, repaySub: RepaySub,
    amountCents: Long, occurredAt: Long, accountId: String, toAccountId: String, channel: String,
    merchant: String, category: String, necessity: Boolean?, isReimbursable: Boolean, note: String,
    loanPlanId: String?, principalCents: Long, interestCents: Long, feeCents: Long,
    expenseIds: List<String>, pendingItem: PendingItem?, viewModel: LedgerViewModel
) {
    when {
        kind == EditorKind.TRANSFER ->
            viewModel.addTransfer(accountId, toAccountId, "%.2f".format(amountCents / 100.0), note, occurredAt)
        kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD ->
            viewModel.addTransfer(accountId, toAccountId, "%.2f".format(amountCents / 100.0), note, occurredAt)
        kind == EditorKind.REPAY && repaySub == RepaySub.LOAN && loanPlanId != null -> {
            val total = amountCents
            val (p, i, f) = if (principalCents + interestCents + feeCents == total) Triple(principalCents, interestCents, feeCents)
            else Triple(total, 0L, 0L)
            viewModel.addLoanPayment(
                accountId, loanPlanId,
                "%.2f".format(total / 100.0), "%.2f".format(p / 100.0),
                "%.2f".format(i / 100.0), "%.2f".format(f / 100.0),
                note, occurredAt
            )
        }
        kind == EditorKind.INCOME && incomeSub == IncomeSub.REIMBURSEMENT ->
            viewModel.saveReimbursement(accountId, amountCents, note, occurredAt, expenseIds)
        else -> {
            val type = when {
                kind == EditorKind.EXPENSE -> TransactionType.EXPENSE
                kind == EditorKind.INCOME -> incomeSub.type
                else -> TransactionType.EXPENSE
            }
            // 退款继承原消费分类（REQ 编辑器§17），不要求用户再选；仓库层自动关联原消费
            val cat = category.ifEmpty { com.assetsking.model.TransactionCategory.UNCATEGORIZED.name }
            if (pendingItem != null) {
                viewModel.confirmNotification(
                    notificationId = pendingItem.notification.id,
                    accountId = accountId,
                    amountCents = amountCents,
                    type = type,
                    category = cat,
                    merchant = merchant.takeIf { it.isNotEmpty() },
                    note = note.takeIf { it.isNotEmpty() },
                    bankBalanceCents = pendingItem.parsed.balanceCents,
                    bankCardTail = pendingItem.parsed.cardTail,
                    necessity = necessity,
                    channel = channel
                )
            } else {
                viewModel.saveEditorTransaction(
                    accountId, amountCents, type, cat, merchant.takeIf { it.isNotEmpty() },
                    note.takeIf { it.isNotEmpty() }, occurredAt, isReimbursable, necessity, channel
                )
            }
            if (merchant.isNotEmpty()) {
                viewModel.learnRule(merchant, accountId, type.name, category)
            }
        }
    }
}

// ── 组件 ──

@Composable
private fun AccountChips(accounts: List<AccountEntity>, selectedId: String, onSelect: (AccountEntity) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { a ->
            FilterChip(selected = selectedId == a.id, onClick = { onSelect(a) }, label = { Text(a.name) })
        }
    }
}

@Composable
private fun MerchantField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    label: String,
    required: Boolean
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(if (required) "$label *" else label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (suggestions.isNotEmpty() && value.isNotBlank()) {
            Column {
                suggestions.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { onValueChange(s) }.padding(vertical = 6.dp, horizontal = 12.dp)) {
                        Text("${s}（已存商户）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** 一级宫格（每行 5 个，大字体 4 个），点击在其行下方原地展开二级面板（REQ 编辑器§3/§25/§28）；长按拖动排序（REQ 编辑器§8）。 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryGrid(
    parents: List<CategoryEntity>,
    childrenOf: (String?) -> List<CategoryEntity>,
    selectedCategoryId: String?,
    onSelect: (CategoryEntity) -> Unit,
    onAddChild: (String) -> Unit,
    onReorder: (List<String>) -> Unit
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val perRow = if (fontScale > 1.15f) 4 else 5
    var expandedParent by remember { mutableStateOf<String?>(null) }
    // 拖动排序的本地顺序；数据源变化（改名/新增）时重新对齐
    var order by remember(parents) { mutableStateOf(parents) }
    var draggingId by remember { mutableStateOf<String?>(null) }

    fun moveItem(draggedId: String, targetId: String) {
        val from = order.indexOfFirst { it.id == draggedId }
        val to = order.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return
        val list = order.toMutableList().apply { add(to, removeAt(from)) }
        order = list
        onReorder(list.map { it.id })
    }

    Column {
        order.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { parent ->
                    // 收入分类库的种子是一级且无二级：选中即一级本身；有二级的一级仍以二级命中高亮
                    val selected = selectedCategoryId == parent.id ||
                        (selectedCategoryId != null && selectedCategoryId.startsWith("${parent.id}-"))
                    val dropTarget = remember(parent.id) {
                        object : DragAndDropTarget {
                            var onItemDropped: ((String, String) -> Unit)? = null
                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                val draggedId = event.toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString()
                                if (draggedId != null) onItemDropped?.invoke(draggedId, parent.id)
                                return true
                            }
                        }
                    }
                    dropTarget.onItemDropped = { draggedId, targetId -> moveItem(draggedId, targetId) }
                    Column(
                        Modifier.weight(1f).alpha(if (draggingId == parent.id) 0.4f else 1f)
                            .clickable {
                                // 无二级分类（收入种子等）：点一级直接选中；否则展开二级面板
                                if (childrenOf(parent.id).isEmpty()) onSelect(parent)
                                else {
                                    expandedParent = if (expandedParent == parent.id) null else parent.id
                                    if (selectedCategoryId == null) expandedParent = parent.id
                                }
                            }
                            .dragAndDropSource {
                                detectTapGestures(onLongPress = {
                                    draggingId = parent.id
                                    startTransfer(
                                        DragAndDropTransferData(
                                            clipData = ClipData.newPlainText("category", parent.id)
                                        )
                                    )
                                    draggingId = null
                                })
                            }
                            .dragAndDropTarget(
                                shouldStartDragAndDrop = { it.mimeTypes().contains("text/plain") },
                                target = dropTarget
                            )
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(44.dp).background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                IconLibrary.byKey(parent.iconKey),
                                contentDescription = parent.name,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(parent.shortName, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            // 二级面板插在点中的一级所在行正下方（REQ 编辑器§28）
            row.firstOrNull { it.id == expandedParent }?.let { parent ->
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(8.dp)
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        childrenOf(parent.id).take(9).forEach { child ->
                            FilterChip(
                                selected = selectedCategoryId == child.id,
                                onClick = { onSelect(child) },
                                label = { Text(child.name) }
                            )
                        }
                        if (childrenOf(parent.id).size > 9) {
                            FilterChip(selected = false, onClick = {}, label = { Text("更多") })
                        }
                        // 新增二级：固定末尾（REQ 编辑器§9）
                        OutlinedButton(onClick = { onAddChild(parent.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Text("＋新增", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** 计算键盘（REQ 编辑器§12）：数字 + 小数点 + 四则运算 + 退格/清空 */
@Composable
private fun CalculatorKeypad(expr: String, onExpr: (String) -> Unit) {
    val keys = listOf("7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "-", ".", "0", "⌫", "+")
    Column(Modifier.fillMaxWidth()) {
        keys.chunked(4).forEach { rowKeys ->
            Row(Modifier.fillMaxWidth()) {
                rowKeys.forEach { k ->
                    OutlinedButton(
                        onClick = {
                            when (k) {
                                "⌫" -> onExpr(expr.dropLast(1))
                                else -> onExpr(expr + k)
                            }
                        },
                        modifier = Modifier.weight(1f).padding(2.dp)
                    ) { Text(k) }
                }
            }
        }
        OutlinedButton(onClick = { onExpr("") }, modifier = Modifier.fillMaxWidth().padding(2.dp)) { Text("清空") }
    }
}

@Composable
private fun EvidenceSectionInEditor(
    pendingItem: PendingItem,
    ignoredItems: List<com.assetsking.database.RawNotificationEntity>,
    viewModel: LedgerViewModel
) {
    val own = pendingItem.notification
    val merged = ignoredItems.filter { it.processingNote?.contains("kept=${own.id}") == true }
    var show by remember { mutableStateOf(false) }
    Column {
        Text(
            "已合并 ${merged.size + 1} 条消息",
            Modifier.clickable { show = !show },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (show) {
            listOf(own) + merged.forEach { n ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${n.sourceLabel ?: n.packageName} · ${formatTime(n.postedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(n.content.ifBlank { n.title.orEmpty() }, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (n.id != own.id) {
                        TextButton(onClick = { viewModel.splitNotification(n.id) }) { Text("拆分为独立待确认", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BalancePreviewInEditor(
    pendingItem: PendingItem,
    accounts: List<AccountEntity>,
    accountId: String,
    amountCents: Long,
    kind: EditorKind,
    incomeSub: IncomeSub
) {
    val parsed = pendingItem.parsed
    val account = accounts.firstOrNull { it.id == accountId } ?: return
    if (parsed.balanceCents == null || parsed.cardTail == null || account.type != AccountType.ASSET.name) return
    if (account.cardTail != null && account.cardTail != parsed.cardTail) {
        Text("银行尾号与所选账户不符", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        return
    }
    val delta = when {
        kind == EditorKind.EXPENSE -> -amountCents
        kind == EditorKind.INCOME -> amountCents
        else -> return
    }
    val check = com.assetsking.ledger.BalanceMath.checkBalance(account.balanceCents, delta, parsed.balanceCents)
    Column {
        Text("余额校验预览", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        Text("账面余额 ${formatMoney(account.balanceCents)} · 本次影响 ${if (delta >= 0) "+" else ""}${formatMoney(delta)} · 应有余额 ${formatMoney(check.expectedCents)}", style = MaterialTheme.typography.labelSmall)
        Text("银行余额 ${formatMoney(check.bankCents ?: 0)}", style = MaterialTheme.typography.labelSmall)
        Text(
            if (check.matches) "✓ 一致" else "✗ 不一致，差额 ${formatMoney(check.diffCents)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (check.matches) Color(0xFF66BB6A) else MaterialTheme.colorScheme.error
        )
    }
}

// ── 分类管理（REQ 编辑器§20-23）──

@Composable
private fun CategoryManageDialog(
    categories: List<CategoryEntity>,
    catKind: String,
    onDismiss: () -> Unit,
    onAddChild: (String) -> Unit,
    viewModel: LedgerViewModel
) {
    var renameTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var mergeSource by remember { mutableStateOf<CategoryEntity?>(null) }
    var mergeTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    val kindCats = categories.filter { it.kind == catKind }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (catKind == "INCOME") "收入分类管理" else "分类管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("一级分类", fontWeight = FontWeight.Bold)
                kindCats.filter { it.parentId == null }.forEach { parent ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${parent.name}（${parent.shortName}）")
                        Row {
                            TextButton(onClick = { renameTarget = parent }) { Text("改名") }
                            TextButton(onClick = { mergeSource = parent }) { Text("合并") }
                            TextButton(onClick = { viewModel.archiveOrDeleteCategory(parent.id) }) { Text(if (parent.isArchived) "恢复" else "归档") }
                            TextButton(onClick = { onAddChild(parent.id) }) { Text("＋二级") }
                        }
                    }
                }
                HorizontalDivider()
                Text("二级分类", fontWeight = FontWeight.Bold)
                kindCats.filter { it.parentId != null }.forEach { child ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${child.name}${if (child.isArchived) "（已归档）" else ""}")
                        Row {
                            TextButton(onClick = { renameTarget = child }) { Text("改名") }
                            TextButton(onClick = { mergeSource = child }) { Text("合并") }
                            TextButton(onClick = { viewModel.archiveOrDeleteCategory(child.id) }) { Text(if (child.isArchived) "恢复" else "归档") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {}
    )
    renameTarget?.let { target ->
        RenameCategoryDialog(target, onDismiss = { renameTarget = null }) { name, shortName ->
            viewModel.updateCategoryEntity(target.id, name, shortName, null)
            renameTarget = null
        }
    }
    mergeSource?.let { source ->
        val targets = kindCats.filter { it.id != source.id && it.parentId == source.parentId }
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("把「${source.name}」合并到") },
            text = {
                Column {
                    targets.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable {
                            viewModel.mergeCategoryEntity(source.id, t.id)
                            mergeSource = null
                        }.padding(8.dp)) { Text(t.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeSource = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun RenameCategoryDialog(target: CategoryEntity, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(target.name) }
    var shortName by remember { mutableStateOf(target.shortName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改名") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("完整名称") })
                OutlinedTextField(shortName, { shortName = it.take(2) }, label = { Text("两字简称") })
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name, shortName) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NewCategoryDialog(
    parentId: String?,
    parents: List<CategoryEntity>,
    catKind: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String?, String, Boolean?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var shortName by remember { mutableStateOf("") }
    var iconKey by remember { mutableStateOf("more-horiz") }
    var necessary by remember { mutableStateOf<Boolean?>(null) }
    var search by remember { mutableStateOf("") }
    val isIncome = catKind == "INCOME"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (parentId == null) "新增一级分类" else "新增二级分类") },
        text = {
            Column {
                OutlinedTextField(name, { name = it; shortName = name.take(2) }, label = { Text("完整名称 *") })
                OutlinedTextField(shortName, { shortName = it.take(2) }, label = { Text("两字简称") })
                // 收入分类没有必要性概念（REQ 预期收入§4）
                if (!isIncome) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = necessary == true, onClick = { necessary = true }, label = { Text("默认必要") })
                        FilterChip(selected = necessary == false, onClick = { necessary = false }, label = { Text("默认非必要") })
                    }
                }
                OutlinedTextField(search, { search = it }, label = { Text("搜索图标") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconLibrary.search(search).take(12).forEach { entry ->
                        Column(
                            Modifier.clickable { iconKey = entry.key }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                entry.icon,
                                contentDescription = entry.key,
                                modifier = Modifier.size(28.dp),
                                tint = if (iconKey == entry.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onCreate(name.trim(), shortName.ifBlank { name.take(2) }, parentId, iconKey, necessary)
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
