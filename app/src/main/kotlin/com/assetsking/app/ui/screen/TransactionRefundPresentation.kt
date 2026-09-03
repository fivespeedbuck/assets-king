package com.assetsking.app.ui.screen

import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType

internal enum class TransactionRefundBadge(val label: String) {
    REFUNDED("已退款"),
    REFUND("退款")
}

/**
 * 退款标记只相信显式的 refundOfId 关系，不用相同金额猜测两笔流水有关联。
 * 同一原消费的有效关联退款合计覆盖全额时，原消费与这些退款均标记“已退款”；
 * 否则只标记退款流水本身。
 */
internal fun transactionRefundBadges(
    transactions: List<TransactionEntity>
): Map<String, TransactionRefundBadge> {
    val activeTransactions = transactions.filter { it.deletedAt == null && it.status == "CONFIRMED" }
    val transactionById = activeTransactions.associateBy { it.id }
    val refundsByOriginalId = activeTransactions
        .filter { it.type == TransactionType.REFUND.name && it.refundOfId != null }
        .groupBy { it.refundOfId!! }
    return buildMap {
        activeTransactions
            .filter { it.type == TransactionType.REFUND.name }
            .forEach { refund ->
                put(refund.id, TransactionRefundBadge.REFUND)
            }
        refundsByOriginalId.forEach { (originalId, refunds) ->
            val original = transactionById[originalId] ?: return@forEach
            if (
                original.type == TransactionType.EXPENSE.name &&
                refunds.sumOf { it.amountCents } == original.amountCents
            ) {
                put(original.id, TransactionRefundBadge.REFUNDED)
                refunds.forEach { refund -> put(refund.id, TransactionRefundBadge.REFUNDED) }
            }
        }
    }
}
