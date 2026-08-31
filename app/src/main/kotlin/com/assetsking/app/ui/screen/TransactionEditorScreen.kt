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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetsking.app.LedgerViewModel
import com.assetsking.app.PendingItem
import com.assetsking.database.AccountEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.LendingOriginType
import com.assetsking.database.LendingPlanEntity
import com.assetsking.database.LendingPlanStatus
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.MerchantEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.ledger.PaymentChannel
import com.assetsking.ledger.OrderPlatform
import com.assetsking.ledger.AmountExpression
import com.assetsking.ledger.BalanceMath
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.IconLibrary
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.datePickerMillis
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.format.transactionCategoryLabel
import com.assetsking.ui.format.replaceLocalDate
import com.assetsking.ui.format.replaceLocalTime
import com.assetsking.usecase.AccountInference
import com.assetsking.usecase.NotificationParser
import com.assetsking.usecase.PendingConfirmationPolicy
import com.assetsking.usecase.ParsedNotification
import com.assetsking.usecase.ReimbursementMatchCandidate
import com.assetsking.usecase.uniqueExactReimbursementMatch
import kotlin.math.roundToLong
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

@Composable
private fun strongSelectedFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun EditorSectionCard(
    title: String,
    supportingText: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                supportingText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

internal data class RefundSourceCandidate(
    val transaction: TransactionEntity,
    val remainingCents: Long
)

internal fun refundSourceCandidates(
    transactions: List<TransactionEntity>,
    accountId: String,
    refundAmountCents: Long,
    refundOccurredAt: Long,
    editingRefundId: String? = null,
    merchantName: String? = null,
    refundChannel: String? = null,
    refundOrderPlatform: String? = null
): List<RefundSourceCandidate> {
    val requiredMerchant = merchantName?.trim().orEmpty()
    val requiredChannel = refundChannel?.trim().orEmpty()
    val requiredOrderPlatform = refundOrderPlatform?.trim().orEmpty()
    if (requiredMerchant.isBlank()) return emptyList()

    val refundedByExpense = transactions
        .filter {
            it.id != editingRefundId &&
                it.status == "CONFIRMED" &&
                it.type == TransactionType.REFUND.name &&
                it.refundOfId != null
        }
        .groupBy { requireNotNull(it.refundOfId) }
        .mapValues { (_, refunds) -> refunds.sumOf { it.amountCents } }

    return transactions.asSequence()
        .filter {
                it.status == "CONFIRMED" &&
                it.type == TransactionType.EXPENSE.name &&
                it.accountId == accountId &&
                it.merchant?.trim()?.equals(requiredMerchant, ignoreCase = true) == true &&
                PaymentChannel.refundSourceCompatible(it.channel, requiredChannel) &&
                OrderPlatform.refundSourceCompatible(it.orderPlatform, requiredOrderPlatform) &&
                it.occurredAt <= refundOccurredAt
        }
        .map { expense ->
            RefundSourceCandidate(
                transaction = expense,
                remainingCents = (expense.amountCents - refundedByExpense.getOrDefault(expense.id, 0L)).coerceAtLeast(0L)
            )
        }
        .filter { it.remainingCents > 0L && (refundAmountCents <= 0L || refundAmountCents <= it.remainingCents) }
        .sortedWith(
            compareByDescending<RefundSourceCandidate> { refundAmountCents > 0L && it.remainingCents == refundAmountCents }
                .thenByDescending { it.transaction.occurredAt }
        )
        .take(30)
        .toList()
}

// ── 编辑器顶层类型（REQ 编辑器§11）──
private enum class EditorKind(val label: String) { EXPENSE("支出"), INCOME("入账"), TRANSFER("划转"), REPAY("还款"), LENDING("借出/收回") }
private enum class IncomeSub(val label: String, val type: TransactionType) {
    INCOME("收入", TransactionType.INCOME),
    REFUND("退款", TransactionType.REFUND),
    REIMBURSEMENT("报销到账", TransactionType.REIMBURSEMENT),
    LOAN_DISBURSEMENT("借款到账", TransactionType.LOAN_DISBURSEMENT)
}
private enum class RepaySub(val label: String) { CREDIT_CARD("信用卡还款"), LOAN("贷款还款") }
internal enum class LendingSub(val label: String) { DISBURSEMENT("借出本金"), REPAYMENT("收回本金/利息") }
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

internal fun lendingSplitDifferenceCents(
    totalCents: Long,
    principalCents: Long,
    interestCents: Long
): Long? = loanPaymentSplitDifferenceCents(totalCents, principalCents, interestCents, 0L)

internal fun lendingValidationErrors(
    amountCents: Long,
    selectedPlan: LendingPlanEntity?,
    isAssetAccount: Boolean,
    sub: LendingSub,
    principalCents: Long?,
    splitDifferenceCents: Long?
): List<String> = buildList {
    if (!isAssetAccount) add("资金账户")
    if (selectedPlan == null) add("出借计划")
    when (sub) {
        LendingSub.DISBURSEMENT -> if (selectedPlan != null) {
            if (selectedPlan.status != LendingPlanStatus.PENDING_DISBURSEMENT) add("待借出计划")
            if (amountCents != selectedPlan.principalCents) add("借出金额须等于计划本金")
        }
        LendingSub.REPAYMENT -> {
            if (selectedPlan != null && selectedPlan.status != LendingPlanStatus.ACTIVE) add("进行中的出借计划")
            if (amountCents > 0L && splitDifferenceCents != 0L) add("本金与利息合计")
            if (principalCents != null && selectedPlan != null && principalCents > selectedPlan.remainingPrincipalCents) {
                add("收回本金不能超过剩余应收")
            }
        }
    }
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
    lendingPlans: List<LendingPlanEntity> = emptyList(),
    transactions: List<TransactionEntity>,
    reimbursableTxs: List<TransactionEntity>,
    merchantLastAccount: Map<String, String>,
    savedPaymentChannels: Set<String> = emptySet(),
    savedOrderPlatforms: Set<String> = emptySet(),
    ignoredItems: List<com.assetsking.database.RawNotificationEntity>,
    viewModel: LedgerViewModel,
    repository: LedgerRepository,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    // 系统返回键/左右边缘返回手势与顶部返回箭头走同一路由，先退出编辑器而不是退出应用。
    BackHandler(onBack = onBack)

    val parsed = pendingItem?.parsed
    val receivableAccountIds = remember(lendingPlans) {
        lendingPlans.mapTo(hashSetOf()) { it.receivableAccountId }
    }
    // 专属应收只由“借出/收回”账务流程在仓储层使用；普通编辑器不把它伪装成可用资金账户。
    // 若历史异常流水已经指向应收，编辑时仍保留当前项，便于用户改到正确账户。
    val ordinaryAccounts = remember(accounts, receivableAccountIds, editingTransaction?.accountId) {
        accounts.filter { it.id !in receivableAccountIds || it.id == editingTransaction?.accountId }
    }
    var kind by remember(pendingItem?.notification?.id, editingTransaction?.id, initialLoanPlanId) {
        mutableStateOf(
            when {
                initialLoanPlanId != null -> EditorKind.REPAY
                editingTransaction?.type == TransactionType.INCOME.name ||
                    editingTransaction?.type == TransactionType.REFUND.name ||
                    editingTransaction?.type == TransactionType.REIMBURSEMENT.name ||
                    editingTransaction?.type == TransactionType.LOAN_DISBURSEMENT.name -> EditorKind.INCOME
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
                editingTransaction?.type == TransactionType.LOAN_DISBURSEMENT.name -> IncomeSub.LOAN_DISBURSEMENT
                editingTransaction?.type == TransactionType.REIMBURSEMENT.name -> IncomeSub.REIMBURSEMENT
                editingTransaction?.type == TransactionType.REFUND.name || parsed?.isRefund == true -> IncomeSub.REFUND
                else -> IncomeSub.INCOME
            }
        )
    }
    var repaySub by remember(initialLoanPlanId) {
        mutableStateOf(if (initialLoanPlanId != null) RepaySub.LOAN else RepaySub.CREDIT_CARD)
    }
    var lendingSub by remember(pendingItem?.notification?.id) {
        mutableStateOf(if (parsed?.isExpense == false) LendingSub.REPAYMENT else LendingSub.DISBURSEMENT)
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
    val inferredAccountId = remember(pendingItem, ordinaryAccounts, merchantLastAccount) {
        if (pendingItem != null) inferPendingAccountId(pendingItem, ordinaryAccounts, merchantLastAccount) else null
    }
    var accountId by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(
            if (editingTransaction != null) editingTransaction.accountId
            else if (pendingItem != null) inferredAccountId.orEmpty()
            else ordinaryAccounts.firstOrNull { !it.archived && it.type == AccountType.ASSET.name }?.id.orEmpty()
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
    var customChannelSelected by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(shouldUseCustomPaymentChannelEditor(channel, savedPaymentChannels))
    }
    var orderPlatform by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(initialOrderPlatform(editingTransaction, pendingItem, parsed?.merchant))
    }
    var customOrderPlatformSelected by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(shouldUseCustomOrderPlatformEditor(orderPlatform, savedOrderPlatforms))
    }
    var saveError by remember(pendingItem?.notification?.id, editingTransaction?.id) { mutableStateOf<String?>(null) }
    var saving by remember(pendingItem?.notification?.id, editingTransaction?.id) { mutableStateOf(false) }
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
    var refundOfId by remember(editingTransaction?.id) { mutableStateOf(editingTransaction?.refundOfId) }
    var note by remember(editingTransaction?.id) { mutableStateOf(editingTransaction?.note.orEmpty()) }
    var balanceResolution by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf<BalanceResolution?>(null)
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showNewCategory by remember { mutableStateOf(false) }
    var newCategoryParentId by remember { mutableStateOf<String?>(null) }
    var pendingCategoryName by remember { mutableStateOf<String?>(null) }
    // 贷款还款
    var loanPlanId by remember(initialLoanPlanId, editingTransaction?.id) {
        mutableStateOf(initialLoanPlanId ?: editingTransaction?.loanPlanId)
    }
    var autoMatchedLoanPlanId by remember(initialLoanPlanId) { mutableStateOf<String?>(null) }
    var loanSuggestion by remember { mutableStateOf<Pair<LoanPlanEntity, com.assetsking.model.LoanInstallment>?>(null) }
    var principalExpr by remember { mutableStateOf("") }
    var interestExpr by remember { mutableStateOf("") }
    var lendingSplitAutoFilled by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(false)
    }
    var feeExpr by remember { mutableStateOf("") }
    var lendingPlanId by remember(pendingItem?.notification?.id, editingTransaction?.id) {
        mutableStateOf(editingTransaction?.lendingPlanId)
    }
    var autoMatchedLendingPlanId by remember(pendingItem?.notification?.id) { mutableStateOf<String?>(null) }
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
    val lendingSplitDifference = loanPaymentSplitDifferenceCents(
        amountCents,
        principalCents,
        interestCents,
        0L
    )
    val evaluatedTransferFee = transferFeeExpr.takeIf { it.isNotBlank() }?.let(AmountExpression::evaluate)
    val transferFeeCents = evaluatedTransferFee?.let { (it * 100).roundToLong() } ?: 0L
    val selectedReimbursementCents = selectableReimbursableTxs
        .filter { it.id in expenseIds.value }
        .sumOf(::availableReimbursementCents)
    val refundCandidates = remember(transactions, accountId, amountCents, occurredAt, editingTransaction?.id, merchantText, channel, orderPlatform) {
        refundSourceCandidates(
            transactions = transactions,
            accountId = accountId,
            refundAmountCents = amountCents,
            refundOccurredAt = occurredAt,
            editingRefundId = editingTransaction?.id,
            merchantName = merchantText,
            refundChannel = channel,
            refundOrderPlatform = orderPlatform
        )
    }
    val selectedRefundSource = refundOfId?.let { selectedId ->
        refundCandidates.firstOrNull { it.transaction.id == selectedId }
    }
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
        editingTransaction?.lendingPlanId != null -> listOf(EditorKind.INCOME)
        editingTransaction?.type == TransactionType.INCOME.name -> listOf(EditorKind.EXPENSE, EditorKind.INCOME, EditorKind.LENDING)
        editingTransaction != null -> listOf(EditorKind.EXPENSE, EditorKind.INCOME)
        else -> EditorKind.entries
    }
    val visibleIncomeSubs = when {
        editingReimbursement -> listOf(IncomeSub.REIMBURSEMENT)
        editingTransaction?.lendingPlanId != null -> listOf(IncomeSub.INCOME)
        editingTransaction != null -> listOf(IncomeSub.INCOME, IncomeSub.REFUND, IncomeSub.LOAN_DISBURSEMENT)
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
    LaunchedEffect(pendingItem?.notification?.id, categories, ordinaryAccounts) {
        if (editingTransaction != null) return@LaunchedEffect
        val merchant = parsed?.merchant ?: return@LaunchedEffect
        val learned = repository.matchLearnedRule(merchant) ?: return@LaunchedEffect
        ordinaryAccounts.firstOrNull { it.id == learned.accountId && !it.archived }?.let { accountId = it.id }
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
        EditorKind.LENDING -> if (lendingSub == LendingSub.DISBURSEMENT) TransactionType.EXPENSE else TransactionType.INCOME
        EditorKind.REPAY -> if (repaySub == RepaySub.LOAN) TransactionType.LOAN_PAYMENT else null
        EditorKind.TRANSFER -> null
    }
    val assetBalanceShortfallCents = if (
        selectedAccount?.type == AccountType.ASSET.name &&
        editorType != null &&
        BalanceMath.transactionDelta(AccountType.ASSET, editorType, amountCents) < 0L
    ) {
        val oldCredit = editingTransaction
            ?.takeIf { it.accountId == selectedAccount.id }
            ?.let {
                BalanceMath.transactionDelta(
                    AccountType.ASSET,
                    runCatching { TransactionType.valueOf(it.type) }.getOrNull() ?: TransactionType.INCOME,
                    it.amountCents
                )
            }
            ?: 0L
        (-(selectedAccount.balanceCents - oldCredit + BalanceMath.transactionDelta(AccountType.ASSET, editorType, amountCents))).coerceAtLeast(0L)
    } else 0L
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

    // 借款到账也必须挂计划：金额唯一命中某个新计划时只做“预选”，多计划同额不猜。
    LaunchedEffect(amountCents, kind, incomeSub, loanPlans) {
        if (kind != EditorKind.INCOME || incomeSub != IncomeSub.LOAN_DISBURSEMENT || amountCents <= 0L) return@LaunchedEffect
        val exact = loanPlans.filter {
            it.status == "PENDING_DISBURSEMENT" &&
                it.originType == "PENDING_DISBURSEMENT" &&
                it.principalCents == amountCents
        }
        if (exact.size == 1) {
            if (loanPlanId == null || loanPlanId == autoMatchedLoanPlanId) {
                loanPlanId = exact.single().id
                autoMatchedLoanPlanId = exact.single().id
            }
        } else if (loanPlanId == autoMatchedLoanPlanId) {
            loanPlanId = null
            autoMatchedLoanPlanId = null
        }
    }

    // 出借收回编辑：首次切换到收回时按“纯本金到账、利息 0”预填，用户仍可按实际回款修改。
    LaunchedEffect(kind, lendingSub, amountCents, editingTransaction?.id) {
        if (
            kind == EditorKind.LENDING &&
            lendingSub == LendingSub.REPAYMENT &&
            amountCents > 0L &&
            (lendingSplitAutoFilled || (principalExpr.isBlank() && interestExpr.isBlank()))
        ) {
            principalExpr = editableMoney(amountCents)
            interestExpr = editableMoney(0L)
            lendingSplitAutoFilled = true
        }
    }
    LaunchedEffect(amountCents, kind, lendingSub, lendingPlans) {
        if (kind != EditorKind.LENDING || lendingSub != LendingSub.DISBURSEMENT || amountCents <= 0L) return@LaunchedEffect
        val exact = lendingPlans.filter {
            it.status == LendingPlanStatus.PENDING_DISBURSEMENT &&
                it.originType == LendingOriginType.PENDING_DISBURSEMENT &&
                it.principalCents == amountCents
        }
        if (exact.size == 1) {
            if (lendingPlanId == null || lendingPlanId == autoMatchedLendingPlanId) {
                lendingPlanId = exact.single().id
                autoMatchedLendingPlanId = exact.single().id
            }
        } else if (lendingPlanId == autoMatchedLendingPlanId) {
            lendingPlanId = null
            autoMatchedLendingPlanId = null
        }
    }
    val selectedLendingPlan = lendingPlans.firstOrNull { it.id == lendingPlanId }
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
                if (incomeSub == IncomeSub.LOAN_DISBURSEMENT && selectedAccount?.type != AccountType.ASSET.name) {
                    add("资产账户")
                }
                if (merchantText.isBlank() && !(editingTransaction != null && incomeSub == IncomeSub.INCOME && lendingPlanId != null)) {
                    add(if (incomeSub == IncomeSub.LOAN_DISBURSEMENT) "借款来源" else "收入来源")
                }
                if (selectedCategory == null && incomeSub == IncomeSub.INCOME && !(editingTransaction != null && lendingPlanId != null)) add("收入分类")
                if (incomeSub == IncomeSub.LOAN_DISBURSEMENT && loanPlanId == null) add("贷款计划")
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
            EditorKind.LENDING -> addAll(
                lendingValidationErrors(
                    amountCents = amountCents,
                    selectedPlan = selectedLendingPlan,
                    isAssetAccount = selectedAccount?.type == AccountType.ASSET.name,
                    sub = lendingSub,
                    principalCents = principalCents,
                    splitDifferenceCents = lendingSplitDifference
                )
            )
        }
        if (accountTailConflict) add("资金账户（银行尾号 ${parsed?.cardTail}）")
        else if (balanceConflict && balanceResolution == null) add("余额对账选择")
        // 选择以通知余额为准时，会在同一确认事务内先重锚再入账，因此不应被旧账面余额不足拦住。
        // 选择当前流水口径仍必须遵守资产账户不能透支的硬门禁。
        if (assetBalanceShortfallCents > 0L && balanceResolution != BalanceResolution.NOTIFICATION) {
            add("余额不足（还差 ${formatMoney(assetBalanceShortfallCents)}）")
        }
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
        bottomBar = {
            EditorSaveBar(
                missing = missing,
                showMissing = amountExpr.isNotBlank() || pendingItem != null,
                amountCents = amountCents,
                saveError = saveError,
                saving = saving,
                editingTransaction = editingTransaction,
                onSave = {
                    if (missing.isEmpty() && !saving) {
                        saving = true
                        saveError = null
                        saveEditor(
                            editingTransaction = editingTransaction,
                            kind = kind,
                            incomeSub = incomeSub,
                            repaySub = repaySub,
                            lendingSub = lendingSub,
                            amountCents = amountCents,
                            occurredAt = occurredAt,
                            accountId = accountId,
                            toAccountId = toAccountId,
                            channel = channel,
                            orderPlatform = orderPlatform,
                            merchantText = merchantText,
                            selectedCategoryName = selectedCategoryName,
                            selectedRefundSource = selectedRefundSource,
                            effectiveNecessity = effectiveNecessity,
                            necessity = necessity,
                            isReimbursable = isReimbursable,
                            note = note,
                            loanPlanId = loanPlanId,
                            lendingPlanId = lendingPlanId,
                            principalCents = principalCents,
                            interestCents = interestCents,
                            feeCents = feeCents,
                            transferFeeCents = transferFeeCents,
                            expenseIds = expenseIds.value,
                            bankBalanceForSave = bankBalanceForSave,
                            refundOfId = refundOfId,
                            pendingItem = pendingItem,
                            viewModel = viewModel,
                            onSavingChanged = { saving = it },
                            onError = { saveError = it },
                            onDone = onDone
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EditorBasicInformationSection(
                editingTransaction = editingTransaction,
                kind = kind,
                directionChosen = directionChosen,
                visibleKinds = visibleKinds,
                visibleIncomeSubs = visibleIncomeSubs,
                incomeSub = incomeSub,
                repaySub = repaySub,
                lendingSub = lendingSub,
                amountExpr = amountExpr,
                evaluated = evaluated,
                occurredAt = occurredAt,
                onKindSelected = { selectedKind ->
                    directionChosen = true
                    if (kind != selectedKind) {
                        if (selectedKind == EditorKind.TRANSFER && pendingItem != null) {
                            val transferAccounts = pendingTransferAccounts(parsed?.isExpense, accountId)
                            accountId = transferAccounts.fromAccountId
                            toAccountId = transferAccounts.toAccountId
                        }
                        kind = selectedKind
                        categoryId = null
                        necessity = null
                    }
                },
                onIncomeSubSelected = { incomeSub = it },
                onRepaySubSelected = { repaySub = it },
                onLendingSubSelected = { selectedSub ->
                    if (lendingSub != selectedSub) {
                        lendingSub = selectedSub
                        lendingPlanId = null
                        autoMatchedLendingPlanId = null
                        principalExpr = ""
                        interestExpr = ""
                        lendingSplitAutoFilled = false
                    }
                },
                onAmountChange = { amountExpr = it.filter { c -> c.isDigit() || c in ".-+×÷*/" } },
                onDatePickerRequested = { showDatePicker = true },
                onTimePickerRequested = { showTimePicker = true }
            )

            EditorReadOnlyBalanceChange(
                pendingItem = pendingItem,
                ignoredItems = ignoredItems,
                editingTransaction = editingTransaction,
                accounts = accounts,
                selectedAccount = selectedAccount,
                amountCents = amountCents,
                type = editorType,
                repository = repository
            )

            EditorAccountAndMerchantSection(
                kind = kind,
                incomeSub = incomeSub,
                repaySub = repaySub,
                ordinaryAccounts = ordinaryAccounts,
                accountId = accountId,
                toAccountId = toAccountId,
                channel = channel,
                savedPaymentChannels = savedPaymentChannels,
                customChannelSelected = customChannelSelected,
                orderPlatform = orderPlatform,
                savedOrderPlatforms = savedOrderPlatforms,
                customOrderPlatformSelected = customOrderPlatformSelected,
                pendingItemPresent = pendingItem != null,
                amountCents = amountCents,
                transferFeeExpr = transferFeeExpr,
                transferFeeCents = transferFeeCents,
                merchantText = merchantText,
                merchantSuggestions = merchantSuggestions,
                onAccountSelected = { accountId = it },
                onToAccountSelected = { toAccountId = it },
                onChannelSelected = { channel = it },
                onCustomChannelSelected = { customChannelSelected = it },
                onOrderPlatformSelected = { orderPlatform = it },
                onCustomOrderPlatformSelected = { customOrderPlatformSelected = it },
                onTransferFeeChanged = { transferFeeExpr = it.filter { c -> c.isDigit() || c == '.' } },
                onMerchantChanged = { merchantText = it }
            )

            if (
                kind == EditorKind.LENDING ||
                (kind == EditorKind.INCOME && (incomeSub == IncomeSub.REFUND || incomeSub == IncomeSub.LOAN_DISBURSEMENT)) ||
                (editingTransaction != null && kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME)
            ) {
                EditorBusinessAssociationSection(
                    kind = kind,
                    incomeSub = incomeSub,
                    editingTransaction = editingTransaction,
                    refundCandidates = refundCandidates,
                    merchantText = merchantText,
                    refundOfId = refundOfId,
                    loanPlans = loanPlans,
                    loanPlanId = loanPlanId,
                    accounts = accounts,
                    lendingPlans = lendingPlans,
                    lendingSub = lendingSub,
                    lendingPlanId = lendingPlanId,
                    selectedLendingPlan = selectedLendingPlan,
                    amountCents = amountCents,
                    principalExpr = principalExpr,
                    interestExpr = interestExpr,
                    lendingSplitDifference = lendingSplitDifference,
                    onRefundSelected = { candidate ->
                        refundOfId = candidate?.transaction?.id
                        candidate?.transaction?.let { source ->
                            necessity = source.necessity
                            categoryId = categories.firstOrNull {
                                it.name == source.category && it.kind == "EXPENSE" && !it.isArchived
                            }?.id
                        }
                    },
                    onLoanPlanSelected = { loanPlanId = it },
                    onLendingPlanSelected = {
                        if (editingTransaction?.lendingPlanId != it) {
                            lendingPlanId = if (lendingPlanId == it) null else it
                        }
                    },
                    onPrincipalChanged = {
                        principalExpr = it.filter { c -> c.isDigit() || c == '.' }
                        lendingSplitAutoFilled = false
                    },
                    onInterestChanged = {
                        interestExpr = it.filter { c -> c.isDigit() || c == '.' }
                        lendingSplitAutoFilled = false
                    },
                    onLendingPlanForDisbursementSelected = {
                        lendingPlanId = it
                        autoMatchedLendingPlanId = null
                    }
                )
            }

            val showsCategory = kind == EditorKind.EXPENSE ||
                (kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME)
            if (showsCategory) {
                EditorCategoryAndNotesSection(
                    parents = parents,
                    childrenOf = childrenOf,
                    selectedCategoryId = categoryId,
                    kind = kind,
                    effectiveNecessity = effectiveNecessity,
                    editingTransaction = editingTransaction,
                    isReimbursable = isReimbursable,
                    note = note,
                    noteSuggestions = noteSuggestions,
                    onSelectCategory = { categoryId = it.id },
                    onClearCategory = { categoryId = null },
                    onAddChild = { parentId ->
                        newCategoryParentId = parentId
                        showNewCategory = true
                    },
                    onReorderCategories = { viewModel.reorderCategories(it) },
                    onNecessityChanged = { necessity = it },
                    onReimbursableChanged = { isReimbursable = it },
                    onNoteChanged = { note = it }
                )
            }

            if (kind == EditorKind.REPAY && repaySub == RepaySub.LOAN) {
                EditorLoanRepaymentSection(
                    loanPlans = loanPlans,
                    accounts = accounts,
                    loanPlanId = loanPlanId,
                    loanSuggestion = loanSuggestion,
                    amountCents = amountCents,
                    loanSplitDifference = loanSplitDifference,
                    principalExpr = principalExpr,
                    interestExpr = interestExpr,
                    feeExpr = feeExpr,
                    onLoanPlanSelected = { loanPlanId = it },
                    onPrincipalChanged = { principalExpr = it.filter { c -> c.isDigit() || c == '.' } },
                    onInterestChanged = { interestExpr = it.filter { c -> c.isDigit() || c == '.' } },
                    onFeeChanged = { feeExpr = it.filter { c -> c.isDigit() || c == '.' } }
                )
            }

            if (kind == EditorKind.INCOME && incomeSub == IncomeSub.REIMBURSEMENT) {
                EditorReimbursementSection(
                    selectableReimbursableTxs = selectableReimbursableTxs,
                    selectedExpenseIds = expenseIds.value,
                    reimbursementAutoMatched = reimbursementAutoMatched,
                    selectedReimbursementCents = selectedReimbursementCents,
                    amountCents = amountCents,
                    availableReimbursementCents = { availableReimbursementCents(it) },
                    onToggleAll = {
                        reimbursementSelectionTouched = true
                        reimbursementAutoMatched = false
                        expenseIds.value = if (expenseIds.value.size == selectableReimbursableTxs.size) {
                            emptyList()
                        } else {
                            selectableReimbursableTxs.map { it.id }
                        }
                    },
                    onToggleTransaction = { tx, picked ->
                        reimbursementSelectionTouched = true
                        reimbursementAutoMatched = false
                        expenseIds.value = if (picked) expenseIds.value - tx.id else expenseIds.value + tx.id
                    }
                )
            }

            if (!showsCategory) {
                EditorNotesSection(
                    note = note,
                    noteSuggestions = noteSuggestions,
                    onNoteChanged = { note = it }
                )
            }

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

            if (pendingItem != null) {
                Text(
                    "证据与余额",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
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
        }
    }

    TransactionEditorDialogs(
        showDatePicker = showDatePicker,
        showTimePicker = showTimePicker,
        confirmDelete = confirmDelete,
        showNewCategory = showNewCategory,
        occurredAt = occurredAt,
        editingTransaction = editingTransaction,
        newCategoryParentId = newCategoryParentId,
        parents = parents,
        catKind = catKind,
        viewModel = viewModel,
        onShowDatePickerChanged = { showDatePicker = it },
        onShowTimePickerChanged = { showTimePicker = it },
        onConfirmDeleteChanged = { confirmDelete = it },
        onShowNewCategoryChanged = { showNewCategory = it },
        onOccurredAtChanged = { occurredAt = it },
        onCategoryCreated = { name, shortName, parentId, iconKey, defaultNecessary ->
            viewModel.addCategoryEntity(name, shortName, parentId, iconKey, defaultNecessary, catKind)
            pendingCategoryName = name.trim()
            showNewCategory = false
        },
        onDone = onDone
    )
}

private fun inferPendingAccountId(
    pendingItem: PendingItem,
    accounts: List<AccountEntity>,
    merchantLastAccount: Map<String, String>
): String? {
    val parsed = pendingItem.parsed
    val candidates = accounts.filterNot { it.archived }
        .map { AccountInference.Candidate(it.id, it.name, it.cardTail) }
    val bankResolution = AccountInference.resolveBankAccount(parsed.cardTail, parsed.bankHint, candidates)
    return AccountInference.infer(
        bankMatchedAccountId = bankResolution.accountId,
        merchantHistoryAccountId = parsed.merchant?.let { merchantLastAccount[it] },
        sourcePackage = pendingItem.notification.packageName,
        candidates = candidates.filter { it.name.isNotBlank() },
        bankEvidenceAmbiguous = pendingItem.bankEvidenceAmbiguous || bankResolution.isAmbiguous
    )
}

@Composable
private fun EditorReadOnlyBalanceChange(
    pendingItem: PendingItem?,
    ignoredItems: List<com.assetsking.database.RawNotificationEntity>,
    editingTransaction: TransactionEntity?,
    accounts: List<AccountEntity>,
    selectedAccount: AccountEntity?,
    amountCents: Long,
    type: TransactionType?,
    repository: LedgerRepository
) {
    if (editingTransaction == null && pendingItem == null) return
    var editingEvidence by remember(editingTransaction?.id) { mutableStateOf<ParsedNotification?>(null) }
    LaunchedEffect(editingTransaction?.id) {
        editingEvidence = editingTransaction?.let { transaction ->
            repository.notificationEvidenceForTransaction(transaction.id)
                .asSequence()
                .map { raw -> NotificationParser.parse(raw.content, raw.title) }
                .firstOrNull { it.balanceCents != null && !it.cardTail.isNullOrBlank() }
        }
    }
    val evidence = pendingItem?.let { pending ->
        sequenceOf(pending.parsed)
            .plus(
                ignoredItems.asSequence()
                    .filter { it.processingNote?.contains("kept=${pending.notification.id}") == true }
                    .map { NotificationParser.parse(it.content, it.title) }
            )
            .firstOrNull { it.balanceCents != null && !it.cardTail.isNullOrBlank() }
    } ?: editingEvidence
    val account = editingTransaction
        ?.let { original -> accounts.firstOrNull { it.id == original.accountId } }
        ?: selectedAccount
    val effectiveType = editingTransaction?.type
        ?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() }
        ?: type
    BalanceChangeReadOnly(
        evidence = evidence,
        account = account,
        amountCents = editingTransaction?.amountCents ?: amountCents,
        type = effectiveType
    )
}

/** 基本信息里的只读余额变化：只消费通知自报余额，不用当前账户余额倒推。 */
@Composable
private fun BalanceChangeReadOnly(
    evidence: ParsedNotification?,
    account: AccountEntity?,
    amountCents: Long,
    type: TransactionType?
) {
    val change = balanceChangeFromEvidence(account, type, amountCents, evidence) ?: return
    val before = formatMoney(change.beforeCents)
    val after = formatMoney(change.afterCents)
    val amountSize = when (maxOf(before.length, after.length)) {
        in 0..10 -> 22.sp
        in 11..13 -> 20.sp
        in 14..16 -> 18.sp
        else -> 16.sp
    }
    var fittedSize by remember(before, after) { mutableStateOf(amountSize) }
    Text(
        text = "$before → $after",
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = "余额变化，从 $before 到 $after" },
        style = MaterialTheme.typography.titleLarge.copy(fontSize = fittedSize),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fittedSize.value > 12f) fittedSize = (fittedSize.value - 1f).sp
        }
    )
}

internal data class BalanceChange(val beforeCents: Long, val afterCents: Long)

internal fun balanceChangeFromEvidence(
    account: AccountEntity?,
    type: TransactionType?,
    amountCents: Long,
    evidence: ParsedNotification?
): BalanceChange? {
    if (account == null || account.type != AccountType.ASSET.name || type == null || amountCents <= 0L) return null
    val afterCents = evidence?.balanceCents ?: return null
    if (evidence.cardTail.isNullOrBlank() || account.cardTail != evidence.cardTail) return null
    val delta = BalanceMath.transactionDelta(AccountType.ASSET, type, amountCents)
    return BalanceChange(beforeCents = afterCents - delta, afterCents = afterCents)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditorBasicInformationSection(
    editingTransaction: TransactionEntity?,
    kind: EditorKind,
    directionChosen: Boolean,
    visibleKinds: List<EditorKind>,
    visibleIncomeSubs: List<IncomeSub>,
    incomeSub: IncomeSub,
    repaySub: RepaySub,
    lendingSub: LendingSub,
    amountExpr: String,
    evaluated: Double?,
    occurredAt: Long,
    onKindSelected: (EditorKind) -> Unit,
    onIncomeSubSelected: (IncomeSub) -> Unit,
    onRepaySubSelected: (RepaySub) -> Unit,
    onLendingSubSelected: (LendingSub) -> Unit,
    onAmountChange: (String) -> Unit,
    onDatePickerRequested: () -> Unit,
    onTimePickerRequested: () -> Unit
) {
    EditorSectionCard(title = "基本信息") {
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            visibleKinds.forEach { selectedKind ->
                FilterChip(
                    selected = directionChosen && kind == selectedKind,
                    onClick = { onKindSelected(selectedKind) },
                    label = { Text(selectedKind.label) },
                    colors = strongSelectedFilterChipColors()
                )
            }
        }
        when (kind) {
            EditorKind.INCOME -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleIncomeSubs.forEach { selectedSub ->
                    FilterChip(
                        selected = incomeSub == selectedSub,
                        onClick = { onIncomeSubSelected(selectedSub) },
                        label = { Text(selectedSub.label) },
                        colors = strongSelectedFilterChipColors()
                    )
                }
            }
            EditorKind.REPAY -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RepaySub.entries.forEach { selectedSub ->
                    FilterChip(
                        selected = repaySub == selectedSub,
                        onClick = { onRepaySubSelected(selectedSub) },
                        label = { Text(selectedSub.label) },
                        colors = strongSelectedFilterChipColors()
                    )
                }
            }
            EditorKind.LENDING -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LendingSub.entries.forEach { selectedSub ->
                    FilterChip(
                        selected = lendingSub == selectedSub,
                        onClick = { onLendingSubSelected(selectedSub) },
                        label = { Text(selectedSub.label) },
                        colors = strongSelectedFilterChipColors()
                    )
                }
            }
            else -> Unit
        }
        Text(
            text = when (kind) {
                EditorKind.EXPENSE -> "钱不再收回才记支出；借给他人请选择“借出/收回”。"
                EditorKind.INCOME -> "只有真实收入才记收入；借款、退款、报销请选择对应类型。"
                EditorKind.TRANSFER -> "仅用于本人账户互转，不计收入或支出。"
                EditorKind.REPAY -> "联动信用账户或贷款计划，不计普通支出。"
                EditorKind.LENDING -> "本金在现金与应收之间流转；只有利息计收入。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = amountExpr,
            onValueChange = onAmountChange,
            label = { Text("金额") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        if (amountExpr.any { it in "+-×÷*/" }) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    when {
                        evaluated != null -> "结果 ${formatMoney((evaluated * 100).roundToLong())}"
                        else -> "结果待计算"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text("日期与时间", fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDatePickerRequested, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(formatTime(occurredAt).substringBefore(' '))
            }
            OutlinedButton(onClick = onTimePickerRequested, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(formatTime(occurredAt).substringAfter(' '))
            }
        }
    }
}

@Composable
private fun EditorAccountAndMerchantSection(
    kind: EditorKind,
    incomeSub: IncomeSub,
    repaySub: RepaySub,
    ordinaryAccounts: List<AccountEntity>,
    accountId: String,
    toAccountId: String,
    channel: String,
    savedPaymentChannels: Set<String>,
    customChannelSelected: Boolean,
    orderPlatform: String,
    savedOrderPlatforms: Set<String>,
    customOrderPlatformSelected: Boolean,
    pendingItemPresent: Boolean,
    amountCents: Long,
    transferFeeExpr: String,
    transferFeeCents: Long,
    merchantText: String,
    merchantSuggestions: List<String>,
    onAccountSelected: (String) -> Unit,
    onToAccountSelected: (String) -> Unit,
    onChannelSelected: (String) -> Unit,
    onCustomChannelSelected: (Boolean) -> Unit,
    onOrderPlatformSelected: (String) -> Unit,
    onCustomOrderPlatformSelected: (Boolean) -> Unit,
    onTransferFeeChanged: (String) -> Unit,
    onMerchantChanged: (String) -> Unit
) {
    EditorSectionCard(title = "账户与渠道") {
        val supportsOrderPlatform = kind == EditorKind.EXPENSE ||
            (kind == EditorKind.INCOME && incomeSub in setOf(IncomeSub.INCOME, IncomeSub.REFUND))
        if (supportsOrderPlatform) {
            OrderPlatformDropdownField(
                selectedPlatform = orderPlatform,
                savedPlatforms = savedOrderPlatforms,
                customPlatformSelected = customOrderPlatformSelected,
                onPlatformSelected = onOrderPlatformSelected,
                onCustomPlatformSelected = onCustomOrderPlatformSelected,
                modifier = Modifier.fillMaxWidth()
            )
            if (customOrderPlatformSelected) {
                OutlinedTextField(
                    value = orderPlatform,
                    onValueChange = onOrderPlatformSelected,
                    label = { Text("自定义订单平台") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
        if (kind == EditorKind.TRANSFER || (kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD)) {
            val fromTargets = ordinaryAccounts.filter { it.type == AccountType.ASSET.name && !it.archived }
            val toTargets = if (kind == EditorKind.TRANSFER) {
                ordinaryAccounts.filter { !it.archived && it.type != AccountType.LOAN.name }
            } else {
                ordinaryAccounts.filter { it.type == AccountType.CREDIT.name && !it.archived }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccountDropdownField(
                    label = "转出账户",
                    accounts = fromTargets,
                    selectedAccountId = accountId,
                    onAccountSelected = onAccountSelected,
                    modifier = Modifier.weight(1f)
                )
                AccountDropdownField(
                    label = "转入账户",
                    accounts = toTargets,
                    selectedAccountId = toAccountId,
                    onAccountSelected = onToAccountSelected,
                    modifier = Modifier.weight(1f)
                )
            }
            PaymentChannelDropdownField(
                selectedChannel = channel,
                savedChannels = savedPaymentChannels,
                customChannelSelected = customChannelSelected,
                onChannelSelected = onChannelSelected,
                onCustomChannelSelected = onCustomChannelSelected
            )
            if (customChannelSelected) {
                OutlinedTextField(
                    value = channel,
                    onValueChange = onChannelSelected,
                    label = { Text("自定义支付渠道") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (kind == EditorKind.TRANSFER && pendingItemPresent) {
                OutlinedTextField(
                    value = transferFeeExpr,
                    onValueChange = onTransferFeeChanged,
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
        } else if (kind == EditorKind.LENDING) {
            AccountChannelFields(
                accounts = ordinaryAccounts.filter { it.type == AccountType.ASSET.name },
                selectedAccountId = accountId,
                selectedChannel = channel,
                savedChannels = savedPaymentChannels,
                customChannelSelected = customChannelSelected,
                onAccountSelected = onAccountSelected,
                onChannelSelected = onChannelSelected,
                onCustomChannelSelected = onCustomChannelSelected
            )
        } else {
            AccountChannelFields(
                accounts = ordinaryAccounts,
                selectedAccountId = accountId,
                selectedChannel = channel,
                savedChannels = savedPaymentChannels,
                customChannelSelected = customChannelSelected,
                onAccountSelected = onAccountSelected,
                onChannelSelected = onChannelSelected,
                onCustomChannelSelected = onCustomChannelSelected
            )
        }
        SuggestionField(
            value = merchantText,
            onValueChange = onMerchantChanged,
            suggestions = merchantSuggestions,
            label = when {
                kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME -> "收入来源"
                kind == EditorKind.INCOME && incomeSub == IncomeSub.LOAN_DISBURSEMENT -> "借款来源"
                kind == EditorKind.LENDING -> "对方 / 借款人（可选）"
                else -> "商户"
            },
            required = kind == EditorKind.EXPENSE || kind == EditorKind.INCOME,
            suggestionHint = "历史商户",
            singleLine = true
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditorBusinessAssociationSection(
    kind: EditorKind,
    incomeSub: IncomeSub,
    editingTransaction: TransactionEntity?,
    refundCandidates: List<RefundSourceCandidate>,
    merchantText: String,
    refundOfId: String?,
    loanPlans: List<LoanPlanEntity>,
    loanPlanId: String?,
    accounts: List<AccountEntity>,
    lendingPlans: List<LendingPlanEntity>,
    lendingSub: LendingSub,
    lendingPlanId: String?,
    selectedLendingPlan: LendingPlanEntity?,
    amountCents: Long,
    principalExpr: String,
    interestExpr: String,
    lendingSplitDifference: Long?,
    onRefundSelected: (RefundSourceCandidate?) -> Unit,
    onLoanPlanSelected: (String) -> Unit,
    onLendingPlanSelected: (String) -> Unit,
    onPrincipalChanged: (String) -> Unit,
    onInterestChanged: (String) -> Unit,
    onLendingPlanForDisbursementSelected: (String) -> Unit
) {
    EditorSectionCard(
        title = "业务关联",
        supportingText = "只显示当前类型需要的计划或原流水"
    ) {
        if (kind == EditorKind.INCOME && incomeSub == IncomeSub.REFUND) {
            RefundSourceField(
                candidates = refundCandidates,
                merchantName = merchantText,
                selectedId = refundOfId,
                onSelected = onRefundSelected
            )
        }

        if (kind == EditorKind.INCOME && incomeSub == IncomeSub.LOAN_DISBURSEMENT) {
            val selectableLoanPlans = loanPlans.filter {
                (it.status == "PENDING_DISBURSEMENT" && it.originType == "PENDING_DISBURSEMENT") ||
                    it.id == editingTransaction?.loanPlanId
            }
            Text("关联贷款计划", fontWeight = FontWeight.Medium)
            Text(
                "借款到账会增加现金和该计划的剩余本金，不计入普通收入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selectableLoanPlans.isEmpty()) {
                Text(
                    "暂无可关联的待放款计划，请先在“贷款”页新建贷款计划并选择“待放款”来源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectableLoanPlans.forEach { plan ->
                        FilterChip(
                            selected = loanPlanId == plan.id,
                            onClick = { onLoanPlanSelected(plan.id) },
                            label = { Text("${loanPlanDisplayName(plan, accounts)} · ${formatMoney(plan.principalCents)}") },
                            colors = strongSelectedFilterChipColors()
                        )
                    }
                }
            }
        }

        if (editingTransaction != null && kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME) {
            Text("关联出借利息（可选）", fontWeight = FontWeight.Medium)
            Text(
                "只把这笔收入标为利息收益，不会重复增加本金。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lendingPlans.filter { it.status != LendingPlanStatus.PENDING_DISBURSEMENT }.forEach { plan ->
                    FilterChip(
                        selected = lendingPlanId == plan.id,
                        onClick = { onLendingPlanSelected(plan.id) },
                        label = { Text("${plan.label} · ${plan.borrowerName}") },
                        colors = strongSelectedFilterChipColors()
                    )
                }
            }
        }

        if (kind == EditorKind.LENDING) {
            Text("关联出借计划", fontWeight = FontWeight.Medium)
            Text(
                if (lendingSub == LendingSub.DISBURSEMENT) {
                    "只能选择待借出计划；金额唯一命中时仅预选，仍由你确认。"
                } else {
                    "本金和利息必须按本次实际到账拆分，合计等于通知金额。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val candidates = lendingPlans.filter { plan ->
                if (lendingSub == LendingSub.DISBURSEMENT) {
                    plan.status == LendingPlanStatus.PENDING_DISBURSEMENT &&
                        plan.originType == LendingOriginType.PENDING_DISBURSEMENT
                } else {
                    plan.status != LendingPlanStatus.PENDING_DISBURSEMENT
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                candidates.forEach { plan ->
                    FilterChip(
                        selected = lendingPlanId == plan.id,
                        onClick = { onLendingPlanForDisbursementSelected(plan.id) },
                        label = { Text("${plan.label} · ${plan.borrowerName}") },
                        colors = strongSelectedFilterChipColors()
                    )
                }
            }
            if (candidates.isEmpty()) {
                Text(
                    if (lendingSub == LendingSub.DISBURSEMENT) "没有待借出的计划，请先在贷款页创建。" else "没有可关联的出借计划。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            selectedLendingPlan?.let { plan ->
                Text(
                    if (lendingSub == LendingSub.DISBURSEMENT) {
                        "计划本金 ${formatMoney(plan.principalCents)}"
                    } else {
                        "剩余应收 ${formatMoney(plan.remainingPrincipalCents)} · 已收利息 ${formatMoney(plan.receivedInterestCents)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (lendingSub == LendingSub.REPAYMENT) {
                val splitHasError = amountCents > 0L && lendingSplitDifference != 0L
                OutlinedTextField(
                    value = principalExpr,
                    onValueChange = onPrincipalChanged,
                    label = { Text("收回本金") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = splitHasError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = interestExpr,
                    onValueChange = onInterestChanged,
                    label = { Text("收到利息") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = splitHasError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text(
                    when {
                        amountCents <= 0L -> "本金与利息合计需等于本次到账"
                        lendingSplitDifference == null -> "请填写有效拆分"
                        lendingSplitDifference > 0L -> "本金与利息合计还差 ${formatMoney(lendingSplitDifference)}"
                        lendingSplitDifference < 0L -> "本金与利息合计超出 ${formatMoney(-lendingSplitDifference)}"
                        else -> "拆分合计 ${formatMoney(amountCents)}，与本次到账一致"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (splitHasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EditorCategoryAndNotesSection(
    parents: List<CategoryEntity>,
    childrenOf: (String?) -> List<CategoryEntity>,
    selectedCategoryId: String?,
    kind: EditorKind,
    effectiveNecessity: Boolean,
    editingTransaction: TransactionEntity?,
    isReimbursable: Boolean,
    note: String,
    noteSuggestions: List<String>,
    onSelectCategory: (CategoryEntity) -> Unit,
    onClearCategory: () -> Unit,
    onAddChild: (String) -> Unit,
    onReorderCategories: (List<String>) -> Unit,
    onNecessityChanged: (Boolean) -> Unit,
    onReimbursableChanged: (Boolean) -> Unit,
    onNoteChanged: (String) -> Unit
) {
    EditorSectionCard(title = "分类与备注") {
        CategoryGrid(
            parents = parents,
            childrenOf = childrenOf,
            selectedCategoryId = selectedCategoryId,
            onSelect = onSelectCategory,
            onClearSelection = onClearCategory,
            onAddChild = onAddChild,
            onReorder = onReorderCategories
        )
        if (kind == EditorKind.EXPENSE) {
            val reimbursementLocked = editingTransaction?.let {
                it.amountCents > 0L && it.reimbursedCents >= it.amountCents
            } == true
            Text("必要性", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = effectiveNecessity,
                    onClick = { onNecessityChanged(true) },
                    label = { Text("必要") },
                    colors = strongSelectedFilterChipColors()
                )
                FilterChip(
                    selected = !effectiveNecessity,
                    onClick = { onNecessityChanged(false) },
                    label = { Text("非必要") },
                    colors = strongSelectedFilterChipColors()
                )
            }
            Text("报销", fontWeight = FontWeight.Medium)
            val reimbursementSelected = isReimbursable || reimbursementLocked
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = reimbursementSelected,
                    onClick = { onReimbursableChanged(true) },
                    enabled = !reimbursementLocked,
                    label = { Text("报销") },
                    colors = strongSelectedFilterChipColors()
                )
                FilterChip(
                    selected = !reimbursementSelected,
                    onClick = { onReimbursableChanged(false) },
                    enabled = !reimbursementLocked,
                    label = { Text("不报销") },
                    colors = strongSelectedFilterChipColors()
                )
            }
            Text(
                reimbursementToggleLabel(
                    isReimbursable = isReimbursable,
                    lockedByArrival = reimbursementLocked
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SuggestionField(
            value = note,
            onValueChange = onNoteChanged,
            suggestions = noteSuggestions,
            label = "备注（可选）",
            required = false,
            suggestionHint = "历史备注",
            singleLine = false
        )
    }
}

@Composable
private fun EditorLoanRepaymentSection(
    loanPlans: List<LoanPlanEntity>,
    accounts: List<AccountEntity>,
    loanPlanId: String?,
    loanSuggestion: Pair<LoanPlanEntity, com.assetsking.model.LoanInstallment>?,
    amountCents: Long,
    loanSplitDifference: Long?,
    principalExpr: String,
    interestExpr: String,
    feeExpr: String,
    onLoanPlanSelected: (String) -> Unit,
    onPrincipalChanged: (String) -> Unit,
    onInterestChanged: (String) -> Unit,
    onFeeChanged: (String) -> Unit
) {
    EditorSectionCard(
        title = "还款明细",
        supportingText = "选择贷款计划，并按银行实际扣款拆分本金、利息与费用"
    ) {
        Text("贷款计划", fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            loanPlans.filter { it.status == "ACTIVE" }.forEach { plan ->
                FilterChip(
                    selected = loanPlanId == plan.id,
                    onClick = { onLoanPlanSelected(plan.id) },
                    label = { Text(loanPlanDisplayName(plan, accounts)) },
                    colors = strongSelectedFilterChipColors()
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
            onValueChange = onPrincipalChanged,
            label = { Text("本金") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = splitHasError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        OutlinedTextField(
            value = interestExpr,
            onValueChange = onInterestChanged,
            label = { Text("利息") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = splitHasError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        OutlinedTextField(
            value = feeExpr,
            onValueChange = onFeeChanged,
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
}

@Composable
private fun EditorReimbursementSection(
    selectableReimbursableTxs: List<TransactionEntity>,
    selectedExpenseIds: List<String>,
    reimbursementAutoMatched: Boolean,
    selectedReimbursementCents: Long,
    amountCents: Long,
    availableReimbursementCents: (TransactionEntity) -> Long,
    onToggleAll: () -> Unit,
    onToggleTransaction: (TransactionEntity, Boolean) -> Unit
) {
    EditorSectionCard(
        title = "报销关联",
        supportingText = "选择这笔到账实际覆盖的垫付消费"
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("勾选本次报销的垫付", fontWeight = FontWeight.Medium)
            if (selectableReimbursableTxs.isNotEmpty()) {
                TextButton(onClick = onToggleAll) {
                    Text(if (selectedExpenseIds.size == selectableReimbursableTxs.size) "清空" else "全选")
                }
            }
        }
        if (selectableReimbursableTxs.isEmpty()) {
            Text("没有待报销的消费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                if (reimbursementAutoMatched) {
                    "金额已唯一核对，自动关联 ${selectedExpenseIds.size} 笔"
                } else {
                    "已选 ${selectedExpenseIds.size} 笔 · 待报合计 ${formatMoney(selectedReimbursementCents)} · 到账 ${formatMoney(amountCents)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    reimbursementAutoMatched -> com.assetsking.ui.theme.ReimbursementYellow
                    selectedExpenseIds.isNotEmpty() && selectedReimbursementCents != amountCents -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        selectableReimbursableTxs.forEach { tx ->
            val picked = tx.id in selectedExpenseIds
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable {
                    onToggleTransaction(tx, picked)
                }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${tx.merchant ?: "未命名"} · 可核对 ${formatMoney(availableReimbursementCents(tx))} · ${formatTime(tx.occurredAt)}")
                Icon(
                    if (picked) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (picked) com.assetsking.ui.theme.ReimbursementYellow else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EditorNotesSection(
    note: String,
    noteSuggestions: List<String>,
    onNoteChanged: (String) -> Unit
) {
    EditorSectionCard(title = "备注") {
        SuggestionField(
            value = note,
            onValueChange = onNoteChanged,
            suggestions = noteSuggestions,
            label = "备注（可选）",
            required = false,
            suggestionHint = "历史备注",
            singleLine = false
        )
    }
}

@Composable
private fun EditorSaveBar(
    missing: List<String>,
    showMissing: Boolean,
    amountCents: Long,
    saveError: String?,
    saving: Boolean,
    editingTransaction: TransactionEntity?,
    onSave: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .imePadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 12.dp)
    ) {
        if (missing.isNotEmpty() && showMissing) {
            Text(
                "还需补充：${missing.joinToString("、")}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        saveError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onSave,
            enabled = amountCents > 0 && missing.isEmpty() && !saving,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(
                if (saving) "正在保存…" else if (editingTransaction != null) "保存修改" else "确认入账",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun saveEditor(
    editingTransaction: TransactionEntity?,
    kind: EditorKind,
    incomeSub: IncomeSub,
    repaySub: RepaySub,
    lendingSub: LendingSub,
    amountCents: Long,
    occurredAt: Long,
    accountId: String,
    toAccountId: String,
    channel: String,
    orderPlatform: String,
    merchantText: String,
    selectedCategoryName: String,
    selectedRefundSource: RefundSourceCandidate?,
    effectiveNecessity: Boolean,
    necessity: Boolean?,
    isReimbursable: Boolean,
    note: String,
    loanPlanId: String?,
    lendingPlanId: String?,
    principalCents: Long?,
    interestCents: Long?,
    feeCents: Long?,
    transferFeeCents: Long,
    expenseIds: List<String>,
    bankBalanceForSave: Long?,
    refundOfId: String?,
    pendingItem: PendingItem?,
    viewModel: LedgerViewModel,
    onSavingChanged: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onDone: () -> Unit
) {
    channel.trim().takeIf {
        it.isNotEmpty() && isCustomPaymentChannel(it)
    }?.let(viewModel::rememberPaymentChannel)
    orderPlatform.trim().takeIf {
        it.isNotEmpty() && it !in commonOrderPlatforms
    }?.let(viewModel::rememberOrderPlatform)

    if (editingTransaction != null) {
        val selectedLoanPlanId = loanPlanId
        val selectedLendingPlanId = lendingPlanId
        if (kind == EditorKind.LENDING && lendingSub == LendingSub.REPAYMENT && selectedLendingPlanId != null) {
            viewModel.convertIncomeToLendingRepayment(
                id = editingTransaction.id,
                planId = selectedLendingPlanId,
                totalCents = amountCents,
                principalCents = principalCents ?: 0L,
                interestCents = interestCents ?: 0L,
                note = note.trim().takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                channel = channel.trim().takeIf { it.isNotEmpty() }
            ) { result ->
                onSavingChanged(false)
                result.onSuccess { onDone() }
                    .onFailure { onError(it.message ?: "转换出借收回失败") }
            }
        } else if (kind == EditorKind.INCOME && incomeSub == IncomeSub.INCOME && selectedLendingPlanId != null) {
            viewModel.linkTransactionToLendingInterest(
                id = editingTransaction.id,
                planId = selectedLendingPlanId,
                amountCents = amountCents,
                merchant = merchantText.trim().takeIf { it.isNotEmpty() },
                note = note.trim().takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                channel = channel.trim().takeIf { it.isNotEmpty() }
            ) { result ->
                onSavingChanged(false)
                result.onSuccess { onDone() }
                    .onFailure { onError(it.message ?: "关联出借利息失败") }
            }
        } else if (kind == EditorKind.INCOME && incomeSub == IncomeSub.LOAN_DISBURSEMENT && selectedLoanPlanId != null) {
            viewModel.linkTransactionToLoanDisbursement(
                id = editingTransaction.id,
                planId = selectedLoanPlanId,
                amountCents = amountCents,
                merchant = merchantText.trim().takeIf { it.isNotEmpty() },
                note = note.trim().takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                channel = channel.trim().takeIf { it.isNotEmpty() }
            ) { result ->
                onSavingChanged(false)
                result.onSuccess { onDone() }
                    .onFailure { onError(it.message ?: "关联借款计划失败") }
            }
        } else if (kind == EditorKind.REPAY && repaySub == RepaySub.LOAN && selectedLoanPlanId != null) {
            viewModel.linkTransactionToLoanPayment(
                id = editingTransaction.id,
                planId = selectedLoanPlanId,
                amountCents = amountCents,
                principalCents = principalCents ?: 0L,
                interestCents = interestCents ?: 0L,
                feeCents = feeCents ?: 0L,
                note = note.trim().takeIf { it.isNotEmpty() },
                accountId = accountId,
                occurredAt = occurredAt,
                channel = channel.trim().takeIf { it.isNotEmpty() }
            ) { result ->
                onSavingChanged(false)
                result.onSuccess { onDone() }
                    .onFailure { onError(it.message ?: "关联贷款还款失败") }
            }
        } else {
            val updatedType = if (kind == EditorKind.EXPENSE) TransactionType.EXPENSE else incomeSub.type
            val updatedCategory = when {
                kind == EditorKind.EXPENSE -> selectedCategoryName
                incomeSub == IncomeSub.INCOME -> selectedCategoryName
                incomeSub == IncomeSub.REFUND -> selectedRefundSource?.transaction?.category
                    ?: editingTransaction.category
                    ?: com.assetsking.model.TransactionCategory.UNCATEGORIZED.name
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
                    expenseIds
                ) { result ->
                    onSavingChanged(false)
                    result.onSuccess { onDone() }
                        .onFailure { onError(it.message ?: "更新报销失败") }
                }
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
                    orderPlatform.trim().takeIf { it.isNotEmpty() },
                    isReimbursable,
                    refundOfId
                ) { result ->
                    onSavingChanged(false)
                    result.onSuccess { onDone() }
                        .onFailure { onError(it.message ?: "更新流水失败") }
                }
            }
        }
    } else {
        doSave(
            kind,
            incomeSub,
            repaySub,
            lendingSub,
            amountCents,
            occurredAt,
            accountId,
            toAccountId,
            channel,
            orderPlatform,
            merchantText.trim(),
            if (incomeSub == IncomeSub.REFUND) {
                selectedRefundSource?.transaction?.category ?: editingTransaction?.category.orEmpty()
            } else {
                selectedCategoryName
            },
            if (incomeSub == IncomeSub.REFUND) {
                selectedRefundSource?.transaction?.necessity ?: editingTransaction?.necessity
            } else {
                necessity
            },
            isReimbursable,
            note,
            loanPlanId,
            lendingPlanId,
            principalCents ?: 0L,
            interestCents ?: 0L,
            feeCents ?: 0L,
            transferFeeCents,
            expenseIds,
            bankBalanceForSave,
            refundOfId,
            pendingItem,
            viewModel
        ) { result ->
            onSavingChanged(false)
            result.onSuccess { onDone() }
                .onFailure { onError(it.message ?: "保存失败，账目未发生变化") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionEditorDialogs(
    showDatePicker: Boolean,
    showTimePicker: Boolean,
    confirmDelete: Boolean,
    showNewCategory: Boolean,
    occurredAt: Long,
    editingTransaction: TransactionEntity?,
    newCategoryParentId: String?,
    parents: List<CategoryEntity>,
    catKind: String,
    viewModel: LedgerViewModel,
    onShowDatePickerChanged: (Boolean) -> Unit,
    onShowTimePickerChanged: (Boolean) -> Unit,
    onConfirmDeleteChanged: (Boolean) -> Unit,
    onShowNewCategoryChanged: (Boolean) -> Unit,
    onOccurredAtChanged: (Long) -> Unit,
    onCategoryCreated: (String, String, String?, String, Boolean?) -> Unit,
    onDone: () -> Unit
) {
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = datePickerMillis(occurredAt))
        DatePickerDialog(
            onDismissRequest = { onShowDatePickerChanged(false) },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onOccurredAtChanged(replaceLocalDate(occurredAt, it)) }
                    onShowDatePickerChanged(false)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { onShowDatePickerChanged(false) }) { Text("取消") } }
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
            onDismissRequest = { onShowTimePickerChanged(false) },
            title = { Text("选择时间") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    onOccurredAtChanged(replaceLocalTime(occurredAt, state.hour, state.minute))
                    onShowTimePickerChanged(false)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { onShowTimePickerChanged(false) }) { Text("取消") } }
        )
    }

    if (confirmDelete && editingTransaction != null) {
        AlertDialog(
            onDismissRequest = { onConfirmDeleteChanged(false) },
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
                    onConfirmDeleteChanged(false)
                    onDone()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { onConfirmDeleteChanged(false) }) { Text("取消") } }
        )
    }

    if (showNewCategory) {
        NewCategoryDialog(
            parentId = newCategoryParentId,
            parents = parents,
            catKind = catKind,
            onDismiss = { onShowNewCategoryChanged(false) },
            onCreate = onCategoryCreated
        )
    }
}

private fun initialOrderPlatform(
    editingTransaction: TransactionEntity?,
    pendingItem: PendingItem?,
    parsedMerchant: String?
): String = editingTransaction?.orderPlatform.orEmpty().ifBlank {
    pendingItem?.orderPlatform
        ?: inferOrderPlatform(
            pendingItem?.notification?.packageName,
            pendingItem?.notification?.sourceLabel,
            parsedMerchant
        ).orEmpty()
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
        val platform = transaction.orderPlatform ?: inferOrderPlatform(null, null, transaction.merchant)
        Text("订单平台：${platform ?: "未识别"}")
        Text("支付渠道：${transaction.channel ?: "未设置"}")
        Text("资金账户：$accountName")
        Text("商户：${merchantForDisplay(transaction.merchant, platform) ?: "未设置"}")
        transactionCategoryLabel(transaction.type, transaction.category)?.let { Text("分类：$it") }
        if (transaction.type == TransactionType.REFUND.name) {
            Text(if (transaction.refundOfId == null) "原消费：未关联" else "原消费：已关联")
        }
        Text("日期与时间：${formatTime(transaction.occurredAt)}")
        transaction.note?.let { Text("备注：$it") }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
    }
}

private fun doSave(
    kind: EditorKind, incomeSub: IncomeSub, repaySub: RepaySub, lendingSub: LendingSub,
    amountCents: Long, occurredAt: Long, accountId: String, toAccountId: String, channel: String, orderPlatform: String,
    merchant: String, category: String, necessity: Boolean?, isReimbursable: Boolean, note: String,
    loanPlanId: String?, lendingPlanId: String?, principalCents: Long, interestCents: Long, feeCents: Long, transferFeeCents: Long,
    expenseIds: List<String>, bankBalanceCents: Long?, refundOfId: String?, pendingItem: PendingItem?, viewModel: LedgerViewModel,
    onResult: (Result<Unit>) -> Unit
) {
    when {
        kind == EditorKind.TRANSFER && pendingItem != null ->
            viewModel.confirmTransferNotification(
                pendingItem.notification.id,
                accountId,
                toAccountId,
                amountCents,
                transferFeeCents,
                note,
                onResult
            )
        kind == EditorKind.TRANSFER ->
            viewModel.addTransfer(accountId, toAccountId, "%.2f".format(amountCents / 100.0), note, occurredAt, onResult)
        kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD && pendingItem != null ->
            viewModel.confirmTransferNotification(
                pendingItem.notification.id,
                accountId,
                toAccountId,
                amountCents,
                feeCents = 0L,
                note = note,
                onResult = onResult
            )
        kind == EditorKind.REPAY && repaySub == RepaySub.CREDIT_CARD ->
            viewModel.addTransfer(accountId, toAccountId, "%.2f".format(amountCents / 100.0), note, occurredAt, onResult)
        kind == EditorKind.LENDING && lendingPlanId != null && lendingSub == LendingSub.DISBURSEMENT -> {
            val lendingNote = listOfNotNull(
                merchant.takeIf { it.isNotEmpty() },
                note.takeIf { it.isNotBlank() }
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            if (pendingItem != null) {
                viewModel.confirmLendingDisbursementNotification(
                    notificationId = pendingItem.notification.id,
                    cashAccountId = accountId,
                    planId = lendingPlanId,
                    amountCents = amountCents,
                    note = lendingNote,
                    bankBalanceCents = bankBalanceCents,
                    bankCardTail = bankBalanceCents?.let { pendingItem.parsed.cardTail },
                    onResult = onResult
                )
            } else {
                viewModel.addLendingDisbursement(
                    cashAccountId = accountId,
                    planId = lendingPlanId,
                    amountCents = amountCents,
                    note = lendingNote,
                    occurredAt = occurredAt,
                    onResult = { onResult(it.map { Unit }) }
                )
            }
        }
        kind == EditorKind.LENDING && lendingPlanId != null && lendingSub == LendingSub.REPAYMENT -> {
            if (lendingSplitDifferenceCents(amountCents, principalCents, interestCents) != 0L) {
                onResult(Result.failure(IllegalArgumentException("本金和利息合计必须等于到账金额")))
                return
            }
            if (pendingItem != null) {
                viewModel.confirmLendingRepaymentNotification(
                    notificationId = pendingItem.notification.id,
                    cashAccountId = accountId,
                    planId = lendingPlanId,
                    totalCents = amountCents,
                    principalCents = principalCents,
                    interestCents = interestCents,
                    note = note.takeIf { it.isNotBlank() },
                    bankBalanceCents = bankBalanceCents,
                    bankCardTail = bankBalanceCents?.let { pendingItem.parsed.cardTail },
                    onResult = onResult
                )
            } else {
                viewModel.addLendingRepayment(
                    cashAccountId = accountId,
                    planId = lendingPlanId,
                    principalCents = principalCents,
                    interestCents = interestCents,
                    note = note.takeIf { it.isNotBlank() },
                    occurredAt = occurredAt,
                    onResult = onResult
                )
            }
        }
        kind == EditorKind.REPAY && repaySub == RepaySub.LOAN && loanPlanId != null -> {
            val total = amountCents
            if (loanPaymentSplitDifferenceCents(total, principalCents, interestCents, feeCents) != 0L) {
                onResult(Result.failure(IllegalArgumentException("本金、利息和费用合计必须等于还款金额")))
                return
            }
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
                    bankCardTail = bankBalanceCents?.let { pendingItem.parsed.cardTail },
                    onResult = onResult
                )
            } else {
                viewModel.addLoanPayment(
                    accountId, loanPlanId,
                    "%.2f".format(total / 100.0), "%.2f".format(principalCents / 100.0),
                    "%.2f".format(interestCents / 100.0), "%.2f".format(feeCents / 100.0),
                    note, occurredAt, onResult
                )
            }
        }
        kind == EditorKind.INCOME && incomeSub == IncomeSub.REIMBURSEMENT ->
            viewModel.saveReimbursement(accountId, amountCents, merchant.takeIf { it.isNotEmpty() }, note, occurredAt, expenseIds, onResult)
        kind == EditorKind.INCOME && incomeSub == IncomeSub.LOAN_DISBURSEMENT && loanPlanId != null -> {
            val loanNote = listOfNotNull(
                merchant.takeIf { it.isNotEmpty() },
                note.takeIf { it.isNotBlank() }
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            if (pendingItem != null) {
                viewModel.confirmLoanDisbursementNotification(
                    notificationId = pendingItem.notification.id,
                    cashAccountId = accountId,
                    planId = loanPlanId,
                    amountCents = amountCents,
                    note = loanNote,
                    bankBalanceCents = bankBalanceCents,
                    bankCardTail = bankBalanceCents?.let { pendingItem.parsed.cardTail },
                    onResult = onResult
                )
            } else {
                viewModel.addLoanDisbursement(
                    accountId = accountId,
                    amount = "%.2f".format(amountCents / 100.0),
                    planId = loanPlanId,
                    note = loanNote,
                    occurredAt = occurredAt,
                    onResult = onResult
                )
            }
        }
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
                    isReimbursable = isReimbursable,
                    channel = channel,
                    orderPlatform = orderPlatform,
                    refundOfId = refundOfId,
                    onResult = onResult
                )
            } else {
                viewModel.saveEditorTransaction(
                    accountId, amountCents, type, cat, merchant.takeIf { it.isNotEmpty() },
                    note.takeIf { it.isNotEmpty() }, occurredAt, isReimbursable, necessity, channel, orderPlatform, refundOfId
                ) { result ->
                    if (result.isSuccess && merchant.isNotEmpty()) {
                        viewModel.learnRule(merchant, accountId, type.name, cat)
                    }
                    onResult(result)
                }
            }
        }
    }
}

// ── 组件 ──

@Composable
internal fun SuggestionField(
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
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            .clickable { onValueChange(s) }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                            .heightIn(min = 64.dp)
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
                                label = { Text(child.name) },
                                colors = strongSelectedFilterChipColors()
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { show = !show },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics {
                    stateDescription = if (show) "已展开" else "已收起"
                }
            ) {
                Text("已合并 ${merged.size + 1} 条消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (show) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (show) "收起消息" else "展开消息"
                )
            }
        if (show) {
            (listOf(own) + merged).forEach { n ->
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${n.sourceLabel ?: n.packageName} · ${formatTime(n.postedAt)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(n.content.ifBlank { n.title.orEmpty() }, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (n.id != own.id) {
                            OutlinedButton(onClick = { viewModel.splitNotification(n.id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text("拆分为独立待确认")
                            }
                        } else {
                            Text("当前主消息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("余额校验预览", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("账面余额", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMoney(account.balanceCents), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("本次影响 ${if (delta >= 0) "+" else ""}${formatMoney(delta)} · 计算后应有余额 ${formatMoney(check.expectedCents)}", style = MaterialTheme.typography.bodyMedium)
            Text("通知余额 ${formatMoney(check.bankCents ?: 0)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (check.matches) "与通知余额一致" else "与通知余额不一致 · 差额 ${formatMoney(check.diffCents)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (check.matches) com.assetsking.ui.theme.IncomeGreen else MaterialTheme.colorScheme.error
            )
        if (!check.matches) {
            Text(
                "这笔流水可以继续入库，请选择余额对账口径：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val notificationModifier = Modifier.weight(1f).heightIn(min = 44.dp)
                if (resolution == BalanceResolution.NOTIFICATION) {
                    Button(onClick = { onBalanceResolution(BalanceResolution.NOTIFICATION) }, modifier = notificationModifier) {
                        Text("通知余额", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    OutlinedButton(onClick = { onBalanceResolution(BalanceResolution.NOTIFICATION) }, modifier = notificationModifier) {
                        Text("通知余额", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    }
                }
                val ledgerModifier = Modifier.weight(1f).heightIn(min = 44.dp)
                if (resolution == BalanceResolution.CURRENT_LEDGER) {
                    Button(onClick = { onBalanceResolution(BalanceResolution.CURRENT_LEDGER) }, modifier = ledgerModifier) {
                        Text("当前流水", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    OutlinedButton(onClick = { onBalanceResolution(BalanceResolution.CURRENT_LEDGER) }, modifier = ledgerModifier) {
                        Text("当前流水", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text(
                when (resolution) {
                    BalanceResolution.NOTIFICATION -> "确认时先把银行卡余额重锚到通知余额，再写入这笔流水。"
                    BalanceResolution.CURRENT_LEDGER -> "确认后保留当前账面余额，由本次流水正常扣减；若会透支，确认按钮仍会保持禁用。"
                    null -> "选定后即可直接确认入库，不必退出去手动改余额。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        }
    }
}

@Composable
private fun RefundSourceField(
    candidates: List<RefundSourceCandidate>,
    merchantName: String,
    selectedId: String?,
    onSelected: (RefundSourceCandidate?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = candidates.firstOrNull { it.transaction.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("关联原消费（先填写商户）", fontWeight = FontWeight.Medium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = merchantName.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    selected?.let {
                        "${it.transaction.merchant ?: "未命名"} · ${formatMoney(it.transaction.amountCents)}"
                    } ?: if (selectedId != null) "原关联待校验" else "不关联原消费",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                DropdownMenuItem(
                    text = { Text("不关联原消费") },
                    onClick = { onSelected(null); expanded = false }
                )
                candidates.forEach { candidate ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "${candidate.transaction.merchant ?: "未命名"} · ${formatMoney(candidate.transaction.amountCents)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "可退 ${formatMoney(candidate.remainingCents)} · ${formatTime(candidate.transaction.occurredAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { onSelected(candidate); expanded = false }
                    )
                }
            }
        }
        Text(
            when {
                selected != null -> "将继承原消费分类与必要性，并冲减对应预算"
                selectedId != null -> "原关联仍保留，但当前商户、渠道、账户、时间或金额不再匹配；恢复条件或取消关联后再保存"
                merchantName.trim().isEmpty() -> "请先填写商户名称，下面只显示同商户、同渠道、同账户的可退款流水"
                candidates.isEmpty() -> "请确认商户和支付渠道；没有找到同商户、同渠道、同账户且可退款的消费，可不关联直接入账"
                else -> "未关联时退款不冲减消费分类和预算，可之后编辑补绑"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable {
                            onUpdate(child.id, null, null, p.id, p.iconKey)
                            moveTarget = null
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(p.name) }
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
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable {
                            onMerge(source.id, t.id)
                            mergeSource = null
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(t.name) }
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
                        FilterChip(
                            selected = necessary == true,
                            onClick = { necessary = true },
                            label = { Text("默认必要") },
                            colors = strongSelectedFilterChipColors()
                        )
                        FilterChip(
                            selected = necessary == false,
                            onClick = { necessary = false },
                            label = { Text("默认非必要") },
                            colors = strongSelectedFilterChipColors()
                        )
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
                    .size(48.dp)
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
