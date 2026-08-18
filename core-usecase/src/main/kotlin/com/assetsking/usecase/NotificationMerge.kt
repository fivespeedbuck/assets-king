package com.assetsking.usecase

import com.assetsking.ledger.ContentFingerprint
import kotlin.math.abs

/**
 * 通知合并决策的纯函数（可信账务内核的判重口径）。
 *
 * 关键区分：
 * - 判重：同一笔被多个 app 各推一条（同额 + 同向 + 5 分钟内 + 商户不冲突）→ 合并。
 * - 退款对冲：下单又整单取消（同额 + 反向 + 其中一方带「退款」字样）→ 抵消。
 * - 转账：转出 + 转入（同额 + 反向 + 均无退款字样）→ 不是对冲，不能抵消（REQ §350）。
 *
 * 时间基准一律用证据的 postedAt（通知/短信的原始时间戳），不用 receivedAt：
 * 补扫读回的旧短信 receivedAt=补扫时刻，与直收的那条相差可达 7 天，用 receivedAt
 * 判重会让同一条短信在待确认箱里出现两次。
 */
object NotificationMerge {
    const val DEDUP_WINDOW_MS = 5 * 60_000L
    const val OFFSET_WINDOW_MS = 24 * 3600_000L

    /** 内容指纹（REQ 通知监听 §12），实现在 [[ContentFingerprint]]。 */
    fun contentFingerprint(title: String?, content: String): String =
        ContentFingerprint.of(title, content)

    /**
     * 指纹判重：指纹相同且证据时间在同一窗口内，视为同一条证据重生。
     * 时间窗防误杀合法重复（同一订阅内容相同的两次扣款、间隔数小时 → 不判重）。
     */
    fun isSameEvidence(aFp: String, aAt: Long, bFp: String, bAt: Long): Boolean =
        aFp.isNotBlank() && aFp == bFp && abs(aAt - bAt) < DEDUP_WINDOW_MS

    /** 同一笔证据被多个 app 各推一条。 */
    fun isDuplicate(a: ParsedNotification, aAt: Long, b: ParsedNotification, bAt: Long): Boolean {
        if (a.amountCents == null || b.amountCents == null) return false
        if (a.amountCents != b.amountCents) return false
        if (a.isExpense == null || a.isExpense != b.isExpense) return false
        if (abs(aAt - bAt) >= DEDUP_WINDOW_MS) return false
        // 商户一方为空就不冲突；两条都带且不同，才是两笔真消费
        return a.merchant == null || b.merchant == null || a.merchant == b.merchant
    }

    /** 退款对冲：同额 + 反向 + 24h 内，且其中一方带退款字样。净额为零才抵消。 */
    fun isRefundOffset(a: ParsedNotification, aAt: Long, b: ParsedNotification, bAt: Long): Boolean {
        if (a.amountCents == null || b.amountCents == null) return false
        if (a.amountCents != b.amountCents) return false
        if (a.isExpense == null || b.isExpense == null) return false
        if (a.isExpense == b.isExpense) return false
        if (abs(aAt - bAt) >= OFFSET_WINDOW_MS) return false
        return a.isRefund || b.isRefund
    }
}
