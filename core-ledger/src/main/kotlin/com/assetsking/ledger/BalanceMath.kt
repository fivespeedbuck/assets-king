package com.assetsking.ledger

import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType

/**
 * 可信账务内核的余额模型（决策 2）。
 *
 * 铁律：账户当前余额 = 最新余额检查点 + 其后（occurredAt > 检查点时刻）所有已确认事件的增量。
 * 检查点是银行短信/手动报告的带时间戳权威余额快照；它把「截至该时刻」的所有账目都 baked in，
 * 之后只重放比它更新的已确认事件。用户随机顺序确认时按交易时间(occurredAt)重放，与确认顺序无关。
 *
 * 这是纯函数、无副作用，是余额计算的唯一数学载体（对应 V5 的 V5Metrics 之于负债口径）。
 * 改公式必须先改这里的单测。
 */
data class BalanceCheckpoint(
    val balanceCents: Long,
    /** 检查点时刻。occurredAt <= checkedAt 的事件已含在 balanceCents 内，不再重复计。 */
    val checkedAt: Long
)

/** 一条已确认账务事件对某个账户的余额增量（分）。 */
data class LedgerDelta(
    val occurredAt: Long,
    val deltaCents: Long
)

object BalanceMath {
    /** 交易对账户余额的增量。ASSET 支出为负；CREDIT/LOAN 负债方向相反（欠款越还越少）。 */
    fun transactionDelta(accountType: AccountType, type: TransactionType, amountCents: Long): Long {
        val assetDelta = when (type) {
            TransactionType.EXPENSE, TransactionType.FEE,
            TransactionType.LOAN_PAYMENT, TransactionType.LOAN_PREPAYMENT -> -amountCents
            TransactionType.INCOME, TransactionType.REFUND,
            TransactionType.LOAN_DISBURSEMENT -> amountCents
        }
        return if (accountType == AccountType.ASSET) assetDelta else -assetDelta
    }

    /** 转账对转出账户的增量。 */
    fun transferOutDelta(accountType: AccountType, amountCents: Long): Long =
        if (accountType == AccountType.ASSET) -amountCents else amountCents

    /** 转账对转入账户的增量。 */
    fun transferInDelta(accountType: AccountType, amountCents: Long): Long =
        if (accountType == AccountType.ASSET) amountCents else -amountCents

    /**
     * 账户余额。
     *
     * @param openingBalanceCents 开户余额（无任何检查点时的基准）。
     * @param checkpoint 最新权威检查点，null 表示尚无检查点（此时所有事件都计在开户余额之上）。
     * @param deltas 该账户全部已确认事件的增量，顺序无关（按 occurredAt 过滤，加法可交换）。
     */
    fun balance(openingBalanceCents: Long, checkpoint: BalanceCheckpoint?, deltas: List<LedgerDelta>): Long {
        val base = checkpoint?.balanceCents ?: openingBalanceCents
        val after = checkpoint?.checkedAt ?: Long.MIN_VALUE
        return base + deltas.filter { it.occurredAt > after }.sumOf { it.deltaCents }
    }
}
