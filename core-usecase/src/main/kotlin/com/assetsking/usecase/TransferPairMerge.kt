package com.assetsking.usecase

/**
 * 同额转出+转入自动配对（REQ 待确认交易类型§4）：时间窗内同额、方向相反的两条通知
 * 合并为「账户转账」卡。ponytail: O(n²) 扫描，待确认列表规模小；宁可漏合不误合。
 */
object TransferPairMerge {

    data class Leg(val id: String, val amountCents: Long, val isExpense: Boolean, val postedAt: Long)

    /** 转出腿（isExpense）在前 */
    data class Pair2(val out: Leg, val inLeg: Leg)

    fun findPairs(legs: List<Leg>, windowMillis: Long = 10 * 60_000L): List<Pair2> {
        val used = mutableSetOf<String>()
        val pairs = mutableListOf<Pair2>()
        val sorted = legs.sortedBy { it.postedAt }
        sorted.forEach { a ->
            if (a.id in used || !a.isExpense || a.amountCents <= 0) return@forEach
            val b = sorted.firstOrNull { b ->
                b.id !in used && b.id != a.id && b.isExpense != a.isExpense &&
                    b.amountCents == a.amountCents &&
                    kotlin.math.abs(b.postedAt - a.postedAt) <= windowMillis
            } ?: return@forEach
            used.add(a.id); used.add(b.id)
            pairs.add(Pair2(a, b))
        }
        return pairs
    }
}
