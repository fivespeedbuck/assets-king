package com.assetsking.usecase

import com.assetsking.database.LedgerRepository
import com.assetsking.database.RawNotificationEntity
import com.assetsking.ledger.RuleBasedCategorizer
import com.assetsking.model.TransactionCategory
import com.assetsking.model.AccountType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val processingMutex = Mutex()

    /** 批量处理所有 NEW 通知，返回处理数量。
     *  已学规则只用于编辑页预填；所有有效通知都进入 PENDING_CONFIRMATION。 */
    suspend fun invoke(): Int = processingMutex.withLock { processPendingOnce() }

    private suspend fun processPendingOnce(): Int {
        // 判重要跟「已在待确认箱里的」「已确认的」和「已忽略的」都比。
        // IGNORED 必须在内：补扫以新 id 重读收件箱，用户忽略过的短信不拦就会每次复活进箱
        // （REQ 监听 §12：已入箱/已确认/已永久删除的通知不得再次生成候选）。
        // IGNORED 记录本身就是永久墓碑；不能按时间裁剪，否则第 8 天补扫会把它复活。
        val linked = repository.linkedNotifications.first()
        val ignored = repository.ignoredNotifications.first()
        // v0.1.4 以前已进入 PENDING_CONFIRMATION 的重复证据不会再走 NEW 处理。
        // 每次恢复先收敛旧队列，确认/忽略留下的墓碑也能立即压掉历史副本。
        val seen = reconcileExistingPending(
            repository.pendingNotifications.first(),
            linked,
            ignored
        ).toMutableList()
        val newNotifications = repository.observeNewNotifications().first()
            .sortedWith(compareBy<RawNotificationEntity> { it.postedAt }.thenBy { it.receivedAt })
        if (newNotifications.isEmpty()) return 0

        val statementEvidence = mutableListOf<RawNotificationEntity>()
        val accounts = repository.accounts.first().toMutableList()
        val claimedExternalRefundEvidenceIds = mutableSetOf<String>()

        var processed = 0
        for (notification in newNotifications) {
            val creditStatement = CreditStatementNotificationParser.parse(
                notification.content,
                notification.title
            )
            if (creditStatement != null) {
                val duplicateStatement = (seen + linked + ignored + statementEvidence).firstOrNull { other ->
                    other.id != notification.id &&
                        NotificationMerge.isSameContentEvidence(
                            notification.packageName,
                            notification.id,
                            notification.title,
                            notification.content,
                            notification.postedAt,
                            other.packageName,
                            other.id,
                            other.title,
                            other.content,
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

            // 无金额官方通知也必须先经过内容指纹判重；否则每次补扫都会重新堆进待确认箱。
            val sameEvidenceBeforeAmount = (seen + linked + ignored).firstOrNull { other ->
                other.id != notification.id &&
                    NotificationMerge.isSameContentEvidence(
                        notification.packageName,
                        notification.id,
                        notification.title,
                        notification.content,
                        notification.postedAt,
                        other.packageName,
                        other.id,
                        other.title,
                        other.content,
                        other.postedAt
                    )
            }
            if (sameEvidenceBeforeAmount != null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(
                    notification.id,
                    "与已入库证据内容指纹相同（补扫/重推）kept=${sameEvidenceBeforeAmount.id}"
                )
                continue
            }

            if (parsed.amountCents == null) {
                val raw = notification.toWechatEvidence()
                val inferredRefund = WechatNotificationEvidence.matchAmountlessRefund(
                    raw,
                    seen.map { it.toWechatEvidence() }
                )
                val externalRefund = WechatNotificationEvidence
                    .matchAmountlessRefundToExternalRefund(
                        raw,
                        seen.map { it.toWechatEvidence() },
                        excludedEvidenceIds = claimedExternalRefundEvidenceIds
                    )
                when {
                    externalRefund != null -> {
                        claimedExternalRefundEvidenceIds += externalRefund.evidenceId
                        repository.updateNotificationStatus(notification.id, "IGNORED")
                        repository.updateNotificationNote(
                            notification.id,
                            "微信无金额退款证据已并入有金额退款 kept=${externalRefund.evidenceId}"
                        )
                        continue
                    }
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

            // 外部来源（如美团）先于微信聚合退款到达时，反向收下已经在箱中的
            // 微信无金额证据；后续确认外部退款即可把两条原文写入同一证据组。
            if (parsed.isRefund) {
                val amountlessWechat = WechatNotificationEvidence
                    .matchExternalRefundToAmountless(
                        notification.toWechatEvidence(),
                        seen.map { it.toWechatEvidence() }
                    )
                if (amountlessWechat != null) {
                    repository.updateNotificationStatus(amountlessWechat.evidenceId, "IGNORED")
                    repository.updateNotificationNote(
                        amountlessWechat.evidenceId,
                        "微信无金额退款证据已并入有金额退款 kept=${notification.id}"
                    )
                    seen.removeIf { it.id == amountlessWechat.evidenceId }
                }
            }

            // ── 内容指纹（REQ 监听 §12）：同一条证据以不同 id 重生 ──
            // 补扫重读收件箱、通知重推产生新 postTime，都会绕过主键 IGNORE。
            // 同一系统通知 key + 当前规则重算后的指纹相同 = 同一条证据，不受重投间隔限制。
            // 明确不同的系统 key 仍保留为两次真实发布；跨来源才继续使用 5 分钟窗口。
            val sameEvidence = (seen + linked + ignored).firstOrNull { other ->
                other.id != notification.id &&
                    NotificationMerge.isSameContentEvidence(
                        notification.packageName,
                        notification.id,
                        notification.title,
                        notification.content,
                        notification.postedAt,
                        other.packageName,
                        other.id,
                        other.title,
                        other.content,
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
            //  · 只有一方没有商户名时才允许跨来源合并；双方都有商户名（即使同名）不猜同笔。
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
                // 自动归并产生的 IGNORED 只是 keeper 的附属证据（note 带 kept=），
                // 不能再充当永久墓碑。否则链路 A(待确认) ← B(自动忽略 kept=A)，
                // 后到的 C 会先把 A 换成更高质量的 keeper，再被 B 的“已忽略”反向压掉，
                // 最终 A/B/C 全部 IGNORED，待确认箱一笔不剩。只有用户明确忽略或
                // 已处理的独立证据才有资格阻止换来源重生。
                if (other.isAutoMergedAttachment()) return@firstOrNull false
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

    /**
     * 收敛旧版已经进箱的副本，不删除原始证据：副本改为 IGNORED，保留审计链。
     * 用户主动执行“拆分通知”会把 processingNote 置为空串；这类明确覆盖不自动合回去。
     */
    private suspend fun reconcileExistingPending(
        pending: List<RawNotificationEntity>,
        linked: List<RawNotificationEntity>,
        ignored: List<RawNotificationEntity>
    ): List<RawNotificationEntity> {
        if (pending.size <= 1 && linked.isEmpty() && ignored.isEmpty()) return pending

        val kept = mutableListOf<RawNotificationEntity>()
        // 自动归并文案（含 kept=，以及旧版遗漏 kept= 的同额归并文案）只是某个候选
        // 的附属证据，不是独立的“用户已删除”墓碑；否则它会反过来把自己的 keeper
        // 也压掉，导致多来源证据一起消失。
        val tombstones = linked + ignored.filterNot { it.isAutoMergedAttachment() }
        val ordered = pending.sortedWith(
            compareByDescending<RawNotificationEntity>(::evidenceQuality)
                .thenBy { it.receivedAt }
        )

        for (notification in ordered) {
            if (notification.wasExplicitlySplit()) {
                kept += notification
                continue
            }

            val handled = tombstones.firstOrNull { other ->
                other.id != notification.id && isSameTransactionEvidence(notification, other)
            }
            if (handled != null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(
                    notification.id,
                    "旧待确认副本与已确认/已忽略证据重复"
                )
                continue
            }

            val duplicate = kept.firstOrNull { other ->
                !other.wasExplicitlySplit() &&
                    other.id != notification.id &&
                    isSameTransactionEvidence(notification, other)
            }
            if (duplicate != null) {
                repository.updateNotificationStatus(notification.id, "IGNORED")
                repository.updateNotificationNote(
                    notification.id,
                    "旧待确认副本已自动收敛 kept=${duplicate.id}"
                )
            } else {
                kept += notification
            }
        }
        return kept
    }

    private fun isSameTransactionEvidence(
        a: RawNotificationEntity,
        b: RawNotificationEntity
    ): Boolean {
        if (
            NotificationMerge.isSameContentEvidence(
                a.packageName,
                a.id,
                a.title,
                a.content,
                a.postedAt,
                b.packageName,
                b.id,
                b.title,
                b.content,
                b.postedAt
            )
        ) return true

        // v0.1.4 之前 Android 通知的系统 key 不稳定：同一条通知在重启/补扫后
        // 可能留下不同 id，但正文、指纹和原始时间完全一致。仅用于启动时收敛
        // 已存在的旧待确认队列；新通知仍保留“同包不同 key 视为两次真实发布”的严格口径。
        if (
            a.packageName == b.packageName &&
            a.packageName != "sms" &&
            NotificationMerge.contentFingerprint(a.title, a.content) ==
                NotificationMerge.contentFingerprint(b.title, b.content) &&
            kotlin.math.abs(a.postedAt - b.postedAt) < NotificationMerge.DEDUP_WINDOW_MS
        ) return true

        return NotificationMerge.isDuplicateAcrossSources(
            a.packageName,
            NotificationParser.parse(a.content, a.title),
            a.postedAt,
            b.packageName,
            NotificationParser.parse(b.content, b.title),
            b.postedAt
        )
    }

    private fun evidenceQuality(entity: RawNotificationEntity): Int {
        val parsed = NotificationParser.parse(entity.content, entity.title)
        return (if (parsed.cardTail != null) 8 else 0) +
            (if (parsed.balanceCents != null) 8 else 0) +
            (if (parsed.merchant != null) 4 else 0) +
            (if (parsed.paymentChannel != null) 2 else 0)
    }

    private fun RawNotificationEntity.wasExplicitlySplit(): Boolean = processingNote == ""

    /**
     * 自动跨来源归并留下的附属证据不是用户“永久忽略”的墓碑。
     * 旧版本有一条同额归并文案没有写 kept=，所以同时按稳定文案识别，
     * 让升级后的新证据不会再被历史附属行压掉。
     */
    private fun RawNotificationEntity.isAutoMergedAttachment(): Boolean {
        val note = processingNote.orEmpty()
        return "kept=" in note || "与另一条同额通知重复" in note
    }

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
