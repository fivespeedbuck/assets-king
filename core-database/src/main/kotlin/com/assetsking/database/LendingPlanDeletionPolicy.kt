package com.assetsking.database

internal fun requireLendingPlanDeletable(
    originType: String,
    hasActiveFlows: Boolean,
    receivableBalanceStatus: String,
    receivableBalanceCents: Long,
    remainingPrincipalCents: Long
) {
    require(!hasActiveFlows) { "请先删除该计划关联的借出、收回或利息流水" }
    require(receivableBalanceStatus == "CONFIRMED") {
        "请先完成应收账户对账，确认剩余应收本金后才能删除"
    }
    if (originType != LendingOriginType.OPENING_BALANCE) {
        require(receivableBalanceCents == 0L) {
            "请先删除该计划关联流水，使剩余应收本金恢复为零后才能删除"
        }
    }
}
