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

        // 判重要跟「已经在待确认箱里的」和「本批刚收下的」都比。
        // 原先只比 existingPending —— 补扫一次入库十几条，这十几条互相之间从没比过，
        // 美团一笔退款推的两条（「您有一笔X元的退款」+「订单已取消，X元退款原路返还」）
        // 就都留在箱子里了。
        val seen = repository.pendingNotifications.first().toMutableList()

        var processed = 0
        for (notification in newNotifications) {
            val parsed = NotificationParser.parse(notification.content, notification.title)

            if (parsed.amountCents == null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(notification.id, "无法识别金额")
                continue
            }

            // ── 判重：同一笔被多个 app 各推一条 ──
            // 判据：金额完全相同 + 收支方向一致 + 5 分钟内 + 商户名不冲突。
            //  · 不能靠「同商户」：银行短信和微信的合并通知都没有商户名；
            //  · 不能靠内容相似度：银行短信和通道通知的文字毫无相似之处；
            //  · 方向必须一致：账户间转账（宁波支出 1 元 ⇄ 微信收款 1 元）金额相同、时间相近，
            //    但那是一笔转账的两条腿，合掉就只剩一半；
            //  · 商户名一方为空就算不冲突 —— 两条都带商户名且不同，才是两笔真消费。
            // ponytail: 金额恰好相同、5 分钟内、两条都没有商户名的两笔真实消费会被误合成一笔。
            // 通知原文里没有任何能区分它们的信息，只能这么取舍：宁可漏记，不要虚增。
            val duplicate = seen.firstOrNull { other ->
                other.id != notification.id &&
                    parseCents(other) == parsed.amountCents &&
                    parsed.isExpense != null &&
                    parsed.isExpense == parseIsExpense(other) &&
                    abs(notification.receivedAt - other.receivedAt) < 5 * 60_000 &&
                    (parsed.merchant == null || parseMerchant(other) == null ||
                        parsed.merchant == parseMerchant(other))
            }
            if (duplicate != null) {
                // 留下带商户名的那条 —— 商户名决定能不能自动分类、学规则
                if (parsed.merchant != null && parseMerchant(duplicate) == null) {
                    repository.updateNotificationStatus(duplicate.id, "IGNORED")
                    repository.updateNotificationNote(duplicate.id, "与另一条同额通知重复，已保留带商户名的那条")
                    seen.remove(duplicate)
                } else {
                    repository.updateNotificationStatus(notification.id, "IGNORED")
                    repository.updateNotificationNote(notification.id, "与另一条同额通知重复（同一笔被多个 app 各推一条）")
                    continue
                }
            }

            // ── 付款 / 退款对冲 ──
            // 下单又整单取消：一笔支出 + 一笔**完全同额**的退款，净额为零。两条都还没确认时
            // 直接抵消，不必让人去确认两笔互相抵消的账。
            //
            // 金额不同的部分退款（买菜少给一斤退 1.5 元）不在此列 —— 那是真实发生的钱，
            // 照常进待确认箱记成退款。
            // 只在两条都还没确认时抵消：付款若已入账，退款就必须如实记成一笔收入。
            val offset = seen.firstOrNull { other ->
                val otherDirection = parseIsExpense(other)
                other.id != notification.id &&
                    parseCents(other) == parsed.amountCents &&
                    parsed.isExpense != null && otherDirection != null &&
                    parsed.isExpense != otherDirection &&
                    abs(notification.receivedAt - other.receivedAt) < 24 * 3600_000L
            }
            if (offset != null) {
                repository.updateNotificationStatus(offset.id, "IGNORED")
                repository.updateNotificationNote(offset.id, "与同额退款对冲，净额为零")
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(notification.id, "与同额付款对冲，净额为零")
                seen.remove(offset)
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
            // 收下了就进 seen —— 本批后面的通知要能跟它判重。
            // 被规则自动入账的也要进：银行那条随后到时才认得出是同一笔。
            seen += notification
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

    private fun parseIsExpense(entity: RawNotificationEntity): Boolean? =
        NotificationParser.parse(entity.content, entity.title).isExpense

    // ponytail: 只跟「待确认 + 本批」比，不查已入账的历史流水。跨批次的迟到通知
    // （信用卡退款 1-3 个工作日才到）碰不上对冲，会如实记成一笔退款 —— 那也没错。
}
