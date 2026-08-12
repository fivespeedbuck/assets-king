package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.database.RawNotificationEntity
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * 处理待确认通知：解析、去重、分类、标记状态。
 * ponytail: 单次批量处理，不做后台常驻 job。
 */
class ProcessPendingUseCase(private val repository: LedgerRepository) {
    private val categorizer = RuleBasedCategorizer()

    /** 批量处理所有 NEW 通知，返回处理数量。
     *  匹配已学规则 → 直接入账；新商户 → PENDING_CONFIRMATION */
    suspend fun invoke(): Int {
        val newNotifications = repository.observeNewNotifications().first()
        if (newNotifications.isEmpty()) return 0

        val existingPending = repository.pendingNotifications.first()

        var processed = 0
        for (notification in newNotifications) {
            val parsed = NotificationParser.parse(notification.content, notification.title)

            if (parsed.amountCents == null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(notification.id, "无法识别金额")
                continue
            }

            // 去重：同金额（±100分容差）+ 同商户 + 2分钟内 + 内容高度相似
            val isDuplicate = existingPending.any { pending ->
                pending.id != notification.id &&
                    abs((parsed.amountCents ?: 0) - (parseCents(pending) ?: 0)) <= 100 &&
                    parsed.merchant != null &&
                    parsed.merchant == parseMerchant(pending) &&
                    abs(notification.receivedAt - pending.receivedAt) < 120_000 &&
                    contentSimilarity(notification.content, pending.content) >= 0.7
            }
            if (isDuplicate) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                continue
            }

            // 匹配已学规则 → 自动入账
            val learned = repository.matchLearnedRule(parsed.merchant)
            if (learned != null) {
                val type = runCatching { TransactionType.valueOf(learned.type) }.getOrDefault(TransactionType.EXPENSE)
                repository.confirmNotification(
                    notificationId = notification.id,
                    accountId = learned.accountId,
                    amountCents = parsed.amountCents,
                    type = type,
                    category = learned.category,
                    merchant = parsed.merchant,
                    note = notification.title
                )
            } else {
                repository.updateNotificationStatus(notification.id, "PENDING_CONFIRMATION")
            }
            processed++
        }
        return processed
    }

    fun suggestCategory(merchant: String?, note: String?): TransactionCategory =
        categorizer.categorize(merchant, note)

    private fun parseCents(entity: RawNotificationEntity): Long? =
        NotificationParser.parse(entity.content, entity.title).amountCents

    private fun parseMerchant(entity: RawNotificationEntity): String? =
        NotificationParser.parse(entity.content, entity.title).merchant

    /**
     * 内容相似度（0~1）。用 2-gram Jaccard 快速判断两条通知是否同一事件。
     * 两次独立充值的内容差异很大（订单号、时间戳），只有系统重复投递才高度相似。
     */
    private fun contentSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val bigramsA = a.windowed(2).toSet()
        val bigramsB = b.windowed(2).toSet()
        val intersection = bigramsA.intersect(bigramsB).size
        val union = bigramsA.union(bigramsB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}
