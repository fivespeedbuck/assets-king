package com.assetsking.database

import androidx.room.withTransaction
import com.assetsking.ledger.BalanceMath
import com.assetsking.ledger.ContentFingerprint
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

enum class EvidenceAuditStatus { COMPLETE, WARNING, BROKEN }
enum class EvidenceAuditSeverity { WARNING, BROKEN }

data class EvidenceAuditIssue(
    val code: String,
    val severity: EvidenceAuditSeverity,
    val subjectType: String,
    val subjectId: String,
    val title: String,
    val detail: String,
    val recommendation: String,
    val subjectLabel: String? = null
)

data class EvidenceAuditReport(
    val generatedAt: Long,
    val status: EvidenceAuditStatus,
    val subjectCount: Int,
    val sourceLinkCount: Int,
    val lifecycleEventCount: Int,
    val issues: List<EvidenceAuditIssue>
) {
    val brokenCount: Int get() = issues.count { it.severity == EvidenceAuditSeverity.BROKEN }
    val warningCount: Int get() = issues.count { it.severity == EvidenceAuditSeverity.WARNING }

    fun toPlainText(): String = buildString {
        appendLine("资产大王账本证据链审计")
        appendLine("状态：$status")
        appendLine("对象：$subjectCount · 来源关系：$sourceLinkCount · 生命周期事件：$lifecycleEventCount")
        appendLine("断链：$brokenCount · 提醒：$warningCount")
        appendLine("生成时间：${Instant.ofEpochMilli(generatedAt).atZone(ZoneId.systemDefault())}")
        if (issues.isEmpty()) {
            appendLine("未发现证据链缺口。")
        } else {
            issues.forEachIndexed { index, issue ->
                appendLine()
                appendLine("${index + 1}. [${issue.severity}] ${issue.title}")
                issue.subjectLabel?.let { appendLine("   涉及：$it") }
                appendLine("   ${issue.subjectType}/${issue.subjectId}")
                appendLine("   ${issue.detail}")
                appendLine("   建议：${issue.recommendation}")
            }
        }
    }
}

/**
 * 对当前数据库做一致快照式只读审计。它只报告，不补写来源、不重算后覆盖余额，也不替用户猜关联。
 */
class LedgerEvidenceAuditService(
    private val database: AssetsKingDatabase
) {
    suspend fun run(now: Long = System.currentTimeMillis()): EvidenceAuditReport = database.withTransaction {
        val accounts = database.accountDao().all()
        val transactions = database.transactionDao().allIncludingDeleted()
        val transfers = database.transferDao().allIncludingDeleted()
        val notifications = database.rawNotificationDao().all()
        val loanPlans = database.loanPlanDao().all()
        val lendingPlans = database.lendingPlanDao().all()
        val recurringRules = database.recurringRuleDao().all()
        val reimbursementLinks = database.reimbursementLinkDao().all()
        val cardPlans = database.creditCardInstallmentDao().all()
        val cardAllocations = database.creditCardInstallmentAllocationDao().all()
        val cardSchedules = database.creditCardInstallmentScheduleDao().all()
        val cardMatches = database.creditCardInstallmentPaymentMatchDao().all()
        val checkpoints = database.balanceCheckpointDao().all()
        val evidenceLinks = database.ledgerEvidenceLinkDao().all()
        val lifecycle = database.ledgerLifecycleEventDao().all()

        val issues = mutableListOf<EvidenceAuditIssue>()
        fun issue(
            code: String,
            severity: EvidenceAuditSeverity,
            subjectType: String,
            subjectId: String,
            title: String,
            detail: String,
            recommendation: String
        ) {
            issues += EvidenceAuditIssue(code, severity, subjectType, subjectId, title, detail, recommendation)
        }

        val accountById = accounts.associateBy { it.id }
        val txById = transactions.associateBy { it.id }
        val activeTxById = transactions.filter { it.deletedAt == null }.associateBy { it.id }
        val transferById = transfers.associateBy { it.id }
        val activeTransferById = transfers.filter { it.deletedAt == null }.associateBy { it.id }
        val notificationById = notifications.associateBy { it.id }
        val loanById = loanPlans.associateBy { it.id }
        val lendingById = lendingPlans.associateBy { it.id }
        val recurringById = recurringRules.associateBy { it.id }
        val cardById = cardPlans.associateBy { it.id }
        val scheduleById = cardSchedules.associateBy { it.id }
        val linksBySubject = evidenceLinks.groupBy { it.subjectType to it.subjectId }
        val lifecycleBySubject = lifecycle.groupBy { it.subjectType to it.subjectId }
        val lastLifecycle = lifecycleBySubject.mapValues { (_, rows) -> rows.maxWith(compareBy({ it.occurredAt }, { it.id })) }

        fun money(cents: Long): String {
            val absolute = kotlin.math.abs(cents)
            val sign = if (cents < 0L) "-" else ""
            return "$sign¥${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
        }

        fun date(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        fun notificationLabel(id: String): String? = notificationById[id]?.let { notification ->
            "${notification.sourceLabel ?: "通知"} · ${date(notification.postedAt)}"
        }

        fun subjectLabel(subjectType: String, subjectId: String): String = when (subjectType) {
            EvidenceSubjectType.TRANSACTION -> txById[subjectId]?.let { tx ->
                "${tx.merchant?.takeIf { it.isNotBlank() } ?: tx.category} · ${money(tx.amountCents)} · ${date(tx.occurredAt)}${if (tx.deletedAt != null) "（垃圾箱）" else ""}"
            }
            EvidenceSubjectType.TRANSFER -> transferById[subjectId]?.let { transfer ->
                val from = accountById[transfer.fromAccountId]?.name ?: "未知转出账户"
                val to = accountById[transfer.toAccountId]?.name ?: "未知转入账户"
                "$from → $to · ${money(transfer.amountCents)} · ${date(transfer.occurredAt)}${if (transfer.deletedAt != null) "（垃圾箱）" else ""}"
            }
            EvidenceSubjectType.LOAN_PLAN -> loanById[subjectId]?.let { plan ->
                "${accountById[plan.accountId]?.name ?: "贷款计划"} · 剩余本金 ${money(plan.remainingPrincipalCents)}"
            }
            EvidenceSubjectType.LENDING_PLAN -> lendingById[subjectId]?.let { plan ->
                "${plan.label} · ${plan.borrowerName} · 剩余应收 ${money(plan.remainingPrincipalCents)}"
            }
            EvidenceSubjectType.CARD_INSTALLMENT -> cardById[subjectId]?.let { plan ->
                "${plan.label} · ${accountById[plan.cardAccountId]?.name ?: "信用账户"} · 剩余本金 ${money(plan.remainingPrincipalCents)}"
            }
            EvidenceSubjectType.RECURRING_RULE -> recurringById[subjectId]?.let { rule ->
                "${rule.merchant?.takeIf { it.isNotBlank() } ?: rule.category} · ${money(rule.amountCents)}"
            }
            EvidenceSourceType.RAW_NOTIFICATION -> subjectId.split('|')
                .mapNotNull(::notificationLabel)
                .distinct()
                .joinToString(" / ")
                .ifBlank { null }
            "ACCOUNT" -> accountById[subjectId]?.let { "${it.name} · 当前余额 ${money(it.balanceCents)}" }
            else -> null
        } ?: "相关历史账务记录"

        fun subjectExists(subjectType: String, subjectId: String): Boolean = when (subjectType) {
            EvidenceSubjectType.TRANSACTION -> subjectId in txById
            EvidenceSubjectType.TRANSFER -> subjectId in transferById
            EvidenceSubjectType.LOAN_PLAN -> subjectId in loanById
            EvidenceSubjectType.CARD_INSTALLMENT -> subjectId in cardById
            EvidenceSubjectType.RECURRING_RULE -> subjectId in recurringById
            EvidenceSubjectType.LENDING_PLAN -> subjectId in lendingById
            else -> false
        }

        fun subjectIsActive(subjectType: String, subjectId: String): Boolean = when (subjectType) {
            EvidenceSubjectType.TRANSACTION -> subjectId in activeTxById
            EvidenceSubjectType.TRANSFER -> subjectId in activeTransferById
            EvidenceSubjectType.LOAN_PLAN -> subjectId in loanById
            EvidenceSubjectType.CARD_INSTALLMENT -> subjectId in cardById
            EvidenceSubjectType.RECURRING_RULE -> subjectId in recurringById
            EvidenceSubjectType.LENDING_PLAN -> subjectId in lendingById
            else -> false
        }

        val supportedSubjectTypes = setOf(
            EvidenceSubjectType.TRANSACTION,
            EvidenceSubjectType.TRANSFER,
            EvidenceSubjectType.LOAN_PLAN,
            EvidenceSubjectType.CARD_INSTALLMENT,
            EvidenceSubjectType.RECURRING_RULE,
            EvidenceSubjectType.LENDING_PLAN
        )

        fun requireEvidence(subjectType: String, subjectId: String) {
            val links = linksBySubject[subjectType to subjectId].orEmpty()
            if (links.isEmpty()) {
                issue(
                    "SOURCE_MISSING", EvidenceAuditSeverity.BROKEN, subjectType, subjectId,
                    "缺少来源证据", "该账务对象无法追溯到通知、手工录入、期初记录或系统事件。",
                    "进入专项修复，为对象补选真实来源；不要伪造短信或现金流水。"
                )
            } else if (links.any { it.sourceType == EvidenceSourceType.LEGACY_IMPORT }) {
                issue(
                    "LEGACY_SOURCE", EvidenceAuditSeverity.WARNING, subjectType, subjectId,
                    "历史来源只能证明为旧版迁入", "旧版本没有保存更细的来源关系，现有账务值保留但无法反查到原始输入。",
                    "若仍有合同、短信或原流水，可手工补关联；没有证据时保留为历史迁入。"
                )
            }
        }

        transactions.forEach { requireEvidence(EvidenceSubjectType.TRANSACTION, it.id) }
        transfers.forEach { requireEvidence(EvidenceSubjectType.TRANSFER, it.id) }
        loanPlans.forEach { requireEvidence(EvidenceSubjectType.LOAN_PLAN, it.id) }
        cardPlans.forEach { requireEvidence(EvidenceSubjectType.CARD_INSTALLMENT, it.id) }
        recurringRules.forEach { requireEvidence(EvidenceSubjectType.RECURRING_RULE, it.id) }
        lendingPlans.forEach { requireEvidence(EvidenceSubjectType.LENDING_PLAN, it.id) }

        evidenceLinks.forEach { link ->
            val purgedSubject = lastLifecycle[link.subjectType to link.subjectId]?.action == EvidenceAction.PURGED
            when {
                link.subjectType !in supportedSubjectTypes -> issue(
                    "SUBJECT_TYPE_INVALID", EvidenceAuditSeverity.BROKEN, link.subjectType, link.subjectId,
                    "证据关系的对象类型无效", link.subjectType,
                    "修正为受支持的账务对象类型，不能让未知关系参与账务判断。"
                )
                !subjectExists(link.subjectType, link.subjectId) && !purgedSubject -> issue(
                    "SUBJECT_ORPHAN", EvidenceAuditSeverity.BROKEN, link.subjectType, link.subjectId,
                    "证据关系指向已消失对象", "${link.groupId} 仍引用该对象，但没有永久删除墓碑。",
                    "恢复对象或补齐真实永久删除墓碑；不要直接删除证据关系。"
                )
            }
            val sourceExists = when (link.sourceType) {
                EvidenceSourceType.RAW_NOTIFICATION -> link.sourceId in notificationById
                EvidenceSourceType.RECURRING_RULE ->
                    link.sourceId in recurringById ||
                        lastLifecycle[EvidenceSubjectType.RECURRING_RULE to link.sourceId]?.action == EvidenceAction.PURGED
                EvidenceSourceType.LEDGER_EVENT ->
                    link.sourceId in txById || link.sourceId in transferById ||
                        lastLifecycle[EvidenceSubjectType.TRANSACTION to link.sourceId]?.action == EvidenceAction.PURGED ||
                        lastLifecycle[EvidenceSubjectType.TRANSFER to link.sourceId]?.action == EvidenceAction.PURGED
                EvidenceSourceType.MANUAL_ENTRY,
                EvidenceSourceType.OPENING_BALANCE,
                EvidenceSourceType.SYSTEM_EVENT,
                EvidenceSourceType.LEGACY_IMPORT -> true
                else -> false
            }
            if (!sourceExists) {
                issue(
                    "SOURCE_ORPHAN", EvidenceAuditSeverity.BROKEN, link.subjectType, link.subjectId,
                    "来源引用已断开", "${link.sourceType}/${link.sourceId} 不存在或无法验证。",
                    "恢复来源墓碑或把对象重新关联到真实证据。"
                )
            }
        }

        lastLifecycle.forEach { (subject, last) ->
            val (subjectType, subjectId) = subject
            if (subjectType in supportedSubjectTypes && !subjectExists(subjectType, subjectId) && last.action != EvidenceAction.PURGED) {
                issue(
                    "PURGE_TOMBSTONE_MISSING", EvidenceAuditSeverity.BROKEN, subjectType, subjectId,
                    "账务对象已消失但没有永久删除墓碑", "最后生命周期动作是 ${last.action}。",
                    "核对物理删除来源并补齐真实 PURGED 墓碑；不得把缺失对象静默当成已删除。"
                )
            }
        }

        val activeEventGroupIds = evidenceLinks.asSequence()
            .filter {
                it.subjectType in setOf(EvidenceSubjectType.TRANSACTION, EvidenceSubjectType.TRANSFER) &&
                    subjectIsActive(it.subjectType, it.subjectId)
            }
            .map { it.groupId }
            .toSet()
        val activeGroupsByNotification = evidenceLinks.asSequence()
            .filter {
                it.sourceType == EvidenceSourceType.RAW_NOTIFICATION &&
                    it.groupId in activeEventGroupIds
            }
            .groupBy({ it.sourceId }, { it.groupId })
            .mapValues { (_, groups) -> groups.toSet() }

        activeGroupsByNotification.forEach { (sourceId, groups) ->
                if (groups.size > 1) {
                    issue(
                        "SOURCE_REUSED_ACROSS_GROUPS", EvidenceAuditSeverity.BROKEN, "RAW_NOTIFICATION", sourceId,
                        "同一原始通知被用于多个独立证据组", "证据组：${groups.joinToString()}。同组内可同时生成划转和手续费，但跨组通常代表重复入账。",
                        "保留唯一真实事件组，把其他重复流水移入垃圾箱并核对余额。"
                    )
                }
        }

        // 历史版本保存过会剥掉小数点的旧指纹；审计必须按当前正文规则重算，
        // 否则同一条通知的新旧记录会被误认为两份不同证据。
        val notificationFingerprints = notifications.associateWith { notification ->
            ContentFingerprint.of(notification.title, notification.content)
        }
        notifications.indices.forEach { leftIndex ->
            val left = notifications[leftIndex]
            for (rightIndex in leftIndex + 1 until notifications.size) {
                val right = notifications[rightIndex]
                if (!ContentFingerprint.isSameEvidence(
                        left.packageName,
                        left.id,
                        notificationFingerprints.getValue(left),
                        left.postedAt,
                        right.packageName,
                        right.id,
                        notificationFingerprints.getValue(right),
                        right.postedAt
                    )
                ) continue
                val groups = activeGroupsByNotification[left.id].orEmpty() + activeGroupsByNotification[right.id].orEmpty()
                if (groups.size > 1) {
                    issue(
                        "DUPLICATE_ACTIVE_EVIDENCE", EvidenceAuditSeverity.BROKEN, "RAW_NOTIFICATION", "${left.id}|${right.id}",
                        "同一条证据生成了多组有效账务事件", "有效证据组：${groups.sorted().joinToString()}。",
                        "保留唯一真实事件组，把重复流水移入垃圾箱并重新对账。"
                    )
                }
            }
        }

        val activeTransactionIdsByNotification = activeTxById.values
            .mapNotNull { tx -> tx.notificationId?.let { it to tx.id } }
            .groupBy({ it.first }, { it.second })
        notifications.forEach { notification ->
            val activeGroups = activeGroupsByNotification[notification.id].orEmpty()
            val activeTransactions = activeTransactionIdsByNotification[notification.id].orEmpty()
            val hasActiveLedgerEvent = activeGroups.isNotEmpty() || activeTransactions.isNotEmpty()
            when (notification.status) {
                "LINKED" -> if (!hasActiveLedgerEvent) issue(
                    "NOTIFICATION_LINKED_WITHOUT_EVENT", EvidenceAuditSeverity.BROKEN, "RAW_NOTIFICATION", notification.id,
                    "通知标记为已入账但没有有效流水", "没有有效 Transaction/Transfer 可以证明 LINKED。",
                    "恢复对应流水，或把通知改回待确认/忽略并核对余额。"
                )
                "IGNORED" -> {
                    // 合并转账会故意把原始通知留作墓碑（两条通知共同证明一笔 Transfer），
                    // 这不是“忽略通知驱动流水”；普通忽略证据仍必须报错。
                    val legalTransferTombstone = hasActiveLedgerEvent &&
                        notification.processingNote?.let { note ->
                            note.startsWith("merged-transfer") || note.startsWith("single-transfer")
                        } == true &&
                        activeGroups.any { group ->
                            evidenceLinks.any {
                                it.groupId == group && it.sourceType == EvidenceSourceType.RAW_NOTIFICATION &&
                                    it.sourceId == notification.id && it.subjectType == EvidenceSubjectType.TRANSFER
                            }
                        }
                    if (hasActiveLedgerEvent && !legalTransferTombstone) issue(
                        "NOTIFICATION_IGNORED_WITH_ACTIVE_EVENT", EvidenceAuditSeverity.BROKEN, "RAW_NOTIFICATION", notification.id,
                        "已忽略通知仍驱动有效流水", "有效组 ${activeGroups.sorted()}，流水 ${activeTransactions.sorted()}。",
                        "核对通知与流水归属；保留真实流水时应恢复 LINKED。"
                    )
                }
                "NEW", "PENDING_CONFIRMATION" -> if (hasActiveLedgerEvent) issue(
                    "NOTIFICATION_PENDING_WITH_ACTIVE_EVENT", EvidenceAuditSeverity.BROKEN, "RAW_NOTIFICATION", notification.id,
                    "待处理通知已经产生有效流水", "status=${notification.status}。",
                    "原子完成确认状态，或回滚重复账务事件。"
                )
                "LINKING" -> issue(
                    if (hasActiveLedgerEvent) "NOTIFICATION_LINKING_WITH_ACTIVE_EVENT" else "NOTIFICATION_LINKING_STUCK",
                    if (hasActiveLedgerEvent) EvidenceAuditSeverity.BROKEN else EvidenceAuditSeverity.WARNING,
                    "RAW_NOTIFICATION", notification.id,
                    if (hasActiveLedgerEvent) "确认中的通知已经产生有效流水" else "通知长时间停留在确认中",
                    "LINKING 不应成为持久终态。",
                    "核对原子确认是否中断；有流水则完成 LINKED，无流水则退回待确认。"
                )
                else -> issue(
                    "NOTIFICATION_STATUS_INVALID", EvidenceAuditSeverity.WARNING, "RAW_NOTIFICATION", notification.id,
                    "通知状态无法识别", notification.status,
                    "核对该状态是否为有效迁移值；不要让未知状态参与自动入账。"
                )
            }
        }

        fun checkLifecycle(subjectType: String, subjectId: String, deleted: Boolean) {
            val last = lastLifecycle[subjectType to subjectId]
            if (last == null) {
                issue(
                    "LIFECYCLE_MISSING", EvidenceAuditSeverity.BROKEN, subjectType, subjectId,
                    "缺少生命周期记录", "无法证明对象何时创建、删除或恢复。",
                    "运行版本迁移补种或专项修复，不要直接修改业务表。"
                )
            } else if (deleted && last.action !in setOf(EvidenceAction.TRASHED, "MIGRATED_TRASHED")) {
                issue(
                    "TRASH_STATE_MISMATCH", EvidenceAuditSeverity.BROKEN, subjectType, subjectId,
                    "垃圾箱状态与证据不一致", "业务行已在垃圾箱，但最后证据动作是 ${last.action}。",
                    "核对删除操作是否完整提交，再修复生命周期事件。"
                )
            } else if (!deleted && last.action in setOf(EvidenceAction.TRASHED, EvidenceAction.PURGED, "MIGRATED_TRASHED")) {
                issue(
                    "ACTIVE_STATE_MISMATCH", EvidenceAuditSeverity.BROKEN, subjectType, subjectId,
                    "有效状态与生命周期不一致", "业务行仍有效，但最后证据动作是 ${last.action}。",
                    "核对是否漏记恢复动作，禁止直接覆盖余额。"
                )
            }
        }
        transactions.forEach { checkLifecycle(EvidenceSubjectType.TRANSACTION, it.id, it.deletedAt != null) }
        transfers.forEach { checkLifecycle(EvidenceSubjectType.TRANSFER, it.id, it.deletedAt != null) }
        loanPlans.forEach { checkLifecycle(EvidenceSubjectType.LOAN_PLAN, it.id, false) }
        cardPlans.forEach { checkLifecycle(EvidenceSubjectType.CARD_INSTALLMENT, it.id, false) }
        recurringRules.forEach { checkLifecycle(EvidenceSubjectType.RECURRING_RULE, it.id, false) }
        lendingPlans.forEach { checkLifecycle(EvidenceSubjectType.LENDING_PLAN, it.id, false) }

        fun validateTransactionEffectState(state: JSONObject) {
            when (state.getString("kind")) {
                "LOAN" -> trashJsonToLoanPlan(state.getJSONObject("plan"))
                "LENDING" -> trashJsonToLendingPlan(state.getJSONObject("plan"))
                "REIMBURSEMENT" -> {
                    val expenses = state.getJSONArray("expenses")
                    for (index in 0 until expenses.length()) {
                        expenses.getJSONObject(index).apply {
                            getString("id")
                            getLong("reimbursedCents")
                        }
                    }
                }
                else -> error("未知流水快照类型：${state.getString("kind")}")
            }
        }

        fun currentTransactionEffectState(tx: TransactionEntity): JSONObject? {
            tx.loanPlanId?.let { planId ->
                return loanById[planId]?.let { plan ->
                    JSONObject().put("kind", "LOAN").put("plan", loanPlanToTrashJson(plan))
                }
            }
            tx.lendingPlanId?.let { planId ->
                return lendingById[planId]?.let { plan ->
                    JSONObject().put("kind", "LENDING").put("plan", lendingPlanToTrashJson(plan))
                }
            }
            if (tx.type == TransactionType.REIMBURSEMENT.name) {
                val expenses = JSONArray()
                reimbursementLinks.asSequence()
                    .filter { it.reimbursementTxId == tx.id }
                    .sortedBy { it.expenseTxId }
                    .forEach { link ->
                        val expense = activeTxById[link.expenseTxId] ?: return null
                        expenses.put(JSONObject().put("id", expense.id).put("reimbursedCents", expense.reimbursedCents))
                    }
                return JSONObject().put("kind", "REIMBURSEMENT").put("expenses", expenses)
            }
            return null
        }

        transactions.filter { it.deletedAt != null }.forEach { tx ->
            val effectful = tx.loanPlanId != null || tx.lendingPlanId != null || tx.type == TransactionType.REIMBURSEMENT.name
            if (!effectful) return@forEach
            val context = tx.trashContextJson
            if (context.isNullOrBlank()) {
                issue(
                    "TRASH_SNAPSHOT_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id,
                    "垃圾箱流水缺少依赖快照", "${tx.type} 的恢复会改动关联计划或报销状态。",
                    "不要强行恢复；从删除审计记录重建 before/after 快照后再处理。"
                )
                return@forEach
            }
            val snapshot = runCatching {
                val root = JSONObject(context)
                val before = root.getJSONObject("before")
                val after = root.getJSONObject("after")
                validateTransactionEffectState(before)
                validateTransactionEffectState(after)
                require(before.getString("kind") == after.getString("kind"))
                before to after
            }.getOrElse { error ->
                issue(
                    "TRASH_SNAPSHOT_DAMAGED", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id,
                    "垃圾箱流水快照损坏", error.message ?: "无法解析 before/after 快照。",
                    "保留垃圾箱记录并专项修复快照；不得绕过检查恢复。"
                )
                null
            } ?: return@forEach
            val current = currentTransactionEffectState(tx)
            if (current == null || !jsonStructurallyEquals(current, snapshot.second)) {
                issue(
                    "TRASH_SNAPSHOT_CONFLICT", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id,
                    "垃圾箱流水已与后续业务状态冲突", "当前关联状态不再等于删除完成后的 after 快照。",
                    "保留在垃圾箱；先处理后来发生的计划、期次或报销事件，再决定是否恢复。"
                )
            }
        }

        fun validateTransferEffectState(state: JSONObject) {
            val matches = state.getJSONArray("matches")
            for (index in 0 until matches.length()) trashJsonToInstallmentMatch(matches.getJSONObject(index))
            val schedules = state.getJSONArray("schedules")
            for (index in 0 until schedules.length()) trashJsonToInstallmentSchedule(schedules.getJSONObject(index))
            val plans = state.getJSONArray("plans")
            for (index in 0 until plans.length()) trashJsonToInstallmentPlan(plans.getJSONObject(index))
            if (!state.isNull("lendingPlan")) trashJsonToLendingPlan(state.getJSONObject("lendingPlan"))
        }

        fun currentTransferEffectState(tf: TransferEntity): JSONObject {
            val matches = cardMatches.filter { it.transferId == tf.id }
            val planIds = matches.map { it.planId }.toSet()
            val schedules = cardSchedules.filter { it.planId in planIds }
            val plans = cardPlans.filter { it.id in planIds }
            val lendingPlan = tf.lendingPlanId?.let(lendingById::get)
            return JSONObject()
                .put("matches", JSONArray().apply {
                    matches.sortedWith(compareBy({ it.scheduleId }, { it.planId })).forEach { put(installmentMatchToTrashJson(it)) }
                })
                .put("schedules", JSONArray().apply { schedules.sortedBy { it.id }.forEach { put(installmentScheduleToTrashJson(it)) } })
                .put("plans", JSONArray().apply { plans.sortedBy { it.id }.forEach { put(installmentPlanToTrashJson(it)) } })
                .put("lendingPlan", lendingPlan?.let(::lendingPlanToTrashJson) ?: JSONObject.NULL)
        }

        transfers.filter { it.deletedAt != null }.forEach { tf ->
            val context = tf.trashContextJson
            if (context.isNullOrBlank()) {
                issue(
                    "TRASH_SNAPSHOT_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id,
                    "垃圾箱划转缺少依赖快照", "划转恢复必须核对信用分期和出借计划状态。",
                    "不要强行恢复；从删除审计记录重建 before/after 快照后再处理。"
                )
                return@forEach
            }
            val after = runCatching {
                val root = JSONObject(context)
                val before = root.getJSONObject("before")
                val parsedAfter = root.getJSONObject("after")
                validateTransferEffectState(before)
                validateTransferEffectState(parsedAfter)
                parsedAfter
            }.getOrElse { error ->
                issue(
                    "TRASH_SNAPSHOT_DAMAGED", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id,
                    "垃圾箱划转快照损坏", error.message ?: "无法解析 before/after 快照。",
                    "保留垃圾箱记录并专项修复快照；不得绕过检查恢复。"
                )
                null
            } ?: return@forEach
            if (!jsonStructurallyEquals(currentTransferEffectState(tf), after)) {
                issue(
                    "TRASH_SNAPSHOT_CONFLICT", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id,
                    "垃圾箱划转已与后续业务状态冲突", "当前分期/出借状态不再等于删除完成后的 after 快照。",
                    "保留在垃圾箱；先处理后来发生的还款、期次或出借事件，再决定是否恢复。"
                )
            }
        }

        transactions.forEach { tx ->
            val type = runCatching { TransactionType.valueOf(tx.type) }.getOrNull()
            if (type == null) {
                issue("TYPE_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "流水类型无效", tx.type, "选择正确业务类型后重新核对金额影响。")
                return@forEach
            }
            if (tx.amountCents <= 0L) {
                issue("AMOUNT_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "流水金额无效", "amountCents=${tx.amountCents}", "修正为大于 0 的金额。")
            }
            if (type == TransactionType.LOAN_PAYMENT && tx.principalCents + tx.interestCents + tx.feeCents != tx.amountCents) {
                issue("LOAN_SPLIT_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "贷款还款拆分不等于总额", "本金 ${tx.principalCents} + 利息 ${tx.interestCents} + 手续费 ${tx.feeCents} != ${tx.amountCents}", "重新核对银行扣款明细并拆分。")
            }
            if (type == TransactionType.LOAN_PREPAYMENT && tx.principalCents + tx.feeCents != tx.amountCents) {
                issue("PREPAY_SPLIT_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "提前还款拆分不等于现金扣款", "本金 ${tx.principalCents} + 手续费 ${tx.feeCents} != ${tx.amountCents}", "按实际本金和违约金/手续费重新拆分。")
            }
            if (type in setOf(TransactionType.LOAN_DISBURSEMENT, TransactionType.LOAN_PAYMENT, TransactionType.LOAN_PREPAYMENT)) {
                val planId = tx.loanPlanId
                if (planId == null || planId !in loanById) {
                    issue("LOAN_LINK_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "贷款流水缺少计划关联", "${tx.type} 必须指向有效贷款计划。", "在流水编辑中补关联正确计划。")
                }
            }
            tx.lendingPlanId?.let { planId ->
                if (planId !in lendingById) {
                    issue("LENDING_LINK_ORPHAN", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "出借利息关联已断开", planId, "恢复对应出借计划或取消错误关联。")
                } else if (type != TransactionType.INCOME) {
                    issue("LENDING_INTEREST_TYPE_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "出借利息不是收入流水", tx.type, "把本金改为划转；只有实际利息保留收入。")
                }
            }
            tx.recurringRuleId?.let { ruleId ->
                val purgedRuleTombstone = lastLifecycle[EvidenceSubjectType.RECURRING_RULE to ruleId]?.action == EvidenceAction.PURGED
                if (ruleId !in recurringById && !purgedRuleTombstone) {
                    issue("RECURRING_ORPHAN", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "周期付款关联已失效", ruleId, "取消无效关联或恢复对应周期规则。")
                }
            }
            if (type == TransactionType.REFUND) {
                val original = tx.refundOfId?.let(txById::get)
                when {
                    tx.refundOfId == null -> issue("REFUND_UNLINKED", EvidenceAuditSeverity.WARNING, EvidenceSubjectType.TRANSACTION, tx.id, "退款未关联原消费", "预算和分类无法证明应冲减哪一笔消费。", "在流水编辑中选择原消费；确实无法确认时保留提醒。")
                    original == null -> issue("REFUND_ORPHAN", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "退款关联的原消费不存在", tx.refundOfId, "恢复原消费或解除错误关联后重新核对。")
                    original.type != TransactionType.EXPENSE.name -> issue("REFUND_TARGET_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, tx.id, "退款目标不是消费流水", original.type, "改关联到真实消费。")
                }
            }
        }

        transactions.filter { it.type == TransactionType.REFUND.name && it.deletedAt == null && it.refundOfId != null }
            .groupBy { it.refundOfId!! }
            .forEach { (expenseId, refunds) ->
                val expense = txById[expenseId]
                if (expense != null && refunds.sumOf { it.amountCents } > expense.amountCents) {
                    issue("REFUND_OVERFLOW", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, expenseId, "累计退款超过原消费", "退款 ${refunds.sumOf { it.amountCents }} > 消费 ${expense.amountCents}", "核对重复退款或错误原消费关联。")
                }
            }

        val activeReimbursements = transactions.filter { it.deletedAt == null && it.type == TransactionType.REIMBURSEMENT.name }.associateBy { it.id }
        reimbursementLinks.forEach { link ->
            val reimbursement = txById[link.reimbursementTxId]
            val expense = txById[link.expenseTxId]
            if (reimbursement == null || expense == null) {
                issue("REIMBURSEMENT_ORPHAN", EvidenceAuditSeverity.BROKEN, "REIMBURSEMENT_LINK", "${link.reimbursementTxId}:${link.expenseTxId}", "报销关联存在孤儿引用", "到账或垫付流水已不存在。", "恢复相关流水或删除错误关联后重算报销状态。")
            } else if (reimbursement.type != TransactionType.REIMBURSEMENT.name || expense.type != TransactionType.EXPENSE.name || link.coveredCents <= 0L) {
                issue("REIMBURSEMENT_LINK_INVALID", EvidenceAuditSeverity.BROKEN, "REIMBURSEMENT_LINK", "${link.reimbursementTxId}:${link.expenseTxId}", "报销关联类型或金额无效", "coveredCents=${link.coveredCents}", "重新选择垫付消费并核对覆盖金额。")
            }
        }
        activeReimbursements.values.forEach { reimbursement ->
            val sum = reimbursementLinks.filter { it.reimbursementTxId == reimbursement.id }.sumOf { it.coveredCents }
            if (sum == 0L) issue("REIMBURSEMENT_UNLINKED", EvidenceAuditSeverity.WARNING, EvidenceSubjectType.TRANSACTION, reimbursement.id, "报销到账未关联垫付", "该笔到账只改变现金，无法证明冲减了哪些垫付。", "选择对应的待报销消费。")
            else if (sum > reimbursement.amountCents) issue("REIMBURSEMENT_OVERFLOW", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, reimbursement.id, "报销覆盖额超过到账", "$sum > ${reimbursement.amountCents}", "重新分配垫付覆盖金额。")
        }
        transactions.filter { it.type == TransactionType.EXPENSE.name }.forEach { expense ->
            val expected = reimbursementLinks.filter { it.expenseTxId == expense.id && it.reimbursementTxId in activeReimbursements }.sumOf { it.coveredCents }
            if (expense.reimbursedCents != expected) issue("REIMBURSED_BALANCE_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, expense.id, "垫付已报销金额不可由有效到账重算", "记录 ${expense.reimbursedCents}，证据链重算 $expected", "从有效报销关联重建已报销金额。")
        }

        transfers.forEach { tf ->
            if (tf.amountCents <= 0L || tf.fromAccountId == tf.toAccountId || tf.fromAccountId !in accountById || tf.toAccountId !in accountById) {
                issue("TRANSFER_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id, "划转两端或金额无效", "${tf.fromAccountId} → ${tf.toAccountId} / ${tf.amountCents}", "修复两端自有账户；对外收付款改用经济实质类型。")
            }
            tf.lendingPlanId?.let { planId ->
                val plan = lendingById[planId]
                when {
                    plan == null -> issue("LENDING_TRANSFER_ORPHAN", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id, "出借划转关联已断开", planId, "恢复对应出借计划或取消错误关联。")
                    tf.lendingRole == LendingTransferRole.DISBURSEMENT && (tf.toAccountId != plan.receivableAccountId || tf.fromAccountId == plan.receivableAccountId) ->
                        issue("LENDING_DISBURSEMENT_DIRECTION_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id, "借出本金方向错误", "${tf.fromAccountId} → ${tf.toAccountId}", "借出必须从现金资产划到该计划的应收资产。")
                    tf.lendingRole == LendingTransferRole.PRINCIPAL_REPAYMENT && (tf.fromAccountId != plan.receivableAccountId || tf.toAccountId == plan.receivableAccountId) ->
                        issue("LENDING_REPAYMENT_DIRECTION_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id, "收回本金方向错误", "${tf.fromAccountId} → ${tf.toAccountId}", "本金收回必须从应收资产划回现金资产。")
                    tf.lendingRole !in setOf(LendingTransferRole.DISBURSEMENT, LendingTransferRole.PRINCIPAL_REPAYMENT) ->
                        issue("LENDING_ROLE_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, tf.id, "出借划转角色无效", tf.lendingRole ?: "null", "选择借出本金或收回本金。")
                }
            }
        }

        val activeLoanTransactions = transactions.filter { it.deletedAt == null && it.loanPlanId != null }
        loanPlans.forEach { plan ->
            val related = activeLoanTransactions.filter { it.loanPlanId == plan.id }
            when (plan.originType) {
                "OPENING_BALANCE" -> if (plan.disbursementTransactionId != null) issue("OPENING_HAS_DISBURSEMENT", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id, "期初贷款不应再绑定放款流水", plan.disbursementTransactionId, "清除重复放款来源，历史流水只能作为参考关联。")
                "PENDING_DISBURSEMENT" -> if (plan.status != "PENDING_DISBURSEMENT" || plan.disbursementTransactionId != null || related.isNotEmpty()) issue("PENDING_LOAN_HAS_EFFECTS", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id, "待放款计划已经产生实际账务影响", "status=${plan.status}", "关联真实到账后再激活，或迁移为期初存量贷款。")
                "DISBURSEMENT_EVENT" -> {
                    val disbursement = plan.disbursementTransactionId?.let(txById::get)
                    if (disbursement == null || disbursement.deletedAt != null || disbursement.type != TransactionType.LOAN_DISBURSEMENT.name || disbursement.loanPlanId != plan.id) {
                        issue("DISBURSEMENT_EVIDENCE_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id, "已放款计划缺少有效到账流水", plan.disbursementTransactionId ?: "null", "恢复/补关联真实到账，或迁移为期初存量贷款。")
                    }
                }
                else -> issue("LOAN_ORIGIN_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id, "贷款来源类型无效", plan.originType, "改为期初、待放款或真实到账来源。")
            }
            if (plan.originType != "PENDING_DISBURSEMENT") {
                val baseline = plan.ledgerBaselinePrincipalCents
                val paidPrincipal = related
                    .filter {
                        it.occurredAt > plan.ledgerBaselineAt &&
                            (it.type == TransactionType.LOAN_PAYMENT.name || it.type == TransactionType.LOAN_PREPAYMENT.name)
                    }
                    .sumOf { it.principalCents }
                if (paidPrincipal > baseline) {
                    issue(
                        "LOAN_PRINCIPAL_OVERPAID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id,
                        "贷款还款本金超过锚点本金", "锚点 $baseline，已付 $paidPrincipal",
                        "核对还款流水本金拆分；不得用 0 元剩余掩盖超额还款。"
                    )
                }
                val expectedRemaining = baseline - paidPrincipal
                if (baseline < 0L || plan.ledgerBaselineAt <= 0L) {
                    issue("LOAN_BASELINE_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id, "贷款缺少可重放本金锚点", "baseline=$baseline, at=${plan.ledgerBaselineAt}", "重新核对当前剩余本金并建立证据锚点。")
                } else if (plan.remainingPrincipalCents != expectedRemaining) {
                    issue("LOAN_PRINCIPAL_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id, "剩余本金不可由锚点与还款流水重算", "记录 ${plan.remainingPrincipalCents}，证据链重算 $expectedRemaining", "核对本金拆分、锚点之后是否漏关联还款。")
                }
            }
            if (plan.remainingPrincipalCents < 0L || plan.remainingPrincipalCents > plan.principalCents) issue("LOAN_RANGE_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LOAN_PLAN, plan.id, "贷款本金范围无效", "${plan.remainingPrincipalCents}/${plan.principalCents}", "重新核对期初本金和当前剩余本金。")
        }

        lendingPlans.forEach { plan ->
            val receivable = accountById[plan.receivableAccountId]
            if (receivable?.type != AccountType.ASSET.name) {
                issue("LENDING_RECEIVABLE_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "出借计划缺少应收资产账户", plan.receivableAccountId, "恢复或选择该出借计划专属的应收资产账户。")
            }
            val relatedTransfers = transfers.filter { it.deletedAt == null && it.lendingPlanId == plan.id }
            val relatedInterest = transactions.filter { it.deletedAt == null && it.lendingPlanId == plan.id }
            when (plan.originType) {
                LendingOriginType.OPENING_BALANCE -> if (plan.disbursementTransferId != null || relatedTransfers.any { it.lendingRole == LendingTransferRole.DISBURSEMENT }) {
                    issue("OPENING_LENDING_HAS_DISBURSEMENT", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "期初应收不应绑定借出划转", plan.disbursementTransferId ?: "active transfer", "移除重复借出来源，保留期初应收。")
                }
                LendingOriginType.PENDING_DISBURSEMENT -> if (plan.status != LendingPlanStatus.PENDING_DISBURSEMENT || plan.disbursementTransferId != null || relatedTransfers.isNotEmpty() || relatedInterest.isNotEmpty()) {
                    issue("PENDING_LENDING_HAS_EFFECTS", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "待借出计划已经产生账务影响", "status=${plan.status}", "关联真实借出划转后再激活，或迁移为期初应收。")
                }
                LendingOriginType.DISBURSEMENT_TRANSFER -> {
                    val disbursement = plan.disbursementTransferId?.let(transferById::get)
                    if (disbursement == null || disbursement.deletedAt != null || disbursement.lendingPlanId != plan.id || disbursement.lendingRole != LendingTransferRole.DISBURSEMENT) {
                        issue("LENDING_DISBURSEMENT_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "新出借缺少有效借出划转", plan.disbursementTransferId ?: "null", "恢复或补关联真实借出划转；不要伪造收入/支出流水。")
                    }
                }
                else -> issue("LENDING_ORIGIN_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "出借来源类型无效", plan.originType, "改为期初应收、待借出或真实借出划转。")
            }
            if (plan.originType != LendingOriginType.PENDING_DISBURSEMENT) {
                val repaidPrincipal = relatedTransfers
                    .filter { it.lendingRole == LendingTransferRole.PRINCIPAL_REPAYMENT && it.occurredAt > plan.ledgerBaselineAt }
                    .sumOf { it.amountCents }
                val receivedInterest = relatedInterest
                    .filter { it.type == TransactionType.INCOME.name && it.occurredAt > plan.ledgerBaselineAt }
                    .sumOf { it.amountCents }
                if (repaidPrincipal > plan.ledgerBaselinePrincipalCents) {
                    issue(
                        "LENDING_PRINCIPAL_OVERPAID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id,
                        "出借收回本金超过锚点本金", "锚点 ${plan.ledgerBaselinePrincipalCents}，已收回 $repaidPrincipal",
                        "核对本金收回划转；不得用 0 元剩余掩盖超额收回。"
                    )
                }
                val expectedRemaining = plan.ledgerBaselinePrincipalCents - repaidPrincipal
                val expectedReceivedInterest = plan.ledgerBaselineInterestCents + receivedInterest
                if (plan.remainingPrincipalCents != expectedRemaining) {
                    issue("LENDING_PRINCIPAL_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "剩余应收不可由有效本金划转重算", "记录 ${plan.remainingPrincipalCents}，证据链重算 $expectedRemaining", "核对本金收回划转和最近权威锚点。")
                }
                if (plan.receivedInterestCents != expectedReceivedInterest) {
                    issue("LENDING_INTEREST_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "已收利息不可由收入流水重算", "记录 ${plan.receivedInterestCents}，证据链重算 $expectedReceivedInterest", "核对利息收入关联；本金不能混入收入。")
                }
            }
            if (plan.remainingPrincipalCents !in 0L..plan.principalCents) {
                issue("LENDING_RANGE_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.LENDING_PLAN, plan.id, "应收本金范围无效", "${plan.remainingPrincipalCents}/${plan.principalCents}", "重新核对期初本金和收回本金。")
            }
        }

        val cardAllocationsByPlan = cardAllocations.groupBy { it.planId }
        val cardSchedulesByPlan = cardSchedules.groupBy { it.planId }
        val confirmedCardMatchStatuses = setOf("AUTO_MATCHED", "USER_CONFIRMED")
        val validCardMatchStatuses = confirmedCardMatchStatuses + setOf("PENDING", "REJECTED", "REVERSED")

        cardAllocations.forEach { allocation ->
            val plan = cardById[allocation.planId]
            val transaction = activeTxById[allocation.transactionId]
            if (
                plan == null || transaction == null || transaction.type != TransactionType.EXPENSE.name ||
                transaction.accountId != plan.cardAccountId || allocation.allocatedPrincipalCents <= 0L
            ) {
                issue("CARD_ALLOCATION_ORPHAN", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, allocation.planId, "信用分期原消费证据断开", allocation.transactionId, "恢复同一信用卡的原消费，或通过分期专项流程修复；禁止普通删除原消费。")
            }
        }
        cardAllocations.asSequence()
            .filter { cardById[it.planId]?.status == "ACTIVE" }
            .groupBy { it.transactionId }
            .forEach { (transactionId, allocations) ->
                val transaction = activeTxById[transactionId] ?: return@forEach
                val refunded = transactions.asSequence()
                    .filter { it.deletedAt == null && it.type == TransactionType.REFUND.name && it.refundOfId == transactionId }
                    .sumOf { it.amountCents }
                val available = (transaction.amountCents - refunded).coerceAtLeast(0L)
                val allocated = allocations.sumOf { it.allocatedPrincipalCents }
                if (allocated > available) {
                    issue(
                        "CARD_ALLOCATION_OVERFLOW", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSACTION, transactionId,
                        "有效信用分期本金超过原消费可用本金", "分期 $allocated > 可用 $available。",
                        "核对重复分期、退款和原消费分配；不要直接改信用卡余额。"
                    )
                }
            }

        cardSchedules.forEach { schedule ->
            val plan = cardById[schedule.planId]
            val paidPrincipal = cardMatches.asSequence()
                .filter { it.scheduleId == schedule.id && it.status in confirmedCardMatchStatuses }
                .sumOf { it.principalCents }
            val expectedStatus = when {
                paidPrincipal == schedule.principalDueCents -> "PAID"
                plan != null && schedule.revision == plan.scheduleRevision && plan.status !in setOf("CANCELLED", "LEGACY_UNLINKED") -> "UPCOMING"
                else -> "CANCELLED"
            }
            if (
                plan == null || schedule.principalDueCents <= 0L ||
                schedule.principalPaidCents !in 0L..schedule.principalDueCents ||
                schedule.expectedInterestCents < 0L || schedule.expectedFeeCents < 0L || schedule.expectedUnclassifiedChargeCents < 0L
            ) {
                issue("CARD_SCHEDULE_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, schedule.planId, "信用分期期次字段无效", schedule.id, "从有效分期计划和还款匹配重建期次。")
            }
            if (schedule.principalPaidCents != paidPrincipal || schedule.status != expectedStatus) {
                issue(
                    "CARD_SCHEDULE_PAYMENT_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, schedule.planId,
                    "信用分期期次不可由有效还款匹配重算", "${schedule.id}: 已还 ${schedule.principalPaidCents}/$paidPrincipal，状态 ${schedule.status}/$expectedStatus。",
                    "从有效 AUTO_MATCHED/USER_CONFIRMED 匹配重算当期本金和状态。"
                )
            }
        }

        cardPlans.forEach { plan ->
            val cardAccount = accountById[plan.cardAccountId]
            if (cardAccount?.type != AccountType.CREDIT.name) {
                issue("CARD_ACCOUNT_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, plan.id, "信用分期没有有效信用账户", plan.cardAccountId, "恢复或改关联到真实信用账户。")
            }
            if (plan.originalPrincipalCents <= 0L || plan.remainingPrincipalCents !in 0L..plan.originalPrincipalCents) {
                issue("CARD_PRINCIPAL_RANGE_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, plan.id, "信用分期本金范围无效", "${plan.remainingPrincipalCents}/${plan.originalPrincipalCents}", "核对原始本金和真实还款匹配。")
            }
            if (plan.status != "LEGACY_UNLINKED") {
                val allocatedPrincipal = cardAllocationsByPlan[plan.id].orEmpty().sumOf { it.allocatedPrincipalCents }
                if (allocatedPrincipal != plan.originalPrincipalCents) {
                    issue(
                        "CARD_PLAN_ALLOCATION_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, plan.id,
                        "信用分期原始本金不可由原消费分配重算", "记录 ${plan.originalPrincipalCents}，分配合计 $allocatedPrincipal。",
                        "核对原消费分配；不能补造第二笔负债。"
                    )
                }
                val confirmedPrincipal = cardMatches.asSequence()
                    .filter { it.planId == plan.id && it.status in confirmedCardMatchStatuses }
                    .sumOf { it.principalCents }
                val expectedRemaining = (plan.originalPrincipalCents - confirmedPrincipal).coerceAtLeast(0L)
                if (plan.remainingPrincipalCents != expectedRemaining) {
                    issue(
                        "CARD_PLAN_PRINCIPAL_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, plan.id,
                        "信用分期剩余本金不可由有效还款匹配重算", "记录 ${plan.remainingPrincipalCents}，证据链重算 $expectedRemaining。",
                        "核对重复匹配和被删除的信用卡还款划转。"
                    )
                }
                val currentSchedules = cardSchedulesByPlan[plan.id].orEmpty().filter { it.revision == plan.scheduleRevision }
                if (currentSchedules.isEmpty()) {
                    issue("CARD_CURRENT_SCHEDULE_MISSING", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, plan.id, "信用分期缺少当前版本期次", "revision=${plan.scheduleRevision}", "从最近一次创建/调整条款审计重建当前期次。")
                } else {
                    val scheduleRemaining = currentSchedules.sumOf { (it.principalDueCents - it.principalPaidCents).coerceAtLeast(0L) }
                    if (scheduleRemaining != plan.remainingPrincipalCents) {
                        issue(
                            "CARD_PLAN_SCHEDULE_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, plan.id,
                            "信用分期计划与当前期次本金不一致", "计划 ${plan.remainingPrincipalCents}，期次重算 $scheduleRemaining。",
                            "从当前版本期次和有效匹配重算计划，旧版本期次不得参与。"
                        )
                    }
                    if (plan.status in setOf("ACTIVE", "COMPLETED")) {
                        val upcoming = currentSchedules.filter { it.status == "UPCOMING" }
                        val expectedNextDue = upcoming.minByOrNull { it.dueDateEpochDay }?.dueDateEpochDay
                        if (plan.periodsRemaining != upcoming.size || plan.nextDueDateEpochDay != expectedNextDue) {
                            issue(
                                "CARD_PLAN_PERIOD_MISMATCH", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, plan.id,
                                "信用分期剩余期数或下次还款日不一致", "记录 ${plan.periodsRemaining}/${plan.nextDueDateEpochDay}，期次重算 ${upcoming.size}/$expectedNextDue。",
                                "从当前版本未还期次重算计划摘要。"
                            )
                        }
                    }
                }
            }
        }

        cardMatches.forEach { match ->
            val transfer = transferById[match.transferId]
            val schedule = scheduleById[match.scheduleId]
            val plan = cardById[match.planId]
            val validReference = plan != null && schedule?.planId == match.planId
            val activeMatch = match.status in setOf("PENDING", "AUTO_MATCHED", "USER_CONFIRMED")
            val activeTransfer = transfer?.takeIf { it.deletedAt == null }
            val validDirection = activeTransfer?.let { tf ->
                accountById[tf.fromAccountId]?.type == AccountType.ASSET.name && tf.toAccountId == plan?.cardAccountId
            } == true
            if (
                !validReference || match.status !in validCardMatchStatuses ||
                (activeMatch && (activeTransfer == null || !validDirection)) ||
                (transfer != null && match.paymentCents != transfer.amountCents) ||
                match.principalCents <= 0L || match.principalCents > match.paymentCents
            ) {
                issue("CARD_PAYMENT_MATCH_INVALID", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, match.transferId, "信用分期还款匹配断链", "schedule=${match.scheduleId}, plan=${match.planId}, status=${match.status}", "恢复同卡真实还款划转/期次，或反向撤销错误匹配。")
            }
            if (match.status in confirmedCardMatchStatuses && evidenceLinks.none {
                    it.subjectType == EvidenceSubjectType.CARD_INSTALLMENT &&
                        it.subjectId == match.planId &&
                        it.sourceType == EvidenceSourceType.LEDGER_EVENT &&
                        it.sourceId == match.transferId
                }
            ) {
                issue(
                    "CARD_PAYMENT_EVIDENCE_MISSING",
                    EvidenceAuditSeverity.BROKEN,
                    EvidenceSubjectType.TRANSFER,
                    match.transferId,
                    "信用分期还款未进入统一证据链",
                    "匹配已推进分期 ${match.planId}，但缺少该划转到分期计划的证据关系。",
                    "运行专项迁移补齐真实划转关联，不要新建第二笔还款。"
                )
            }
        }
        cardMatches.asSequence()
            .filter { it.status in confirmedCardMatchStatuses }
            .groupBy { it.transferId }
            .forEach { (transferId, matches) ->
                val payment = transferById[transferId]?.amountCents ?: return@forEach
                val principal = matches.sumOf { it.principalCents }
                if (principal > payment) {
                    issue("CARD_TRANSFER_MATCH_OVERFLOW", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.TRANSFER, transferId, "信用卡还款匹配本金超过真实付款", "$principal > $payment", "撤销重复期次匹配并重新选择本金归属。")
                }
            }
        cardMatches.asSequence()
            .filter { it.status in confirmedCardMatchStatuses }
            .groupBy { it.scheduleId }
            .forEach { (scheduleId, matches) ->
                val due = scheduleById[scheduleId]?.principalDueCents ?: return@forEach
                val principal = matches.sumOf { it.principalCents }
                if (principal > due) {
                    issue("CARD_SCHEDULE_MATCH_OVERFLOW", EvidenceAuditSeverity.BROKEN, EvidenceSubjectType.CARD_INSTALLMENT, matches.first().planId, "分期期次匹配本金超过当期本金", "$principal > $due", "撤销重复还款匹配并重算期次。")
                }
            }

        accounts.forEach { account ->
            val checkpoint = checkpoints.filter { it.accountId == account.id }.maxByOrNull { it.checkedAt }
            if (checkpoint == null) {
                issue("CHECKPOINT_MISSING", EvidenceAuditSeverity.WARNING, "ACCOUNT", account.id, "账户缺少余额检查点", "无法独立重放账户余额。", "手动对账一次，建立权威检查点。")
            } else {
                val accountType = runCatching { AccountType.valueOf(account.type) }.getOrNull()
                if (accountType == null) {
                    issue("ACCOUNT_TYPE_INVALID", EvidenceAuditSeverity.BROKEN, "ACCOUNT", account.id, "账户类型无效", account.type, "修复账户类型。")
                } else {
                    var expected = checkpoint.balanceCents
                    transactions.asSequence()
                        .filter { it.deletedAt == null && it.accountId == account.id && it.occurredAt > checkpoint.checkedAt }
                        .forEach { tx ->
                            runCatching { TransactionType.valueOf(tx.type) }.getOrNull()?.let { type ->
                                expected += BalanceMath.transactionDelta(accountType, type, tx.amountCents)
                            }
                        }
                    transfers.asSequence()
                        .filter { it.deletedAt == null && it.occurredAt > checkpoint.checkedAt && (it.fromAccountId == account.id || it.toAccountId == account.id) }
                        .forEach { tf ->
                            expected += if (tf.toAccountId == account.id) BalanceMath.transferInDelta(accountType, tf.amountCents)
                            else BalanceMath.transferOutDelta(accountType, tf.amountCents)
                        }
                    if (expected != account.balanceCents) {
                        issue("ACCOUNT_BALANCE_MISMATCH", EvidenceAuditSeverity.BROKEN, "ACCOUNT", account.id, "当前余额不可由检查点和有效事件重放", "记录 ${account.balanceCents}，证据链重算 $expected", "进入对账查看差额；确认来源后建立新检查点，不要静默覆盖。")
                    }
                }
            }
        }

        val sortedIssues = issues.distinctBy { listOf(it.code, it.subjectType, it.subjectId, it.detail) }
            .map { it.copy(subjectLabel = subjectLabel(it.subjectType, it.subjectId)) }
            .sortedWith(compareBy<EvidenceAuditIssue>({ it.severity != EvidenceAuditSeverity.BROKEN }, { it.code }, { it.subjectId }))
        EvidenceAuditReport(
            generatedAt = now,
            status = when {
                sortedIssues.any { it.severity == EvidenceAuditSeverity.BROKEN } -> EvidenceAuditStatus.BROKEN
                sortedIssues.isNotEmpty() -> EvidenceAuditStatus.WARNING
                else -> EvidenceAuditStatus.COMPLETE
            },
            subjectCount = transactions.size + transfers.size + loanPlans.size + lendingPlans.size + cardPlans.size + recurringRules.size,
            sourceLinkCount = evidenceLinks.size,
            lifecycleEventCount = lifecycle.size,
            issues = sortedIssues
        )
    }
}
