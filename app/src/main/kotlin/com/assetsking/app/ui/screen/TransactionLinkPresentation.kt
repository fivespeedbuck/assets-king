package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity

/** 只表达流水与业务计划/规则的审计关系，不改变金额或收支口径。 */
internal enum class TransactionLinkBadge(val label: String, val colorKey: String) {
    BORROWING_PLAN("借款计划", "loan"),
    LOAN_PLAN("贷款计划", "loan"),
    LENDING_PLAN("出借计划", "lending"),
    RECURRING_PAYMENT("周期扣款", "recurring")
}

internal fun transactionLinkBadges(transaction: TransactionEntity): List<TransactionLinkBadge> = buildList {
    transaction.loanPlanId?.let {
        add(
            if (transaction.type == "LOAN_DISBURSEMENT") {
                TransactionLinkBadge.BORROWING_PLAN
            } else {
                TransactionLinkBadge.LOAN_PLAN
            }
        )
    }
    if (transaction.lendingPlanId != null) add(TransactionLinkBadge.LENDING_PLAN)
    if (transaction.recurringRuleId != null) add(TransactionLinkBadge.RECURRING_PAYMENT)
}
