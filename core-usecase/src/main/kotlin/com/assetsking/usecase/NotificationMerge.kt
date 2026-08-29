package com.assetsking.usecase

import com.assetsking.ledger.ContentFingerprint
import kotlin.math.abs

/**
 * 通知合并决策的纯函数（可信账务内核的判重口径）。
 *
 * 关键区分：
 * - 判重：同一笔被多个 app 各推一条（同额 + 同向 + 5 分钟内 + 至少一方缺商户）→ 合并。
 * - 退款对冲：下单又整单取消（同额 + 反向 + 其中一方带「退款」字样）→ 抵消。
 * - 转账：转出 + 转入（同额 + 反向 + 均无退款字样）→ 不是对冲，不能抵消（REQ §350）。
 *
 * 时间基准一律用证据的 postedAt（通知/短信的原始时间戳），不用 receivedAt：
 * 补扫读回的旧短信 receivedAt=补扫时刻，与直收的那条相差可达 7 天，用 receivedAt
 * 判重会让同一条短信在待确认箱里出现两次。
 */
object NotificationMerge {
    const val DEDUP_WINDOW_MS = ContentFingerprint.DEDUP_WINDOW_MS
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

    /**
     * 同一 App 的系统通知 key 不同，表示两次独立发布；即使文案和金额完全相同也不能吞。
     * 同 key 仅 postTime 变化才是系统补推/更新。不同来源（短信直收 vs 通知镜像）仍按指纹合并。
     */
    fun isSameEvidence(
        aPackage: String,
        aId: String,
        aFp: String,
        aAt: Long,
        bPackage: String,
        bId: String,
        bFp: String,
        bAt: Long
    ): Boolean = ContentFingerprint.isSameEvidence(
        aPackage,
        aId,
        aFp,
        aAt,
        bPackage,
        bId,
        bFp,
        bAt
    )

    /**
     * 持久化证据必须按当前规则从标题和正文重算指纹，不能信任旧版本保存的指纹。
     */
    fun isSameContentEvidence(
        aPackage: String,
        aId: String,
        aTitle: String?,
        aContent: String,
        aAt: Long,
        bPackage: String,
        bId: String,
        bTitle: String?,
        bContent: String,
        bAt: Long
    ): Boolean {
        // 同一条短信同时经短信接收器和系统短信通知转发时，标题由入口各自生成，
        // 可能分别是「95555」和「招商银行」；正文完全相同才允许合并，不能退化为商户/金额猜测。
        if (ContentFingerprint.isSameSmsMirrorContent(aPackage, aContent, aAt, bPackage, bContent, bAt)) {
            return true
        }
        return isSameEvidence(
            aPackage,
            aId,
            contentFingerprint(aTitle, aContent),
            aAt,
            bPackage,
            bId,
            contentFingerprint(bTitle, bContent),
            bAt
        )
    }

    /** 同一笔证据被多个 app 各推一条。 */
    fun isDuplicate(a: ParsedNotification, aAt: Long, b: ParsedNotification, bAt: Long): Boolean {
        if (a.amountCents == null || b.amountCents == null) return false
        if (a.amountCents != b.amountCents) return false
        if (a.isExpense == null || a.isExpense != b.isExpense) return false
        if (abs(aAt - bAt) >= DEDUP_WINDOW_MS) return false
        // 两张不同银行卡在几秒内恰好发生同额交易，仍是两笔真钱。
        // 真机样本：招商 3683 充值微信零钱 5 元、宁波 3721 充值支付宝 5 元，
        // 旧逻辑只看同额/同向/时间接近，错误吞掉了其中一笔。
        if (a.cardTail != null && b.cardTail != null && a.cardTail != b.cardTail) return false
        // 只要双方都带商户名，就不能仅凭“同名”判定同一笔；同店同额也可能是两笔消费。
        return a.merchant == null || b.merchant == null
    }

    /** 金额/方向判重只用于跨来源证据；同一 App 的两次发布先视为两笔真实事件。 */
    fun isDuplicateAcrossSources(
        aPackage: String,
        a: ParsedNotification,
        aAt: Long,
        bPackage: String,
        b: ParsedNotification,
        bAt: Long
    ): Boolean = aPackage != bPackage && isDuplicate(a, aAt, b, bAt)

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
