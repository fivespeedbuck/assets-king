package com.assetsking.app.ui.screen

import android.content.ClipData
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.datePickerMillis
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.format.replaceLocalDate
import com.assetsking.ui.format.replaceLocalTime
import com.assetsking.usecase.AccountInference
import com.assetsking.usecase.PendingConfirmationPolicy
import com.assetsking.usecase.ReimbursementMatchCandidate
import com.assetsking.usecase.uniqueExactReimbursementMatch
import kotlin.math.roundToLong
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

// ── 编辑器顶层类型（REQ 编辑器§11）──
private enum class EditorKind(val label: String) { EXPENSE("支出"), INCOME("入账"), TRANSFER("转账"), REPAY("还款") }
private enum class IncomeSub(val label: String, val type: TransactionType) {
    INCOME("收入", TransactionType.INCOME),
    REFUND("退款", TransactionType.REFUND),
    REIMBURSEMENT("报销到账", TransactionType.REIMBURSEMENT)
}
private enum class RepaySub(val label: String) { CREDIT_CARD("信用卡还款"), LOAN("贷款还款") }
internal enum class BalanceResolution { NOTIFICATION, CURRENT_LEDGER }

internal data class PendingTransferAccounts(val fromAccountId: String, val toAccountId: String)

internal fun pendingTransferAccounts(isExpense: Boolean?, evidenceAccountId: String): PendingTransferAccounts =
    when (isExpense) {
        true -> PendingTransferAccounts(fromAccountId = evidenceAccountId, toAccountId = "")
        false -> PendingTransferAccounts(fromAccountId = "", toAccountId = evidenceAccountId)
        null -> PendingTransferAccounts(fromAccountId = "", toAccountId = "")
    }

internal fun pendingTransferEvidenceAccountId(
    isExpense: Boolean?,
    fromAccountId: String,
    toAccountId: String
): String = when (isExpense) {
    true -> fromAccountId
    false -> toAccountId
    null -> ""
}

internal fun isOrdinaryEditableTransaction(type: TransactionType): Boolean =
    type == TransactionType.EXPENSE || type == TransactionType.INCOME ||
        type == TransactionType.REFUND || type == TransactionType.REIMBURSEMENT

internal fun loanPaymentSplitDifferenceCents(
    totalCents: Long,
    principalCents: Long?,
    interestCents: Long?,
    feeCents: Long?
): Long? {
    if (totalCents <= 0L || principalCents == null || interestCents == null || feeCents == null) return null
    if (principalCents < 0L || interestCents < 0L || feeCents < 0L) return null
    val splitTotal = runCatching {
        Math.addExact(Math.addExact(principalCents, interestCents), feeCents)
    }.getOrNull() ?: return null
    return runCatching { Math.subtractExact(totalCents, splitTotal) }.getOrNull()
}

private fun editableMoney(cents: Long): String = String.format(Locale.US, "%.2f", cents / 100.0)

private fun editableMoneyCents(expression: String): Long? {
    val amount = expression.takeIf { it.isNotBlank() }?.let(AmountExpression::evaluate) ?: return null
    if (!amount.isFinite() || amount < 0.0 || amount > Long.MAX_VALUE / 100.0) return null
    return (amount * 100).roundToLong()
}

/**
 * 统一全屏交易编辑器（REQ 编辑器 §1-29）：手动记账与待确认复用同一流程。
 * 类型顶部切换、分类宫格原地展开二级、计算键盘、动态字段、必填校验确认按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TransactionEditorScreen(
    pendingItem: PendingItem?,
    editingTransaction: TransactionEntity? = null,
    initialLoanPlanId: String? = null,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    merchants: List<MerchantEntity>,
    loanPlans: List<LoanPlanEntity>,
    transactions: List<TransactionEntity>,
    reimbursableTxs: List<TransactionEntity>,
    merchantLastAccount: Map<String, String>,
    savedPaymentChannels: Set<String> = emptySet(),
    ignoredItems: List<com.assetsking.database.RawNotificationEntity>,
    viewModel: LedgerViewModel,
    repository: LedgerRepository,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    // 系统返回键/左右边缘返回手势与顶部返回箭头走同一路由，先退出编辑器而不是退出应用。
    BackHandler(onBack = onBack)

    val parsed = pendingItem?.parsed
    var kind by remember(pendingItem?.notification?.id, editingTransaction?.id, initialLoanPlanId) {
        mutableStateOf(
            when {
                initialLoanPlanId != null -> EditorKind.REPAY
                editingTransaction?.type == TransactionType.INCOME.name ||
                    editingTransaction?.type == TransactionType.REFUND.name ||
                    editingTransaction?.type == TransactionType.REIMBURSEMENT.name -> EditorKind.INCOME
                parsed?.isExpense == false -> EditorKind.INCOME
                else -> EditorKind.EXPENSE
            }
        )
    }
    // 待确认通知方向未知时不把“支出”当成默认答案；用户必须主动选择方向。
    var directionChosen by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(editingTransaction != null || pendingItem == null || parsed?.isExpense != null)
    }
    // 审核 BUG-6 修复：退款通知预填「退款」子类型（原固定 INCOME，用户不改就把退款记成收入）。
    var incomeSub by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(
            when {
                editingTransaction?.type == TransactionType.REIMBURSEMENT.name -> IncomeSub.REIMBURSEMENT
                editingTransaction?.type == TransactionType.REFUND.name || parsed?.isRefund == true -> IncomeSub.REFUND
                else -> IncomeSub.INCOME
            }
        )
    }
    var repaySub by remember(initialLoanPlanId) {
        mutableStateOf(if (initialLoanPlanId != null) RepaySub.LOAN else RepaySub.CREDIT_CARD)
    }
    var amountExpr by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(
            editingTransaction?.amountCents?.let { "%.2f".format(it / 100.0) }
                ?: parsed?.amountCents?.let { "%.2f".format(it / 100.0) }
                ?: ""
        )
    }
    var occurredAt by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(editingTransaction?.occurredAt ?: pendingItem?.notification?.postedAt ?: System.currentTimeMillis())
    }
    val inferredAccountId = remember(pendingItem, accounts, merchantLastAccount) {
        if (pendingItem != null) AccountInference.infer(
            bankMatchedAccountId = parsed?.bankHint?.let { hint ->
                val active = accounts.asSequence().filterNot { it.archived }
                active.firstOrNull { account ->
                    account.cardTail != null && account.cardTail == parsed.cardTail &&
                        (account.name.contains(hint) || hint.contains(account.name))
                }?.id ?: active.firstOrNull { account ->
                    account.name.contains(hint) || hint.contains(account.name)
                }?.id
            },
            merchantHistoryAccountId = parsed?.merchant?.let { merchantLastAccount[it] },
            sourcePackage = pendingItem.notification.packageName,
            candidates = accounts.filter { !it.archived && it.type != AccountType.LOAN.name }
                .map { AccountInference.Candidate(it.id, it.name) }
        ) else null
    }
    var accountId by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(
            if (editingTransaction != null) editingTransaction.accountId
            else if (pendingItem != null) inferredAccountId.orEmpty()
            else accounts.firstOrNull { !it.archived && it.type == AccountType.ASSET.name }?.id.orEmpty()
        )
    }
    var toAccountId by remember { mutableStateOf(accounts.firstOrNull { it.type == AccountType.CREDIT.name }?.id.orEmpty()) }
    var channel by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(
            editingTransaction?.channel.orEmpty().ifBlank {
                if (pendingItem != null) AccountInference.channelLabel(
                    pendingItem.notification.packageName,
                    pendingItem.notification.sourceLabel,
                    parsed?.paymentChannel
                )
                else "微信"
            }
        )
    }
    var customChannelSelected by remember(channel, savedPaymentChannels) {
        mutableStateOf(shouldUseCustomPaymentChannelEditor(channel, savedPaymentChannels))
    }
    var merchantText by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(editingTransaction?.merchant.orEmpty().ifBlank { parsed?.merchant.orEmpty() })
    }
    val editingCategoryKind = if (editingTransaction?.type == TransactionType.INCOME.name) "INCOME" else "EXPENSE"
    var categoryId by remember(editingTransaction?.id) {
        mutableStateOf(
            categories.firstOrNull {
                it.name == editingTransaction?.category && it.kind == editingCategoryKind && !it.isArchived
            }?.id
        )
    }
    var editingCategoryInitialized by remember(editingTransaction?.id) {
        mutableStateOf(editingTransaction == null || categoryId != null)
    }
    var necessity by remember(editingTransaction?.id) { mutableStateOf(editingTransaction?.necessity) }
    var isReimbursable by remember(editingTransaction?.id) { mutableStateOf(editingTransaction?.isReimbursable ?: false) }
    var note by remember(editingTransaction?.id) { mutableStateOf(editingTransaction?.note.orEmpty()) }
    var balanceResolution by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf<BalanceResolution?>(null)
    }
    var keypadExpanded by remember { mutableStateOf(pendingItem == null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showNewCategory by remember { mutableStateOf(false) }
    var newCategoryParentId by remember { mutableStateOf<String?>(null) }
    var pendingCategoryName by remember { mutableStateOf<String?>(null) }
    // 贷款还款
    var loanPlanId by remember(initialLoanPlanId) { mutableStateOf(initialLoanPlanId) }
    var loanSuggestion by remember { mutableStateOf<Pair<LoanPlanEntity, com.assetsking.model.LoanInstallment>?>(null) }
    var principalExpr by remember { mutableStateOf("") }
    var interestExpr by remember { mutableStateOf("") }
    var feeExpr by remember { mutableStateOf("") }
    var transferFeeExpr by remember(pendingItem?.notification?.id) { mutableStateOf("") }
    // 报销垫付多选
    val expenseIds = remember { mutableStateOf(listOf<String>()) }
    var reimbursementSelectionTouched by remember { mutableStateOf(false) }
    var reimbursementAutoMatched by remember { mutableStateOf(false) }
    var editingReimbursementLinks by remember(editingTransaction?.id) {
        mutableStateOf(emptyList<com.assetsking.database.ReimbursementLinkEntity>())
    }
    var expenseHasReimbursementLink by remember(editingTransaction?.id) {
        mutableStateOf((editingTransaction?.reimbursedCents ?: 0L) > 0L)
    }
    LaunchedEffect(editingTransaction?.id) {
        if (editingTransaction?.type == TransactionType.REIMBURSEMENT.name) {
            editingReimbursementLinks = repository.reimbursementLinks(editingTransaction.id)
            expenseIds.value = editingReimbursementLinks.map { it.expenseTxId }
            reimbursementSelectionTouched = true
        } else if (editingTransaction?.type == TransactionType.EXPENSE.name) {
            expenseHasReimbursementLink = repository
                .reimbursementLinksForExpense(editingTransaction.id)
                .isNotEmpty()
        }
    }
    val editingCoveredByExpense = editingReimbursementLinks.associate {
        it.expenseTxId to it.coveredCents
    }
    val selectableReimbursableTxs = (
        reimbursableTxs + transactions.filter { it.id in editingCoveredByExpense }
    ).distinctBy { it.id }.sortedBy { it.occurredAt }
    fun availableReimbursementCents(tx: TransactionEntity): Long =
        if (tx.isReimbursable) {
            reimbursementRemainingCents(tx) + editingCoveredByExpense.getOrDefault(tx.id, 0L)
        } else {
            editingCoveredByExpense.getOrDefault(tx.id, 0L)
        }

    val evaluated = AmountExpression.evaluate(amountExpr)
    val amountCents = evaluated?.let { (it * 100).roundToLong() } ?: 0L
    val principalCents = editableMoneyCents(principalExpr)
    val interestCents = editableMoneyCents(interestExpr)
    val feeCents = editableMoneyCents(feeExpr)
    val loanSplitDifference = loanPaymentSplitDifferenceCents(
        amountCents,
        principalCents,
        interestCents,
        feeCents
    )
    val evaluatedTransferFee = transferFeeExpr.takeIf { it.isNotBlank() }?.let(AmountExpression::evaluate)
    val transferFeeCents = evaluatedTransferFee?.let { (it * 100).roundToLong() } ?: 0L
    val selectedReimbursementCents = selectableReimbursableTxs
        .filter { it.id in expenseIds.value }
        .sumOf(::availableReimbursementCents)

    LaunchedEffect(amountCents, kind, incomeSub, selectableReimbursableTxs, reimbursementSelectionTouched) {
        if (
            kind == EditorKind.INCOME &&
            incomeSub == IncomeSub.REIMBURSEMENT &&
            amountCents > 0L &&
            !reimbursementSelectionTouched
        ) {
            val match = uniqueExactReimbursementMatch(
                candidates = selectableReimbursableTxs
                    .map { ReimbursementMatchCandidate(it.id, availableReimbursementCents(it)) },
                arrivalCents = amountCents
            )
            expenseIds.value = match.orEmpty()
            reimbursementAutoMatched = match != null
        }
    }

    // 贷款还款：金额变化时自动匹配期次（REQ 贷款页§6）
    LaunchedEffect(amountCents, repaySub, kind) {
        if (kind == EditorKind.REPAY && repaySub == RepaySub.LOAN && amountCents > 0) {
            loanSuggestion = repository.suggestLoanMatch(amountCents, occurredAt)
            loanSuggestion?.let { (plan, inst) ->
                if (inst.total.cents == amountCents) {
                    loanPlanId = plan.id
                    principalExpr = editableMoney(inst.principal.cents)
                    interestExpr = editableMoney(inst.interest.cents)
                    feeExpr = editableMoney(inst.fee.cents)
                } else {
                    principalExpr = editableMoney(amountCents)
                    interestExpr = editableMoney(0L)
                    feeExpr = editableMoney(0L)
                }
            } ?: run {
                principalExpr = editableMoney(amountCents)
                interestExpr = editableMoney(0L)
                feeExpr = editableMoney(0L)
            }
        }
    }

    // 二级分类：最近 30 天使用频率排序（REQ 编辑器§9）
    val catUsage = remember(transactions) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        transactions.filter { it.occurredAt >= cutoff }.groupingBy { it.category }.eachCount()
    }
    // 收入用独立分类库（REQ 预期收入§4）。
    val catKind = if (kind == EditorKind.INCOME) "INCOME" else "EXPENSE"
    val parents = categories.filter { it.parentId == null && !it.isArchived && it.kind == catKind }
    val childrenOf = { parentId: String? ->
        categories.filter { it.parentId == parentId && !it.isArchived && it.kind == catKind }
            .sortedByDescending { catUsage[it.name] ?: 0 }
    }

    // 分类 ID 只有在当前收支库中才有效，避免切换方向后把已经隐藏的旧分类保存回去。
    val selectedCategory = categories.firstOrNull { it.id == categoryId && it.kind == catKind && !it.isArchived }
    val selectedCategoryName = selectedCategory?.name ?: ""
    val effectiveNecessity = necessity ?: selectedCategory?.defaultNecessary ?: true
    val editingReimbursement = editingTransaction?.type == TransactionType.REIMBURSEMENT.name
    val visibleKinds = when {
        editingReimbursement -> listOf(EditorKind.INCOME)
        editingTransaction != null -> listOf(EditorKind.EXPENSE, EditorKind.INCOME)
        else -> EditorKind.entries
    }
    val visibleIncomeSubs = when {
        editingReimbursement -> listOf(IncomeSub.REIMBURSEMENT)
        editingTransaction != null -> listOf(IncomeSub.INCOME, IncomeSub.REFUND)
        else -> IncomeSub.entries
    }
    val merchantSuggestions = remember(merchantText, merchants, transactions) {
        historyTextSuggestions(
            query = merchantText,
            candidates = transactions.mapNotNull { it.merchant } + merchants.map { it.id }
        )
    }
    val noteSuggestions = remember(note, transactions) {
        historyTextSuggestions(note, transactions.mapNotNull { it.note })
    }

    LaunchedEffect(categories, editingTransaction?.id) {
        if (!editingCategoryInitialized && categories.isNotEmpty()) {
            categoryId = categories.firstOrNull {
                it.name == editingTransaction?.category && it.kind == editingCategoryKind && !it.isArchived
            }?.id
            editingCategoryInitialized = true
        }
    }

    LaunchedEffect(categories, pendingCategoryName, catKind) {
        val name = pendingCategoryName ?: return@LaunchedEffect
        categories.firstOrNull { it.name == name && it.kind == catKind && !it.isArchived }?.let {
            categoryId = it.id
            pendingCategoryName = null
        }
    }

    // 学习规则只负责预填，绝不自动落账。解析出的“退款”证据优先于历史商户类型。
    LaunchedEffect(pendingItem?.notification?.id, categories, accounts) {
        if (editingTransaction != null) return@LaunchedEffect
        val merchant = parsed?.merchant ?: return@LaunchedEffect
        val learned = repository.matchLearnedRule(merchant) ?: return@LaunchedEffect
        accounts.firstOrNull { it.id == learned.accountId && !it.archived }?.let { accountId = it.id }
        if (parsed.isRefund != true) {
            when (runCatching { TransactionType.valueOf(learned.type) }.getOrNull()) {
                TransactionType.INCOME -> {
                    directionChosen = true
                    kind = EditorKind.INCOME
                    incomeSub = IncomeSub.INCOME
                }
                TransactionType.REFUND -> {
                    directionChosen = true
                    kind = EditorKind.INCOME
                    incomeSub = IncomeSub.REFUND
                }
                TransactionType.EXPENSE -> {
                    directionChosen = true
                    kind = EditorKind.EXPENSE
                }
                else -> Unit
            }
        }
        val expectedKind = if (kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME) "INCOME" else "EXPENSE"
        categories.firstOrNull {
            it.name == learned.category && it.kind == expectedKind && !it.isArchived
        }?.let { categoryId = it.id }
    }

    val evidenceAccountId = if (kind == EditorKind.TRANSFER && pendingItem != null) {
        pendingTransferEvidenceAccountId(parsed?.isExpense, accountId, toAccountId)
    } else {
        accountId
    }
    val selectedAccount = accounts.firstOrNull { it.id == evidenceAccountId && !it.archived }
    val editorType = when (kind) {
        EditorKind.EXPENSE -> TransactionType.EXPENSE
        EditorKind.INCOME -> incomeSub.type
        else -> null
    }
    val accountTailConflict = pendingItem != null && selectedAccount != null &&
        PendingConfirmationPolicy.accountTailMismatch(selectedAccount.cardTail, parsed?.cardTail)
    val balanceConflict = pendingItem != null && directionChosen &&
        PendingConfirmationPolicy.balanceConflict(
            type = editorType,
            amountCents = amountCents,
            accountType = selectedAccount?.type?.let { runCatching { AccountType.valueOf(it) }.getOrNull() },
            accountCardTail = selectedAccount?.cardTail,
            bankCardTail = parsed?.cardTail,
            currentBalanceCents = selectedAccount?.balanceCents,
            bankBalanceCents = parsed?.balanceCents
        )
    LaunchedEffect(pendingItem?.notification?.id, accountId, amountCents, kind, incomeSub) {
        balanceResolution = null
    }
    val bankBalanceForSave = when (balanceResolution) {
        BalanceResolution.CURRENT_LEDGER -> null
        else -> parsed?.balanceCents
    }

    // ── 必填校验（REQ 编辑器§19）──
    val missing = buildList {
        if (amountCents <= 0) add("金额")
        if (pendingItem != null && !directionChosen) add("方向")
        when (kind) {
            EditorKind.EXPENSE -> {
                if (selectedAccount == null) add("账户")
                if (merchantText.isBlank()) add("商户")
                if (selectedCategory == null) add("分类")
            }
            EditorKind.INCOME -> {
                if (selectedAccount == null) add("账户")
                if (merchantText.isBlank()) add("收入来源")
                if (selectedCategory == null && incomeSub == IncomeSub.INCOME) add("收入分类")
                if (incomeSub == IncomeSub.REIMBURSEMENT && selectableReimbursableTxs.isNotEmpty()) {
                    reimbursementSelectionError(
                        outstandingCount = selectableReimbursableTxs.size,
                        selectedCount = expenseIds.value.size,
                        selectedCents = selectedReimbursementCents,
                        arrivalCents = amountCents
                    )?.let(::add)
                }
            }
            EditorKind.TRANSFER -> {
                if (accountId.isBlank() || toAccountId.isBlank()) add("转出/转入账户")
                if (accountId == toAccountId) add("转出与转入账户不能相同")
                if (transferFeeExpr.isNotBlank() && (evaluatedTransferFee == null || transferFeeCents < 0)) {
                    add("手续费")
                }
            }
            EditorKind.REPAY -> {
                if (accountId.isBlank()) add("付款账户")
                if (repaySub == RepaySub.CREDIT_CARD && toAccountId.isBlank()) add("信用卡")
                if (repaySub == RepaySub.LOAN && loanPlanId == null) add("贷款计划")
                if (repaySub == RepaySub.LOAN && amountCents > 0L && loanSplitDifference != 0L) {
                    add("本金、利息与费用合计")
                }
            }
        }
        if (accountTailConflict) add("资金账户（银行尾号 ${parsed?.cardTail}）")
        else if (balanceConflict && balanceResolution == null) add("余额对账选择")
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (editingTransaction != null) "编辑流水" else kind.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
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
                            channel.trim().takeIf {
                                it.isNotEmpty() && isCustomPaymentChannel(it)
                            }?.let(viewModel::rememberPaymentChannel)
                            if (editingTransaction != null) {
                                val updatedType = if (kind == EditorKind.EXPENSE) TransactionType.EXPENSE else incomeSub.type
                                val updatedCategory = when {
                                    kind == EditorKind.EXPENSE -> selectedCategoryName
                                    incomeSub == IncomeSub.INCOME -> selectedCategoryName
                                    else -> editingTransaction.category.ifBlank { com.assetsking.model.TransactionCategory.UNCATEGORIZED.name }
                                }
                                if (updatedType == TransactionType.REIMBURSEMENT) {
                                    viewModel.updateReimbursement(
                                        editingTransaction.id,
                                        accountId,
                                        amountCents,
                                        merchantText.trim().takeIf { it.isNotEmpty() },
                                        note.trim().takeIf { it.isNotEmpty() },
                                        occurredAt,
                                        expenseIds.value
                                    )
                                } else {
                                    viewModel.updateTransaction(
                                        editingTransaction.id,
                                        amountCents,
                                        updatedType,
                                        updatedCategory,
                                        merchantText.trim().takeIf { it.isNotEmpty() },
                                        note.trim().takeIf { it.isNotEmpty() },
                                        accountId,
                                        occurredAt,
                                        if (updatedType == TransactionType.EXPENSE) effectiveNecessity else editingTransaction.necessity,
                                        channel.trim().takeIf { it.isNotEmpty() },
                                        isReimbursable
                                    )
                                }
                            } else {
                                doSave(
                                    kind, incomeSub, repaySub, amountCents, occurredAt, accountId, toAccountId, channel,
                                    merchantText.trim(), selectedCategoryName, necessity, isReimbursable, note,
                                    loanPlanId, principalCents ?: 0L, interestCents ?: 0L, feeCents ?: 0L,
                                    transferFeeCents, expenseIds.value, bankBalanceForSave,
                                    pendingItem, viewModel
                                )
                            }
                            onDone()
                        }
                    },
                    enabled = amountCents > 0 && missing.isEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (editingTransaction != null) "保存修改" else "确认入账", fontWeight = FontWeight.Bold) }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (editingTransaction?.let(::isRecurringDebit) == true) {
                Text(
                    RECURRING_DEBIT_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = com.assetsking.ui.theme.RecurringDebitOrange,
                    modifier = Modifier
                        .background(com.assetsking.ui.theme.RecurringDebitOrange.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            // ── 类型切换（REQ 编辑器§2/§11/§24）──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleKinds.forEach { k ->
                    FilterChip(
                        selected = directionChosen && kind == k,
                        onClick = {
                            directionChosen = true
                            if (kind != k) {
                                if (k == EditorKind.TRANSFER && pendingItem != null) {
                                    val transferAccounts = pendingTransferAccounts(parsed?.isExpense, accountId)
                                    accountId = transferAccounts.fromAccountId
                                    toAccountId = transferAccounts.toAccountId
                                }
                                kind = k
                                categoryId = null
                                necessity = null
                            }
                        },
                        label = { Text(k.label) }
                    )
                }
            }
            when (kind) {
                EditorKind.INCOME -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleIncomeSubs.forEach { s ->
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
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    when {
                        amountExpr.isBlank() -> "＝"
                        evaluated != null -> "＝ ${formatMoney((evaluated * 100).roundToLong())}"
                        else -> "＝ …"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (keypadExpanded) {
                CalculatorKeypad(expr = amountExpr, onExpr = { amountExpr = it })
            } else {
                TextButton(onClick = { keypadExpanded = true }) { Text("展开键盘修改金额") }
            }

            // ── 日期与时间：新增、编辑共用同一组入口 ──
            Text("日期与时间", fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Text(formatTime(occurredAt).substringBefore(' '))
                }
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                    Text(formatTime(occurredAt).substringAfter(' '))
                }
            }

            // ── 资金账户 / 支付渠道：普通新增流水与编辑流水共用同一排两列控件 ──
            if (kind == EditorKind.TRANSFER || (kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD)) {
                val fromTargets = accounts.filter { it.type == AccountType.ASSET.name && !it.archived }
                val toTargets = if (kind == EditorKind.TRANSFER) {
                    accounts.filter { !it.archived && it.type != AccountType.LOAN.name }
                } else {
                    accounts.filter { it.type == AccountType.CREDIT.name && !it.archived }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccountDropdownField(
                        label = "转出账户",
                        accounts = fromTargets,
                        selectedAccountId = accountId,
                        onAccountSelected = { accountId = it },
                        modifier = Modifier.weight(1f)
                    )
                    AccountDropdownField(
                        label = "转入账户",
                        accounts = toTargets,
                        selectedAccountId = toAccountId,
                        onAccountSelected = { toAccountId = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                PaymentChannelDropdownField(
                    selectedChannel = channel,
                    savedChannels = savedPaymentChannels,
                    customChannelSelected = customChannelSelected,
                    onChannelSelected = { channel = it },
                    onCustomChannelSelected = { customChannelSelected = it }
                )
                if (customChannelSelected) {
                    OutlinedTextField(
                        value = channel,
                        onValueChange = { channel = it },
                        label = { Text("自定义支付渠道") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (kind == EditorKind.TRANSFER && pendingItem != null) {
                    OutlinedTextField(
                        value = transferFeeExpr,
                        onValueChange = { transferFeeExpr = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("手续费（从转出账户扣，可为 0）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        "转入 ${formatMoney(amountCents)} · 转出账户共减少 ${formatMoney(amountCents + transferFeeCents)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AccountChannelFields(
                    accounts = accounts,
                    selectedAccountId = accountId,
                    selectedChannel = channel,
                    savedChannels = savedPaymentChannels,
                    customChannelSelected = customChannelSelected,
                    onAccountSelected = { accountId = it },
                    onChannelSelected = { channel = it },
                    onCustomChannelSelected = { customChannelSelected = it }
                )
            }

            // ── 商户 / 收入来源（REQ 商户库§4/编辑器§18）──
            SuggestionField(
                value = merchantText,
                onValueChange = { merchantText = it },
                suggestions = merchantSuggestions,
                label = if (kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME) "收入来源" else "商户",
                required = kind == EditorKind.EXPENSE || kind == EditorKind.INCOME,
                suggestionHint = "历史商户",
                singleLine = true
            )

            // ── 分类宫格（REQ 编辑器§3/§25-29）──
            if (kind == EditorKind.EXPENSE || (kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME)) {
                CategoryGrid(
                    parents = parents,
                    childrenOf = childrenOf,
                    selectedCategoryId = categoryId,
                    onSelect = { categoryId = it.id },
                    onClearSelection = { categoryId = null },
                    onAddChild = { parentId -> newCategoryParentId = parentId; showNewCategory = true },
                    onReorder = { viewModel.reorderCategories(it) }
                )
            }

            // ── 必要性（REQ 分类§2：默认来自二级分类，单笔可改）──
            if (kind == EditorKind.EXPENSE) {
                val reimbursementLocked = editingTransaction?.let {
                    it.amountCents > 0L && it.reimbursedCents >= it.amountCents
                } == true
                Text("必要性", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = effectiveNecessity, onClick = { necessity = true }, label = { Text("必要") })
                    FilterChip(selected = !effectiveNecessity, onClick = { necessity = false }, label = { Text("非必要") })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = !reimbursementLocked) {
                        isReimbursable = !isReimbursable
                    }
                ) {
                    Icon(
                        if (isReimbursable) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (isReimbursable) com.assetsking.ui.theme.ReimbursementYellow else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        reimbursementToggleLabel(
                            isReimbursable = isReimbursable,
                            lockedByArrival = reimbursementLocked
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isReimbursable) com.assetsking.ui.theme.ReimbursementYellow else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ── 贷款还款：计划 + 明细（REQ 贷款页§7-8）──
            if (kind == EditorKind.REPAY && repaySub == RepaySub.LOAN) {
                Text("贷款计划", fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    loanPlans.filter { it.status == "ACTIVE" }.forEach { plan ->
                        FilterChip(
                            selected = loanPlanId == plan.id,
                            onClick = { loanPlanId = plan.id },
                            label = { Text(loanPlanDisplayName(plan, accounts)) }
                        )
                    }
                }
                loanSuggestion?.let { (plan, inst) ->
                    if (plan.id == loanPlanId) {
                        Text(
                            "已按第 ${inst.number} 期预填，可按银行实际入账拆分修改",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val splitHasError = amountCents > 0L && loanSplitDifference != 0L
                OutlinedTextField(
                    value = principalExpr,
                    onValueChange = { principalExpr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("本金") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = splitHasError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = interestExpr,
                    onValueChange = { interestExpr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("利息") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = splitHasError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = feeExpr,
                    onValueChange = { feeExpr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("费用 / 手续费") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = splitHasError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text(
                    when {
                        amountCents <= 0L -> "三项合计需等于本次付款金额"
                        loanSplitDifference == null -> "请填写有效金额，三项合计需等于 ${formatMoney(amountCents)}"
                        loanSplitDifference > 0L -> "三项合计还差 ${formatMoney(loanSplitDifference)}"
                        loanSplitDifference < 0L -> "三项合计超出 ${formatMoney(-loanSplitDifference)}"
                        else -> "拆分合计 ${formatMoney(amountCents)}，与本次付款一致"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (splitHasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 报销到账：垫付多选（REQ 报销§3-4）──
            if (kind == EditorKind.INCOME && incomeSub == IncomeSub.REIMBURSEMENT) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("勾选本次报销的垫付", fontWeight = FontWeight.Medium)
                    if (selectableReimbursableTxs.isNotEmpty()) {
                        TextButton(onClick = {
                            reimbursementSelectionTouched = true
                            reimbursementAutoMatched = false
                            expenseIds.value = if (expenseIds.value.size == selectableReimbursableTxs.size) {
                                emptyList()
                            } else {
                                selectableReimbursableTxs.map { it.id }
                            }
                        }) {
                            Text(if (expenseIds.value.size == selectableReimbursableTxs.size) "清空" else "全选")
                        }
                    }
                }
                if (selectableReimbursableTxs.isEmpty()) {
                    Text("没有待报销的消费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        if (reimbursementAutoMatched) {
                            "金额已唯一核对，自动关联 ${expenseIds.value.size} 笔"
                        } else {
                            "已选 ${expenseIds.value.size} 笔 · 待报合计 ${formatMoney(selectedReimbursementCents)} · 到账 ${formatMoney(amountCents)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            reimbursementAutoMatched -> com.assetsking.ui.theme.ReimbursementYellow
                            expenseIds.value.isNotEmpty() && selectedReimbursementCents != amountCents -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                selectableReimbursableTxs.forEach { tx ->
                    val picked2 = tx.id in expenseIds.value
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            reimbursementSelectionTouched = true
                            reimbursementAutoMatched = false
                            expenseIds.value = if (picked2) expenseIds.value - tx.id else expenseIds.value + tx.id
                        }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${tx.merchant ?: "未命名"} · 可核对 ${formatMoney(availableReimbursementCents(tx))} · ${formatTime(tx.occurredAt)}")
                        Icon(if (picked2) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null, tint = if (picked2) com.assetsking.ui.theme.ReimbursementYellow else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── 备注 ──
            SuggestionField(
                value = note,
                onValueChange = { note = it },
                suggestions = noteSuggestions,
                label = "备注（可选）",
                required = false,
                suggestionHint = "历史备注",
                singleLine = false
            )

            if (editingTransaction != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !expenseHasReimbursementLink,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("删除流水", color = MaterialTheme.colorScheme.error) }
                if (expenseHasReimbursementLink) {
                    Text(
                        "已关联报销到账，请先删除对应的报销到账流水",
                        style = MaterialTheme.typography.bodySmall,
                        color = com.assetsking.ui.theme.ReimbursementYellow
                    )
                }
            }

            // ── 证据 + 余额校验预览（待确认模式，REQ 归并§6/§17-18）──
            if (pendingItem != null) {
                EvidenceSectionInEditor(pendingItem, ignoredItems, viewModel)
                BalancePreviewInEditor(
                    pendingItem,
                    accounts,
                    evidenceAccountId,
                    amountCents,
                    kind,
                    incomeSub,
                    directionChosen,
                    balanceResolution,
                    onBalanceResolution = { balanceResolution = it }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = datePickerMillis(occurredAt))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { occurredAt = replaceLocalDate(occurredAt, it) }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) { DatePicker(state = state) }
    }

    if (showTimePicker) {
        val localTime = Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()).toLocalTime()
        val state = rememberTimePickerState(
            initialHour = localTime.hour,
            initialMinute = localTime.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    occurredAt = replaceLocalTime(occurredAt, state.hour, state.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } }
        )
    }

    if (confirmDelete && editingTransaction != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条流水？") },
            text = {
                Text(
                    if (editingTransaction.type == TransactionType.REIMBURSEMENT.name) {
                        "删除后报销到账会从流水消失，关联垫付款恢复为待报销，并重新计算账户余额。"
                    } else {
                        "删除后会同步从流水页消失，并重新计算关联账户余额。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTransaction(editingTransaction.id)
                    confirmDelete = false
                    onDone()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
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
                pendingCategoryName = name.trim()
                showNewCategory = false
            }
        )
    }
}

internal fun reimbursementToggleLabel(
    isReimbursable: Boolean,
    lockedByArrival: Boolean
): String = when {
    lockedByArrival -> "已报销（需先删除对应报销到账）"
    isReimbursable -> "待报销（到账前仍计入本月支出）"
    else -> "不报销"
}

@Composable
internal fun ManagedTransactionDetailSheet(
    transaction: TransactionEntity,
    accountName: String,
    onDismiss: () -> Unit
) {
    val type = runCatching { TransactionType.valueOf(transaction.type) }.getOrNull()
    val typeLabel = when (type) {
        TransactionType.FEE -> "手续费"
        TransactionType.LOAN_DISBURSEMENT -> "借款到账"
        TransactionType.LOAN_PAYMENT -> "贷款还款"
        TransactionType.LOAN_PREPAYMENT -> "提前还款"
        TransactionType.REIMBURSEMENT -> "报销到账"
        else -> transaction.type
    }
    Sheet(title = "流水详情", onDismiss = onDismiss) {
        Text(
            "${typeLabel}由对应业务流程联动生成，不能在普通流水编辑器中修改。请前往贷款、报销或相关业务入口调整。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        reimbursementBadge(transaction)?.let { badge ->
            Text(
                badge.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = com.assetsking.ui.theme.ReimbursementYellow,
                modifier = Modifier
                    .background(com.assetsking.ui.theme.ReimbursementYellow.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "金额：${formatMoney(transaction.amountCents)}",
            color = com.assetsking.ui.theme.transactionCashFlowColor(transaction.type),
            fontWeight = FontWeight.SemiBold
        )
        Text("资金账户：$accountName")
        Text("分类：${transaction.category}")
        Text("日期与时间：${formatTime(transaction.occurredAt)}")
        transaction.merchant?.let { Text("商户/来源：$it") }
        transaction.note?.let { Text("备注：$it") }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
    }
}

private fun doSave(
    kind: EditorKind, incomeSub: IncomeSub, repaySub: RepaySub,
    amountCents: Long, occurredAt: Long, accountId: String, toAccountId: String, channel: String,
    merchant: String, category: String, necessity: Boolean?, isReimbursable: Boolean, note: String,
    loanPlanId: String?, principalCents: Long, interestCents: Long, feeCents: Long, transferFeeCents: Long,
    expenseIds: List<String>, bankBalanceCents: Long?, pendingItem: PendingItem?, viewModel: LedgerViewModel
) {
    when {
        kind == EditorKind.TRANSFER && pendingItem != null ->
            viewModel.confirmTransferNotification(
                pendingItem.notification.id,
                accountId,
                toAccountId,
                amountCents,
                transferFeeCents,
                note
            )
        kind == EditorKind.TRANSFER ->
            viewModel.addTransfer(accountId, toAccountId, "%.2f".format(amountCents / 100.0), note, occurredAt)
        kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD && pendingItem != null ->
            viewModel.confirmTransferNotification(
                pendingItem.notification.id,
                accountId,
                toAccountId,
                amountCents,
                feeCents = 0L,
                note = note
            )
        kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD ->
            viewModel.addTransfer(accountId, toAccountId, "%.2f".format(amountCents / 100.0), note, occurredAt)
        kind == EditorKind.REPAY && repaySub == RepaySub.LOAN && loanPlanId != null -> {
            val total = amountCents
            if (loanPaymentSplitDifferenceCents(total, principalCents, interestCents, feeCents) != 0L) return
            if (pendingItem != null) {
                viewModel.confirmLoanPaymentNotification(
                    notificationId = pendingItem.notification.id,
                    accountId = accountId,
                    planId = loanPlanId,
                    totalCents = total,
                    principalCents = principalCents,
                    interestCents = interestCents,
                    feeCents = feeCents,
                    note = note,
                    bankBalanceCents = bankBalanceCents,
                    bankCardTail = bankBalanceCents?.let { pendingItem.parsed.cardTail }
                )
            } else {
                viewModel.addLoanPayment(
                    accountId, loanPlanId,
                    "%.2f".format(total / 100.0), "%.2f".format(principalCents / 100.0),
                    "%.2f".format(interestCents / 100.0), "%.2f".format(feeCents / 100.0),
                    note, occurredAt
                )
            }
        }
        kind == EditorKind.INCOME && incomeSub == IncomeSub.REIMBURSEMENT ->
            viewModel.saveReimbursement(accountId, amountCents, merchant.takeIf { it.isNotEmpty() }, note, occurredAt, expenseIds)
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
                    bankBalanceCents = bankBalanceCents,
                    bankCardTail = bankBalanceCents?.let { pendingItem.parsed.cardTail },
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
private fun SuggestionField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    label: String,
    required: Boolean,
    suggestionHint: String,
    singleLine: Boolean
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(if (required) "$label *" else label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine
        )
        if (suggestions.isNotEmpty() && value.isNotBlank()) {
            Column {
                suggestions.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { onValueChange(s) }.padding(vertical = 6.dp, horizontal = 12.dp)) {
                        Text("$s（$suggestionHint）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** 一级宫格（每行 5 个，大字体 4 个），点击在其行下方原地展开二级面板（REQ 编辑器§3/§25/§28）；长按拖动排序（REQ 编辑器§8）。 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CategoryGrid(
    parents: List<CategoryEntity>,
    childrenOf: (String?) -> List<CategoryEntity>,
    selectedCategoryId: String?,
    onSelect: (CategoryEntity) -> Unit,
    onClearSelection: () -> Unit = {},
    onAddChild: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    showAddChild: Boolean = true,
    selectParentOnExpand: Boolean = false
) {
    val perRow = 4
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
                    val parentChildren = childrenOf(parent.id)
                    val selected = selectedCategoryId == parent.id || parentChildren.any { it.id == selectedCategoryId }
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
                            .dragAndDropSource {
                                // 点击和长按必须由同一个手势识别器处理；两个识别器叠加时，长按识别器会
                                // 消耗普通点击，导致分类永远选不中、确认按钮持续禁用。
                                detectTapGestures(
                                    onTap = {
                                        if (parentChildren.isEmpty()) onSelect(parent)
                                        else {
                                            if (selectedCategoryId != null && !selected) onClearSelection()
                                            if (selectParentOnExpand) onSelect(parent)
                                            expandedParent = if (expandedParent == parent.id) null else parent.id
                                        }
                                    },
                                    onLongPress = {
                                        draggingId = parent.id
                                        startTransfer(
                                            DragAndDropTransferData(
                                                clipData = ClipData.newPlainText("category", parent.id)
                                            )
                                        )
                                        draggingId = null
                                    }
                                )
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
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
            // 二级面板插在点中的一级所在行正下方（REQ 编辑器§28）
            row.firstOrNull { it.id == expandedParent }?.let { parent ->
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(8.dp)
                ) {
                    // 二级超 10 个显示常用 9 个 +「更多」展开（REQ 编辑器§29）
                    var showAll by remember(parent.id) { mutableStateOf(false) }
                    val children = childrenOf(parent.id)
                    val shown = if (children.size > 9 && !showAll) children.take(9) else children
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        shown.forEach { child ->
                            FilterChip(
                                selected = selectedCategoryId == child.id,
                                onClick = { onSelect(child) },
                                label = { Text(child.name) }
                            )
                        }
                        if (children.size > 9) {
                            FilterChip(selected = false, onClick = { showAll = !showAll }, label = { Text(if (showAll) "收起" else "更多") })
                        }
                        if (showAddChild) {
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
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onExpr("") }, modifier = Modifier.weight(1f).padding(2.dp)) { Text("清空") }
            Button(
                onClick = { calculatorEqualsExpression(expr)?.let(onExpr) },
                enabled = calculatorEqualsExpression(expr) != null,
                modifier = Modifier.weight(1f).padding(2.dp)
            ) { Text("＝") }
        }
    }
}

internal fun historyTextSuggestions(
    query: String,
    candidates: List<String>,
    limit: Int = 6
): List<String> {
    val normalized = query.trim()
    if (normalized.isEmpty() || limit <= 0) return emptyList()
    val unique = candidates.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.equals(normalized, ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .toList()
    val (prefix, contains) = unique.partition { it.startsWith(normalized, ignoreCase = true) }
    return (prefix + contains.filter { it.contains(normalized, ignoreCase = true) }).take(limit)
}

internal fun calculatorEqualsExpression(expr: String): String? =
    AmountExpression.evaluate(expr)?.let { value ->
        runCatching { java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() }.getOrNull()
    }

internal fun loanPlanDisplayName(plan: LoanPlanEntity, accounts: List<AccountEntity>): String =
    accounts.firstOrNull { it.id == plan.accountId }?.name ?: "贷款计划"

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
            (listOf(own) + merged).forEach { n ->
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
    incomeSub: IncomeSub,
    directionChosen: Boolean,
    resolution: BalanceResolution?,
    onBalanceResolution: (BalanceResolution) -> Unit
) {
    val parsed = pendingItem.parsed
    val account = accounts.firstOrNull { it.id == accountId && !it.archived } ?: return
    if (!directionChosen) {
        Text("请先选择收入/支出方向", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        return
    }
    if (parsed.balanceCents == null || parsed.cardTail == null || account.type != AccountType.ASSET.name) return
    if (PendingConfirmationPolicy.accountTailMismatch(account.cardTail, parsed.cardTail)) {
        val selectedTail = account.cardTail?.let { "尾号 $it" } ?: "未设置卡号尾号"
        Text(
            "银行消息来自尾号 ${parsed.cardTail}，所选资金账户「${account.name}」$selectedTail；请改选对应资金账户",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall
        )
        return
    }
    val delta = when {
        kind == EditorKind.EXPENSE -> -amountCents
        kind == EditorKind.INCOME -> amountCents
        kind == EditorKind.TRANSFER && parsed.isExpense == true -> -amountCents
        kind == EditorKind.TRANSFER && parsed.isExpense == false -> amountCents
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
        if (!check.matches) {
            Text(
                "这笔流水可以继续入库，请选择余额对账口径：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (resolution == BalanceResolution.NOTIFICATION) {
                    Button(
                        onClick = { onBalanceResolution(BalanceResolution.NOTIFICATION) },
                        modifier = Modifier.weight(1f)
                    ) { Text("以通知余额为准") }
                } else {
                    OutlinedButton(
                        onClick = { onBalanceResolution(BalanceResolution.NOTIFICATION) },
                        modifier = Modifier.weight(1f)
                    ) { Text("以通知余额为准") }
                }
                if (resolution == BalanceResolution.CURRENT_LEDGER) {
                    Button(
                        onClick = { onBalanceResolution(BalanceResolution.CURRENT_LEDGER) },
                        modifier = Modifier.weight(1f)
                    ) { Text("以当前流水为准") }
                } else {
                    OutlinedButton(
                        onClick = { onBalanceResolution(BalanceResolution.CURRENT_LEDGER) },
                        modifier = Modifier.weight(1f)
                    ) { Text("以当前流水为准") }
                }
            }
            Text(
                when (resolution) {
                    BalanceResolution.NOTIFICATION -> "确认后会把银行卡余额重锚到通知余额。"
                    BalanceResolution.CURRENT_LEDGER -> "确认后保留当前账面余额，由本次流水正常扣减。"
                    null -> "选定后即可直接确认入库，不必退出去手动改余额。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 分类管理（REQ 编辑器§20-23）──

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CategoryManageDialog(
    categories: List<CategoryEntity>,
    catKind: String,
    onDismiss: () -> Unit,
    onAddParent: () -> Unit,
    onAddChild: (String) -> Unit,
    onUpdate: (String, String?, String?, String?, String?) -> Unit,
    onArchiveOrDelete: (String) -> Unit,
    onMerge: (String, String) -> Unit
) {
    var renameTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var mergeSource by remember { mutableStateOf<CategoryEntity?>(null) }
    var moveTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var selectedParentId by remember { mutableStateOf<String?>(null) }
    var childActionTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    val kindCats = categories.filter { it.kind == catKind }
    val selectedParent = kindCats.firstOrNull { it.id == selectedParentId && it.parentId == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                selectedParent?.name
                    ?: if (catKind == "INCOME") "收入分类管理" else "消费分类管理"
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (selectedParent == null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("一级分类", fontWeight = FontWeight.Bold)
                            Text("点进分类后管理二级分类", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onAddParent) { Text("＋一级分类") }
                    }
                    Spacer(Modifier.height(6.dp))
                    kindCats.filter { it.parentId == null }
                        .sortedWith(compareBy<CategoryEntity> { it.isArchived }.thenBy { it.sortOrder })
                        .forEach { parent ->
                            val childCount = kindCats.count { it.parentId == parent.id && !it.isArchived }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                    .clickable { selectedParentId = parent.id }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(IconLibrary.byKey(parent.iconKey), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(parent.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (parent.isArchived) "已归档" else "$childCount 个二级分类",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                } else {
                    TextButton(onClick = { selectedParentId = null }) { Text("‹ 全部一级分类") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(IconLibrary.byKey(selectedParent.iconKey), contentDescription = null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selectedParent.name, fontWeight = FontWeight.Bold)
                            Text(selectedParent.shortName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { renameTarget = selectedParent }) { Text("编辑一级") }
                        TextButton(onClick = { mergeSource = selectedParent }) { Text("合并") }
                        TextButton(onClick = { onArchiveOrDelete(selectedParent.id) }) { Text(if (selectedParent.isArchived) "恢复" else "归档") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("二级分类", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { onAddChild(selectedParent.id) }) { Text("＋二级分类") }
                    }
                    val children = kindCats.filter { it.parentId == selectedParent.id }
                        .sortedWith(compareBy<CategoryEntity> { it.isArchived }.thenBy { it.sortOrder })
                    if (children.isEmpty()) {
                        Text("暂无二级分类", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    children.forEach { child ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .clickable { childActionTarget = child }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(child.name)
                                if (child.isArchived) Text("已归档", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("管理 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {}
    )
    childActionTarget?.let { child ->
        AlertDialog(
            onDismissRequest = { childActionTarget = null },
            title = { Text(child.name) },
            text = {
                Column {
                    TextButton(onClick = { renameTarget = child; childActionTarget = null }, modifier = Modifier.fillMaxWidth()) { Text("编辑名称") }
                    TextButton(onClick = { moveTarget = child; childActionTarget = null }, modifier = Modifier.fillMaxWidth()) { Text("调整所属一级分类") }
                    TextButton(onClick = { mergeSource = child; childActionTarget = null }, modifier = Modifier.fillMaxWidth()) { Text("合并到其他二级分类") }
                    TextButton(
                        onClick = { onArchiveOrDelete(child.id); childActionTarget = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (child.isArchived) "恢复分类" else "归档分类") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { childActionTarget = null }) { Text("取消") } }
        )
    }
    renameTarget?.let { target ->
        RenameCategoryDialog(
            target = target,
            inheritedIconKey = target.parentId?.let { parentId -> kindCats.firstOrNull { it.id == parentId }?.iconKey },
            onDismiss = { renameTarget = null }
        ) { name, shortName, iconKey ->
            onUpdate(target.id, name, shortName, null, iconKey)
            renameTarget = null
        }
    }
    // 调整归属（REQ 流水商户库入口 §2）：二级分类移到其他一级之下
    moveTarget?.let { child ->
        val candidates = kindCats.filter { it.parentId == null && it.id != child.parentId && !it.isArchived }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("把「${child.name}」移动到") },
            text = {
                Column {
                    if (candidates.isEmpty()) Text("没有可移动的一级分类", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    candidates.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable {
                            onUpdate(child.id, null, null, p.id, p.iconKey)
                            moveTarget = null
                        }.padding(8.dp)) { Text(p.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("取消") } }
        )
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
                            onMerge(source.id, t.id)
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RenameCategoryDialog(
    target: CategoryEntity,
    inheritedIconKey: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(target.name) }
    var shortName by remember { mutableStateOf(target.shortName) }
    var iconKey by remember { mutableStateOf(inheritedIconKey ?: target.iconKey) }
    var search by remember { mutableStateOf("") }
    val isPrimary = target.parentId == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPrimary) "改名/换图标" else "编辑二级分类") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("完整名称") })
                OutlinedTextField(shortName, { shortName = it.take(2) }, label = { Text("两字简称") })
                if (isPrimary) {
                    CategoryIconPicker(
                        selectedIconKey = iconKey,
                        search = search,
                        onSearchChange = { search = it },
                        onSelect = { iconKey = it }
                    )
                } else {
                    Text(
                        "二级分类沿用所属一级分类图标",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onSave(name, shortName, categoryIconKeyForEdit(target, inheritedIconKey, iconKey))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NewCategoryDialog(
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
    val isSecondary = parentId != null
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
                if (!isSecondary) {
                    CategoryIconPicker(
                        selectedIconKey = iconKey,
                        search = search,
                        onSearchChange = { search = it },
                        onSelect = { iconKey = it }
                    )
                } else {
                    Text(
                        "二级分类沿用所属一级分类图标",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onCreate(
                        name.trim(),
                        shortName.ifBlank { name.take(2) },
                        parentId,
                        newCategoryIconKey(parentId, parents, iconKey),
                        necessary
                    )
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CategoryIconPicker(
    selectedIconKey: String,
    search: String,
    onSearchChange: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    val visibleIcons = IconLibrary.search(search)
    OutlinedTextField(
        value = search,
        onValueChange = onSearchChange,
        label = { Text("搜索图标（可选）") },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        if (search.isBlank()) "全部图标 · ${visibleIcons.size}" else "找到 ${visibleIcons.size} 个图标",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxWidth().height(232.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(visibleIcons, key = { it.key }) { entry ->
            val selected = selectedIconKey == entry.key
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelect(entry.key) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    entry.icon,
                    contentDescription = entry.key,
                    modifier = Modifier.size(27.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun newCategoryIconKey(
    parentId: String?,
    parents: List<CategoryEntity>,
    requestedIconKey: String
): String = parentId?.let { id -> parents.firstOrNull { it.id == id }?.iconKey }
    ?: requestedIconKey

/** 一级可换图标；二级保存时始终回写所属一级图标，避免脱离继承规则。 */
internal fun categoryIconKeyForEdit(
    target: CategoryEntity,
    inheritedIconKey: String?,
    requestedIconKey: String
): String = target.parentId?.let { inheritedIconKey ?: target.iconKey } ?: requestedIconKey
