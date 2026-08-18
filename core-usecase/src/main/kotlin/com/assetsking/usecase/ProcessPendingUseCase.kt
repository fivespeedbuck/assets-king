package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.database.RawNotificationEntity
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import kotlinx.coroutines.flow.first

/**
 * 处理待确认通知：解析、去重、分类、标记状态。
 * ponytail: 单次批量处理，不做后台常驻 job。
 *
 * 判重时间基准一律用 postedAt（证据原始时间戳），不用 receivedAt：
 * 补扫读回的旧短信 receivedAt=补扫时刻，与直收的那条相差可达 7 天，
 * 用 receivedAt 判重会让同一条短信在待确认箱里出现两条。
 */
class ProcessPendingUseCase(private val repository: LedgerRepository) {
    private val categorizer = RuleBasedCategorizer()

    /** 批量处理所有 NEW 通知，返回处理数量。
     *  匹配已学规则 → 直接入账；新商户 → PENDING_CONFIRMATION */
    suspend fun invoke(): Int {
        val newNotifications = repository.observeNewNotifications().first()
        if (newNotifications.isEmpty()) return 0

        // 判重要跟「已在待确认箱里的」「已确认的」和「已忽略的」都比。
        // IGNORED 必须在内：补扫以新 id 重读收件箱，用户忽略过的短信不拦就会每次复活进箱
        // （REQ 监听 §12：已入箱/已确认/已永久删除的通知不得再次生成候选）。
        // 已忽略只比最近 7 天的，防历史噪音无限增长。
        val seen = repository.pendingNotifications.first().toMutableList()
        val linked = repository.linkedNotifications.first()
        val recentCutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val ignored = repository.ignoredNotifications.first().filter { it.postedAt >= recentCutoff }

        var processed = 0
        for (notification in newNotifications) {
            val parsed = NotificationParser.parse(notification.content, notification.title)

            if (parsed.amountCents == null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(notification.id, "无法识别金额")
                continue
            }

            // ── 自动对账 ──
            // 银行短信自带「尾号3721…余额657.09」，这是银行给的权威数字。一到就按尾号
            // 把余额对上，不必等用户确认这笔流水。判重之前做：重复的那条余额同样有效。
            // 差额校验要带上「待确认变化」（REQ 对账 §4）：同尾号的待确认证据和本条自身
            // 都算进账面应有余额，否则刚收到短信就会误报差额（余额是扣款后的值）。
            val tail = parsed.cardTail
            val bankBalance = parsed.balanceCents
            if (tail != null && bankBalance != null) {
                val pendingDeltas = seen.mapNotNull { other ->
                    val p = NotificationParser.parse(other.content, other.title)
                    if (p.cardTail != tail || p.amountCents == null || p.isExpense == null) return@mapNotNull null
                    com.assetsking.ledger.LedgerDelta(
                        occurredAt = other.postedAt,
                        deltaCents = if (p.isExpense) -p.amountCents else p.amountCents
                    )
                }
                val thisDelta = if (parsed.isExpense != null) {
                    if (parsed.isExpense) -parsed.amountCents else parsed.amountCents
                } else null
                repository.reconcileFromNotification(tail, bankBalance, notification.postedAt, pendingDeltas, thisDelta)
            }

            // ── 内容指纹（REQ 监听 §12）：同一条证据以不同 id 重生 ──
            // 补扫重读收件箱、通知重推产生新 postTime，都会绕过主键 IGNORE。
            // 指纹相同 + 5 分钟窗内 = 同一条证据，直接忽略（保留先入库的那条）。
            // 时间窗防误杀：同一订阅内容相同的两次真实扣款间隔数小时，不判重。
            val fp = notification.contentFingerprint
            val sameEvidence = (seen + linked + ignored).firstOrNull { other ->
                other.id != notification.id &&
                    NotificationMerge.isSameEvidence(fp, notification.postedAt, other.contentFingerprint, other.postedAt)
            }
            if (sameEvidence != null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(notification.id, "与已入库证据内容指纹相同（补扫/重推）")
                continue
            }

            // ── 迟到重复：已确认（LINKED）的同笔通知，不能再生成第二笔账（REQ §81）──
            val confirmedDup = linked.firstOrNull { other ->
                NotificationMerge.isDuplicate(
                    parsed, notification.postedAt,
                    NotificationParser.parse(other.content, other.title), other.postedAt
                )
            }
            if (confirmedDup != null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(notification.id, "与已确认流水重复（同笔证据迟到）")
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
                if (other.id == notification.id) return@firstOrNull false
                NotificationMerge.isDuplicate(
                    parsed, notification.postedAt,
                    NotificationParser.parse(other.content, other.title), other.postedAt
                )
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

            // ── 已忽略判重：用户忽略过的同笔交易，换一个来源再推也不复活 ──
            val ignoredDup = ignored.firstOrNull { other ->
                NotificationMerge.isDuplicate(
                    parsed, notification.postedAt,
                    NotificationParser.parse(other.content, other.title), other.postedAt
                )
            }
            if (ignoredDup != null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(notification.id, "与已忽略通知重复（同笔交易换来源）")
                continue
            }

            // ── 付款 / 退款对冲 ──
            // 下单又整单取消：一笔支出 + 一笔**完全同额**的退款，净额为零。两条都还没确认时
            // 直接抵消，不必让人去确认两笔互相抵消的账。
            //
            // 金额不同的部分退款（买菜少给一斤退 1.5 元）不在此列 —— 那是真实发生的钱，
            // 照常进待确认箱记成退款。
            // 只在两条都还没确认时抵消：付款若已入账，退款就必须如实记成一笔收入。
            val offset = seen.firstOrNull { other ->
                if (other.id == notification.id) return@firstOrNull false
                NotificationMerge.isRefundOffset(
                    parsed, notification.postedAt,
                    NotificationParser.parse(other.content, other.title), other.postedAt
                )
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

    private fun parseMerchant(entity: RawNotificationEntity): String? =
        NotificationParser.parse(entity.content, entity.title).merchant

    // ponytail: 只跟「待确认 + 已确认 + 已忽略」比，不查已入账的历史流水。跨批次的迟到通知
    // （信用卡退款 1-3 个工作日才到）碰不上对冲，会如实记成一笔退款 —— 那也没错。
}
