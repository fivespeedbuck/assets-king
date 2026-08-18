package com.assetsking.ledger

/**
 * 报销款覆盖垫付的分摊纯函数（REQ 报销 §3-4）。
 *
 * 按用户勾选顺序逐笔覆盖：每笔垫付全额覆盖，直到报销款耗尽；
 * 最后一笔可部分覆盖，未覆盖部分保留为待报销差额。
 */
object ReimbursementSplit {
    fun cover(expenseCents: List<Long>, amountCents: Long): List<Long> {
        var remaining = amountCents
        return expenseCents.map { c ->
            val cover = minOf(c, remaining)
            remaining -= cover
            cover
        }
    }
}
