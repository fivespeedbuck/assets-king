package com.assetsking.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.assetsking.database.AccountEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.LendingOriginType
import com.assetsking.database.LendingPlanEntity
import com.assetsking.database.LendingPlanStatus
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.ReimbursementLinkEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.LoanInstallment
import com.assetsking.model.TransactionType
import com.assetsking.usecase.ParsedNotification
import com.assetsking.usecase.ReimbursementMatchCandidate
import com.assetsking.usecase.uniqueExactReimbursementMatch

/**
 * Loads associations needed by the editor without keeping the database read in the main
 * editor Composable's FIR analysis boundary.
 */
@Composable
internal fun TransactionEditorAssociationEffects(
    editingTransaction: TransactionEntity?,
    savedDraftPresent: Boolean,
    repository: LedgerRepository,
    onEditingReimbursementLinksChanged: (List<ReimbursementLinkEntity>) -> Unit,
    onExpenseIdsChanged: (List<String>) -> Unit,
    onReimbursementSelectionTouchedChanged: (Boolean) -> Unit,
    onExpenseHasReimbursementLinkChanged: (Boolean) -> Unit
) {
    LaunchedEffect(editingTransaction?.id) {
        if (editingTransaction?.type == TransactionType.REIMBURSEMENT.name) {
            val links = repository.reimbursementLinks(editingTransaction.id)
            onEditingReimbursementLinksChanged(links)
            if (!savedDraftPresent) {
                onExpenseIdsChanged(links.map { it.expenseTxId })
                onReimbursementSelectionTouchedChanged(true)
            }
        } else if (editingTransaction?.type == TransactionType.EXPENSE.name) {
            onExpenseHasReimbursementLinkChanged(
                repository.reimbursementLinksForExpense(editingTransaction.id).isNotEmpty()
            )
        }
    }
}

/**
 * Restores category/merchant learning prefill. These effects only update editor state; they
 * never confirm a notification or create a transaction.
 */
@Composable
internal fun TransactionEditorPrefillEffects(
    pendingNotificationId: String?,
    categories: List<CategoryEntity>,
    pendingCategoryName: String?,
    catKind: String,
    editingCategoryKind: String,
    editingCategoryInitialized: Boolean,
    editingTransaction: TransactionEntity?,
    parsed: ParsedNotification?,
    ordinaryAccounts: List<AccountEntity>,
    repository: LedgerRepository,
    savedDraftPresent: Boolean,
    kind: EditorKind,
    incomeSub: IncomeSub,
    onCategoryIdChanged: (String?) -> Unit,
    onEditingCategoryInitializedChanged: (Boolean) -> Unit,
    onPendingCategoryNameChanged: (String?) -> Unit,
    onAccountIdChanged: (String) -> Unit,
    onDirectionChosenChanged: (Boolean) -> Unit,
    onKindChanged: (EditorKind) -> Unit,
    onIncomeSubChanged: (IncomeSub) -> Unit
) {
    LaunchedEffect(categories, editingTransaction?.id) {
        if (!editingCategoryInitialized && categories.isNotEmpty()) {
            onCategoryIdChanged(
                categories.firstOrNull {
                    it.name == editingTransaction?.category &&
                        it.kind == editingCategoryKind &&
                        !it.isArchived
                }?.id
            )
            onEditingCategoryInitializedChanged(true)
        }
    }

    LaunchedEffect(categories, pendingCategoryName, catKind) {
        val name = pendingCategoryName ?: return@LaunchedEffect
        categories.firstOrNull { it.name == name && it.kind == catKind && !it.isArchived }?.let {
            onCategoryIdChanged(it.id)
            onPendingCategoryNameChanged(null)
        }
    }

    // 学习规则只负责预填，绝不自动落账。解析出的“退款”证据优先于历史商户类型。
    LaunchedEffect(pendingNotificationId, categories, ordinaryAccounts) {
        if (editingTransaction != null || savedDraftPresent) return@LaunchedEffect
        val parsedNotification = parsed ?: return@LaunchedEffect
        val merchant = parsedNotification.merchant ?: return@LaunchedEffect
        val learned = repository.matchLearnedRule(merchant) ?: return@LaunchedEffect
        ordinaryAccounts.firstOrNull { it.id == learned.accountId && !it.archived }?.let {
            onAccountIdChanged(it.id)
        }

        var learnedKind = kind
        var learnedIncomeSub = incomeSub
        if (parsedNotification.isRefund != true) {
            when (runCatching { TransactionType.valueOf(learned.type) }.getOrNull()) {
                TransactionType.INCOME -> {
                    onDirectionChosenChanged(true)
                    onKindChanged(EditorKind.INCOME)
                    learnedKind = EditorKind.INCOME
                    onIncomeSubChanged(IncomeSub.INCOME)
                    learnedIncomeSub = IncomeSub.INCOME
                }
                TransactionType.REFUND -> {
                    onDirectionChosenChanged(true)
                    onKindChanged(EditorKind.INCOME)
                    learnedKind = EditorKind.INCOME
                    onIncomeSubChanged(IncomeSub.REFUND)
                    learnedIncomeSub = IncomeSub.REFUND
                }
                TransactionType.EXPENSE -> {
                    onDirectionChosenChanged(true)
                    onKindChanged(EditorKind.EXPENSE)
                    learnedKind = EditorKind.EXPENSE
                }
                else -> Unit
            }
        }
        val expectedKind = if (
            learnedKind == EditorKind.INCOME && learnedIncomeSub == IncomeSub.INCOME
        ) {
            "INCOME"
        } else {
            "EXPENSE"
        }
        categories.firstOrNull {
            it.name == learned.category && it.kind == expectedKind && !it.isArchived
        }?.let { onCategoryIdChanged(it.id) }
    }
}

/**
 * Performs editor-only suggestions and unique matches. The callbacks intentionally mutate the
 * same Compose state holders as before, so matching remains a prefill operation rather than an
 * automatic ledger write.
 */
@Composable
internal fun TransactionEditorAutoMatchEffects(
    savedDraftPresent: Boolean,
    amountCents: Long,
    kind: EditorKind,
    incomeSub: IncomeSub,
    selectableReimbursableTxs: List<TransactionEntity>,
    reimbursementSelectionTouched: Boolean,
    availableReimbursementCents: (TransactionEntity) -> Long,
    onExpenseIdsChanged: (List<String>) -> Unit,
    onReimbursementAutoMatchedChanged: (Boolean) -> Unit,
    repaySub: RepaySub,
    occurredAt: Long,
    repository: LedgerRepository,
    onLoanSuggestionChanged: (Pair<LoanPlanEntity, LoanInstallment>?) -> Unit,
    onLoanPlanIdChanged: (String?) -> Unit,
    principalExpr: String,
    interestExpr: String,
    onPrincipalExprChanged: (String) -> Unit,
    onInterestExprChanged: (String) -> Unit,
    onFeeExprChanged: (String) -> Unit,
    loanPlans: List<LoanPlanEntity>,
    currentLoanPlanId: String?,
    autoMatchedLoanPlanId: String?,
    onAutoMatchedLoanPlanIdChanged: (String?) -> Unit,
    lendingSub: LendingSub,
    editingTransactionId: String?,
    lendingSplitAutoFilled: Boolean,
    onLendingSplitAutoFilledChanged: (Boolean) -> Unit,
    lendingPlans: List<LendingPlanEntity>,
    currentLendingPlanId: String?,
    autoMatchedLendingPlanId: String?,
    onLendingPlanIdChanged: (String?) -> Unit,
    onAutoMatchedLendingPlanIdChanged: (String?) -> Unit,
    pendingNotificationId: String?,
    accountId: String,
    balanceResolutionInitialized: Boolean,
    onBalanceResolutionChanged: (BalanceResolution?) -> Unit,
    onBalanceResolutionInitializedChanged: (Boolean) -> Unit
) {
    LaunchedEffect(amountCents, kind, incomeSub, selectableReimbursableTxs, reimbursementSelectionTouched) {
        if (
            !savedDraftPresent &&
            kind == EditorKind.INCOME &&
            incomeSub == IncomeSub.REIMBURSEMENT &&
            amountCents > 0L &&
            !reimbursementSelectionTouched
        ) {
            val match = uniqueExactReimbursementMatch(
                candidates = selectableReimbursableTxs.map {
                    ReimbursementMatchCandidate(it.id, availableReimbursementCents(it))
                },
                arrivalCents = amountCents
            )
            onExpenseIdsChanged(match.orEmpty())
            onReimbursementAutoMatchedChanged(match != null)
        }
    }

    // 贷款还款：金额变化时自动匹配期次（REQ 贷款页§6）
    LaunchedEffect(amountCents, repaySub, kind) {
        if (savedDraftPresent) return@LaunchedEffect
        if (kind == EditorKind.REPAY && repaySub == RepaySub.LOAN && amountCents > 0L) {
            val suggestion = repository.suggestLoanMatch(amountCents, occurredAt)
            onLoanSuggestionChanged(suggestion)
            suggestion?.let { (plan, installment) ->
                if (installment.total.cents == amountCents) {
                    onLoanPlanIdChanged(plan.id)
                    onPrincipalExprChanged(editableMoney(installment.principal.cents))
                    onInterestExprChanged(editableMoney(installment.interest.cents))
                    onFeeExprChanged(editableMoney(installment.fee.cents))
                } else {
                    onPrincipalExprChanged(editableMoney(amountCents))
                    onInterestExprChanged(editableMoney(0L))
                    onFeeExprChanged(editableMoney(0L))
                }
            } ?: run {
                onPrincipalExprChanged(editableMoney(amountCents))
                onInterestExprChanged(editableMoney(0L))
                onFeeExprChanged(editableMoney(0L))
            }
        }
    }

    LaunchedEffect(pendingNotificationId, accountId, amountCents, kind, incomeSub) {
        if (balanceResolutionInitialized) onBalanceResolutionChanged(null)
        onBalanceResolutionInitializedChanged(true)
    }

    // 借款到账也必须挂计划：金额唯一命中某个新计划时只做“预选”，多计划同额不猜。
    LaunchedEffect(amountCents, kind, incomeSub, loanPlans) {
        if (savedDraftPresent) return@LaunchedEffect
        if (kind != EditorKind.INCOME || incomeSub != IncomeSub.LOAN_DISBURSEMENT || amountCents <= 0L) {
            return@LaunchedEffect
        }
        val exact = loanPlans.filter {
            it.status == "PENDING_DISBURSEMENT" &&
                it.originType == "PENDING_DISBURSEMENT" &&
                it.principalCents == amountCents
        }
        if (exact.size == 1) {
            if (currentLoanPlanId == null || currentLoanPlanId == autoMatchedLoanPlanId) {
                onLoanPlanIdChanged(exact.single().id)
                onAutoMatchedLoanPlanIdChanged(exact.single().id)
            }
        } else if (currentLoanPlanId == autoMatchedLoanPlanId) {
            onLoanPlanIdChanged(null)
            onAutoMatchedLoanPlanIdChanged(null)
        }
    }

    // 出借收回编辑：首次切换到收回时按“纯本金到账、利息 0”预填，用户仍可按实际回款修改。
    LaunchedEffect(kind, lendingSub, amountCents, editingTransactionId) {
        if (savedDraftPresent) return@LaunchedEffect
        if (
            kind == EditorKind.LENDING &&
            lendingSub == LendingSub.REPAYMENT &&
            amountCents > 0L &&
            (lendingSplitAutoFilled || (principalExpr.isBlank() && interestExpr.isBlank()))
        ) {
            onPrincipalExprChanged(editableMoney(amountCents))
            onInterestExprChanged(editableMoney(0L))
            onLendingSplitAutoFilledChanged(true)
        }
    }

    LaunchedEffect(amountCents, kind, lendingSub, lendingPlans) {
        if (savedDraftPresent) return@LaunchedEffect
        if (kind != EditorKind.LENDING || lendingSub != LendingSub.DISBURSEMENT || amountCents <= 0L) {
            return@LaunchedEffect
        }
        val exact = lendingPlans.filter {
            it.status == LendingPlanStatus.PENDING_DISBURSEMENT &&
                it.originType == LendingOriginType.PENDING_DISBURSEMENT &&
                it.principalCents == amountCents
        }
        if (exact.size == 1) {
            if (currentLendingPlanId == null || currentLendingPlanId == autoMatchedLendingPlanId) {
                onLendingPlanIdChanged(exact.single().id)
                onAutoMatchedLendingPlanIdChanged(exact.single().id)
            }
        } else if (currentLendingPlanId == autoMatchedLendingPlanId) {
            onLendingPlanIdChanged(null)
            onAutoMatchedLendingPlanIdChanged(null)
        }
    }
}
