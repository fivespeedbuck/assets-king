package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.database.RawNotificationEntity
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.model.TransactionCategory
import com.assetsking.model.AccountType
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
     *  已学规则只用于编辑页预填；所有有效通知都进入 PENDING_CONFIRMATION。 */
    suspend fun invoke(): Int {
        val newNotifications = repository.observeNewNotifications().first()
        if (newNotifications.isEmpty()) return 0

        // 判重要跟「已在待确认箱里的」「已确认的」和「已忽略的」都比。
        // IGNORED 必须在内：补扫以新 id 重读收件箱，用户忽略过的短信不拦就会每次复活进箱
        // （REQ 监听 §12：已入箱/已确认/已永久删除的通知不得再次生成候选）。
        // IGNORED 记录本身就是永久墓碑；不能按时间裁剪，否则第 8 天补扫会把它复活。
        val seen = repository.pendingNotifications.first().toMutableList()
        val linked = repository.linkedNotifications.first()
        val ignored = repository.ignoredNotifications.first()
        val statementEvidence = mutableListOf<RawNotificationEntity>()
        val accounts = repository.accounts.first().toMutableList()

        var processed = 0
        for (notification in newNotifications) {
            val creditStatement = CreditStatementNotificationParser.parse(
                notification.content,
                notification.title
            )
            if (creditStatement != null) {
                val duplicateStatement = (seen + linked + ignored + statementEvidence).firstOrNull { other ->
                    other.id != notification.id &&
                        NotificationMerge.isSameEvidence(
                            notification.packageName,
                            notification.id,
                            notification.contentFingerprint,
                            notification.postedAt,
                            other.packageName,
                            other.id,
                            other.contentFingerprint,
                            other.postedAt
                        )
                }
                if (duplicateStatement != null) {
                    repository.updateNotificationStatus(notification.id, "IGNORED")
                    repository.updateNotificationNote(
                        notification.id,
                        "与已处理账单证据内容相同 kept=${duplicateStatement.id}"
                    )
                    continue
                }

                val matchedAccounts = accounts.filter {
                    it.type == AccountType.CREDIT.name && it.cardTail == creditStatement.cardTail
                }
                if (matchedAccounts.size == 1) {
                    val account = matchedAccounts.single()
                    val updated = account.copy(
                        statementOriginalDueCents = creditStatement.statementAmountCents,
                        dueDay = creditStatement.dueDay
                    )
                    repository.updateAccount(updated)
                    accounts[accounts.indexOf(account)] = updated
                    repository.updateNotificationStatus(notification.id, "IGNORED")
                    repository.updateNotificationNote(
                        notification.id,
                        "已同步${account.name}本期账单金额与还款日；未生成流水"
                    )
                } else {
                    repository.updateNotificationStatus(notification.id, "IGNORED")
                    repository.updateNotificationNote(
                        notification.id,
                        "识别到信用卡账单，但未找到唯一尾号${creditStatement.cardTail}信用账户；未更新"
                    )
                }
                statementEvidence += notification
                continue
            }

            var parsed = NotificationParser.parse(notification.content, notification.title)

            if (parsed.amountCents == null) {
                val raw = notification.toWechatEvidence()
                val inferredRefund = WechatNotificationEvidence.matchAmountlessRefund(
                    raw,
                    seen.map { it.toWechatEvidence() }
                )
                when {
                    inferredRefund != null -> parsed = parsed.copy(
                        amountCents = inferredRefund.amountCents,
                        isExpense = false,
                        isRefund = true
                    )
                    WechatNotificationEvidence.shouldKeepAmountless(raw) -> {
                        repository.updateNotificationStatus(notification.id, "PENDING_CONFIRMATION")
                        repository.updateNotificationNote(notification.id, "官方通知未提供金额，请补充后确认")
                        seen += notification
                        processed++
                        continue
                    }
                    else -> {
                        repository.updateNotificationStatus(notification.id, "IGNORED")
                        repository.updateNotificationNote(notification.id, "无法识别金额")
                        continue
                    }
                }
            }

            // ── 内容指纹（REQ 监听 §12）：同一条证据以不同 id 重生 ──
            // 补扫重读收件箱、通知重推产生新 postTime，都会绕过主键 IGNORE。
            // 指纹相同 + 5 分钟窗内 = 同一条证据，直接忽略（保留先入库的那条）。
            // 时间窗防误杀：同一订阅内容相同的两次真实扣款间隔数小时，不判重。
            val fp = notification.contentFingerprint
            val sameEvidence = (seen + linked + ignored).firstOrNull { other ->
                other.id != notification.id &&
                    NotificationMerge.isSameEvidence(
                        notification.packageName,
                        notification.id,
                        fp,
                        notification.postedAt,
                        other.packageName,
                        other.id,
                        other.contentFingerprint,
                        other.postedAt
                    )
            }
            if (sameEvidence != null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                // kept=<id>：待确认箱据此把本条显示为该笔的合并证据，并支持拆分（REQ 归并§17-18）
                repository.updateNotificationNote(notification.id, "与已入库证据内容指纹相同（补扫/重推）kept=${sameEvidence.id}")
                continue
            }

            // ── 迟到重复：已确认（LINKED）的同笔通知，不能再生成第二笔账（REQ §81）──
            val confirmedDup = linked.firstOrNull { other ->
                NotificationMerge.isDuplicateAcrossSources(
                    notification.packageName, parsed, notification.postedAt,
                    other.packageName, NotificationParser.parse(other.content, other.title), other.postedAt
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
                NotificationMerge.isDuplicateAcrossSources(
                    notification.packageName, parsed, notification.postedAt,
                    other.packageName, NotificationParser.parse(other.content, other.title), other.postedAt
                )
            }
            if (duplicate != null) {
                // 留下带商户名的那条 —— 商户名决定能不能自动分类、学规则
                if (parsed.merchant != null && parseMerchant(duplicate) == null) {
                    repository.updateNotificationStatus(duplicate.id, "IGNORED")
                    repository.updateNotificationNote(duplicate.id, "与另一条同额通知重复，已保留带商户名的那条 kept=${notification.id}")
                    seen.remove(duplicate)
                } else {
                    repository.updateNotificationStatus(notification.id, "IGNORED")
                    repository.updateNotificationNote(notification.id, "与另一条同额通知重复（同一笔被多个 app 各推一条）kept=${duplicate.id}")
                    continue
                }
            }

            // ── 已忽略判重：用户忽略过的同笔交易，换一个来源再推也不复活 ──
            val ignoredDup = ignored.firstOrNull { other ->
                NotificationMerge.isDuplicateAcrossSources(
                    notification.packageName, parsed, notification.postedAt,
                    other.packageName, NotificationParser.parse(other.content, other.title), other.postedAt
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

            // 铁律：通知和学习规则只能生成/预填待确认候选，不能绕过用户直接形成正式流水。
            // 已学习的账户、类型和分类在统一编辑器打开时预填，仍由用户点击“确认入账”。
            repository.updateNotificationStatus(notification.id, "PENDING_CONFIRMATION")
            // 收下了就进 seen —— 本批后面的通知要能跟它判重。
            // 本批次后续证据也要能与刚进入待确认箱的候选判重。
            seen += notification
            processed++
        }
        return processed
    }

    fun suggestCategory(merchant: String?, note: String?): TransactionCategory =
        categorizer.categorize(merchant, note)

    private fun parseMerchant(entity: RawNotificationEntity): String? =
        NotificationParser.parse(entity.content, entity.title).merchant

    private fun RawNotificationEntity.toWechatEvidence() = WechatNotificationEvidence.Raw(
        id = id,
        packageName = packageName,
        title = title,
        content = content,
        postedAt = postedAt
    )

    // ponytail: 只跟「待确认 + 已确认 + 已忽略」比，不查已入账的历史流水。跨批次的迟到通知
    // （信用卡退款 1-3 个工作日才到）碰不上对冲，会如实记成一笔退款 —— 那也没错。
}
