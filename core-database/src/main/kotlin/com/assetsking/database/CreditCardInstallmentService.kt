package com.assetsking.database

import androidx.room.withTransaction
import com.assetsking.ledger.CardInstallmentAllocationRequest
import com.assetsking.ledger.CardInstallmentPaymentCandidate
import com.assetsking.ledger.CardInstallmentPaymentMatchDecision
import com.assetsking.ledger.CardInstallmentPaymentMatchKind
import com.assetsking.ledger.CardInstallmentSource
import com.assetsking.ledger.CardInstallmentScheduleLine
import com.assetsking.ledger.buildEqualPrincipalCardInstallmentSchedule
import com.assetsking.ledger.cardStatementCycle
import com.assetsking.ledger.matchCardInstallmentPayment
import com.assetsking.ledger.validateCardInstallmentAllocation
import com.assetsking.model.AccountType
import com.assetsking.model.RecordStatus
import com.assetsking.model.TransactionType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class CreditCardInstallmentDraft(
    val cardAccountId: String,
    val label: String,
    val allocations: List<CardInstallmentAllocationRequest>,
    val installmentCount: Int,
    val firstDueDateEpochDay: Long,
    val installmentType: String = CreditCardInstallmentService.TYPE_POST_PURCHASE,
    val expectedInterestCentsPerPeriod: Long = 0,
    val expectedFeeCentsPerPeriod: Long = 0,
    val expectedPaymentCentsPerPeriod: Long? = null,
    val customSchedule: List<CreditCardInstallmentScheduleDraft> = emptyList()
)

data class CreditCardInstallmentTerms(
    val installmentCount: Int,
    val firstDueDateEpochDay: Long,
    val expectedInterestCentsPerPeriod: Long = 0,
    val expectedFeeCentsPerPeriod: Long = 0,
    val expectedPaymentCentsPerPeriod: Long? = null,
    val customSchedule: List<CreditCardInstallmentScheduleDraft> = emptyList()
)

data class CreditCardInstallmentScheduleDraft(
    val dueDateEpochDay: Long,
    val expectedPaymentCents: Long
)

/**
 * Deep module for post-purchase card installments. All creation, allocation and audit invariants
 * live here so UI and callers cannot create a second expense or liability by construction.
 */
class CreditCardInstallmentService(
    private val database: AssetsKingDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val evidenceRecorder = LedgerEvidenceRecorder(database)

    suspend fun create(draft: CreditCardInstallmentDraft): String = database.withTransaction {
        require(draft.installmentType == TYPE_POST_PURCHASE || draft.installmentType == TYPE_STATEMENT) {
            "不支持的信用分期类型"
        }
        require(draft.installmentCount in 1..360) { "分期期数必须在 1 到 360 之间" }
        require(draft.expectedInterestCentsPerPeriod >= 0 && draft.expectedFeeCentsPerPeriod >= 0) {
            "预计利息和手续费不能为负数"
        }
        require(draft.expectedPaymentCentsPerPeriod == null || draft.expectedPaymentCentsPerPeriod > 0L) {
            "每期总还款必须大于零"
        }
        val card = requireNotNull(database.accountDao().find(draft.cardAccountId)) { "信用卡账户不存在" }
        require(card.type == AccountType.CREDIT.name && !card.archived) { "只能选择有效的信用卡账户" }

        val createdAt = now()
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(createdAt).atZone(zoneId).toLocalDate()
        val statementCycle = cardStatementCycle(card.statementDay, today, zoneId)
        val statementStart = card.statementDay?.let { statementDay ->
            val previousMonth = YearMonth.from(statementCycle.currentStatementDate).minusMonths(1)
            previousMonth.atDay(statementDay.coerceIn(1, previousMonth.lengthOfMonth())).plusDays(1)
        }
        val plans = database.creditCardInstallmentDao().all()
        val allocations = database.creditCardInstallmentAllocationDao().all()
        val activePlans = plans.filter { it.status == STATUS_ACTIVE }
        val activePlanById = activePlans.associateBy { it.id }
        val transactions = database.transactionDao().all()
        val transactionsById = transactions.associateBy { it.id }
        val requestedIds = draft.allocations.mapTo(linkedSetOf()) { it.transactionId }
        val refundedByOriginal = transactions
            .filter { it.status == RecordStatus.CONFIRMED.name && it.type == TransactionType.REFUND.name && it.refundOfId != null }
            .groupingBy { requireNotNull(it.refundOfId) }
            .fold(0L) { sum, refund -> sum + refund.amountCents }
        val allocatedByTransaction = allocations
            .filter { it.planId in activePlanById }
            .groupingBy { it.transactionId }
            .fold(0L) { sum, allocation -> sum + allocation.allocatedPrincipalCents }
        val sources = requestedIds.map { transactionId ->
            val transaction = requireNotNull(transactionsById[transactionId]) { "原信用卡消费不存在" }
            require(transaction.status == RecordStatus.CONFIRMED.name) { "只能选择已确认的信用卡消费" }
            require(transaction.type == TransactionType.EXPENSE.name) { "分期来源必须是信用卡消费" }
            val purchaseDate = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate()
            if (draft.installmentType == TYPE_STATEMENT) {
                require(statementStart != null && purchaseDate in statementStart..statementCycle.currentStatementDate) {
                    "账单分期只能选择本期已出账消费"
                }
            } else if (card.statementDay != null) {
                require(purchaseDate in statementCycle.currentStatementDate.plusDays(1)..today) {
                    "消费分期只能选择当前未出账消费"
                }
            }
            CardInstallmentSource(
                transactionId = transaction.id,
                cardAccountId = transaction.accountId,
                postedExpenseCents = transaction.amountCents,
                refundedCents = refundedByOriginal[transaction.id].orZero().coerceAtMost(transaction.amountCents),
                activeAllocatedCents = allocatedByTransaction[transaction.id].orZero()
            )
        }
        val activeAllocatedOnCard = activePlans
            .filter { it.cardAccountId == card.id }
            .sumOf { it.remainingPrincipalCents }
        val decision = validateCardInstallmentAllocation(
            cardAccountId = card.id,
            cardOutstandingCents = card.balanceCents,
            activeAllocatedOnCardCents = activeAllocatedOnCard,
            sources = sources,
            requests = draft.allocations
        )
        require(decision.accepted) { "当前消费或信用卡可分本金不足：${decision.error}" }
        if (draft.installmentType == TYPE_POST_PURCHASE) {
            require(draft.allocations.map { it.transactionId }.distinct().size == 1) { "未出账消费分期只能选择一笔消费" }
            val activeUnbilledPrincipal = activePlans
                .filter { it.cardAccountId == card.id && it.installmentType == TYPE_POST_PURCHASE }
                .sumOf { it.remainingPrincipalCents }
            val unbilledOutstanding = (card.balanceCents - card.statementOriginalDueCents).coerceAtLeast(0L)
            val availableUnbilledPrincipal = (unbilledOutstanding - activeUnbilledPrincipal).coerceAtLeast(0L)
            require(decision.principalCents <= availableUnbilledPrincipal) {
                "未出账分期本金不能超过账户尚未出账、尚未分期的 ${moneyText(availableUnbilledPrincipal)}"
            }
        }
        if (draft.installmentType == TYPE_STATEMENT) {
            require(card.statementDay != null && card.dueDay != null) { "请先设置该信用账户的出账日和还款日" }
            require(card.statementOriginalDueCents > 0L) { "请先在信用账户中录入本期应还账单" }
            val transferredInCycle = database.transferDao().all()
                .filter {
                    it.toAccountId == card.id &&
                        database.accountDao().find(it.fromAccountId)?.let { source ->
                            source.type == AccountType.ASSET.name && !source.archived
                        } == true &&
                        it.occurredAt in statementCycle.repaymentStartMillis until statementCycle.repaymentEndMillis
                }
                .sumOf { it.amountCents }
            val cycleStartEpochDay = statementCycle.currentStatementEpochDay
            val alreadyConverted = plans
                .filter {
                    it.cardAccountId == card.id &&
                        it.installmentType == TYPE_STATEMENT &&
                        it.statementCycleStartEpochDay == cycleStartEpochDay &&
                        it.status != STATUS_CANCELLED
                }
                .sumOf { it.originalPrincipalCents }
            val statementAvailable = (
                card.statementOriginalDueCents - transferredInCycle - alreadyConverted
                ).coerceAtLeast(0L)
            require(decision.principalCents <= statementAvailable) {
                "账单分期本金不能超过本期尚未还款、尚未分期的 ${moneyText(statementAvailable)}"
            }
        }

        val schedule = buildSchedule(
            principalCents = decision.principalCents,
            installmentCount = draft.installmentCount,
            firstDueDateEpochDay = draft.firstDueDateEpochDay,
            expectedInterestCentsPerPeriod = draft.expectedInterestCentsPerPeriod,
            expectedFeeCentsPerPeriod = draft.expectedFeeCentsPerPeriod,
            expectedPaymentCentsPerPeriod = draft.expectedPaymentCentsPerPeriod,
            customSchedule = draft.customSchedule
        )
        val startDateEpochDay = java.time.Instant.ofEpochMilli(createdAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
        val planId = UUID.randomUUID().toString()
        database.creditCardInstallmentDao().upsert(
            CreditCardInstallmentEntity(
                id = planId,
                cardAccountId = card.id,
                label = draft.label.trim().ifBlank { "消费分期" },
                originalPrincipalCents = decision.principalCents,
                remainingPrincipalCents = decision.principalCents,
                monthlyPaymentCents = schedule.first().expectedTotalCents,
                feeCentsPerPeriod = draft.expectedFeeCentsPerPeriod,
                periodsRemaining = draft.installmentCount,
                startDateEpochDay = startDateEpochDay,
                installmentType = draft.installmentType,
                installmentCount = draft.installmentCount,
                nextDueDateEpochDay = schedule.first().dueDateEpochDay,
                statementCycleStartEpochDay = if (draft.installmentType == TYPE_STATEMENT) {
                    statementCycle.currentStatementEpochDay
                } else null,
                status = STATUS_ACTIVE,
                scheduleRevision = 1,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
        database.creditCardInstallmentAllocationDao().insertAll(
            draft.allocations
                .groupingBy { it.transactionId }
                .fold(0L) { sum, request -> sum + request.principalCents }
                .map { (transactionId, principalCents) ->
                    CreditCardInstallmentAllocationEntity(planId, transactionId, principalCents, createdAt)
                }
        )
        database.creditCardInstallmentScheduleDao().insertAll(
            schedule.map { line ->
                CreditCardInstallmentScheduleEntity(
                    id = UUID.randomUUID().toString(),
                    planId = planId,
                    revision = 1,
                    number = line.number,
                    dueDateEpochDay = line.dueDateEpochDay,
                    principalDueCents = line.principalDueCents,
                    expectedInterestCents = line.expectedInterestCents,
                    expectedFeeCents = line.expectedFeeCents,
                    expectedUnclassifiedChargeCents = line.expectedUnclassifiedChargeCents
                )
            }
        )
        appendAudit(
            planId = planId,
            eventType = "CREATED",
            occurredAt = createdAt,
            payload = JSONObject()
                .put("cardAccountId", card.id)
                .put("principalCents", decision.principalCents)
                .put("installmentCount", draft.installmentCount)
                .put("firstDueDateEpochDay", draft.firstDueDateEpochDay)
                .put("installmentType", draft.installmentType)
                .put("statementCycleStartEpochDay", if (draft.installmentType == TYPE_STATEMENT) {
                    statementCycle.currentStatementEpochDay
                } else JSONObject.NULL)
                .put("expectedInterestCentsPerPeriod", draft.expectedInterestCentsPerPeriod)
                .put("expectedFeeCentsPerPeriod", draft.expectedFeeCentsPerPeriod)
                .put("expectedPaymentCentsPerPeriod", draft.expectedPaymentCentsPerPeriod)
                .put("customSchedule", JSONArray().apply {
                    draft.customSchedule.forEach { line ->
                        put(JSONObject().put("dueDateEpochDay", line.dueDateEpochDay).put("expectedPaymentCents", line.expectedPaymentCents))
                    }
                })
                .put("allocations", JSONArray().apply {
                    draft.allocations.forEach { request ->
                        put(JSONObject().put("transactionId", request.transactionId).put("principalCents", request.principalCents))
                    }
                })
        )
        planId
    }

    suspend fun adjustTerms(planId: String, terms: CreditCardInstallmentTerms) = database.withTransaction {
        require(terms.installmentCount in 1..360) { "分期期数必须在 1 到 360 之间" }
        require(terms.expectedInterestCentsPerPeriod >= 0 && terms.expectedFeeCentsPerPeriod >= 0)
        require(terms.expectedPaymentCentsPerPeriod == null || terms.expectedPaymentCentsPerPeriod > 0L)
        val plan = requireNotNull(database.creditCardInstallmentDao().findById(planId)) { "分期计划不存在" }
        require(plan.status == STATUS_ACTIVE) { "只有进行中的分期可以调整" }
        require(plan.remainingPrincipalCents > 0) { "分期本金已结清" }
        require(database.creditCardInstallmentPaymentMatchDao().countPendingByPlan(planId) == 0) {
            "该分期有待确认还款，请先确认还款归属再调整条款"
        }

        val nextRevision = plan.scheduleRevision + 1
        val schedule = buildSchedule(
            principalCents = plan.remainingPrincipalCents,
            installmentCount = terms.installmentCount,
            firstDueDateEpochDay = terms.firstDueDateEpochDay,
            expectedInterestCentsPerPeriod = terms.expectedInterestCentsPerPeriod,
            expectedFeeCentsPerPeriod = terms.expectedFeeCentsPerPeriod,
            expectedPaymentCentsPerPeriod = terms.expectedPaymentCentsPerPeriod,
            customSchedule = terms.customSchedule
        )
        val adjustedAt = now()
        database.creditCardInstallmentScheduleDao().cancelUpcoming(planId)
        database.creditCardInstallmentScheduleDao().insertAll(
            schedule.map { line ->
                CreditCardInstallmentScheduleEntity(
                    id = UUID.randomUUID().toString(),
                    planId = planId,
                    revision = nextRevision,
                    number = line.number,
                    dueDateEpochDay = line.dueDateEpochDay,
                    principalDueCents = line.principalDueCents,
                    expectedInterestCents = line.expectedInterestCents,
                    expectedFeeCents = line.expectedFeeCents,
                    expectedUnclassifiedChargeCents = line.expectedUnclassifiedChargeCents
                )
            }
        )
        database.creditCardInstallmentDao().updateTerms(
            id = planId,
            monthlyPaymentCents = schedule.first().expectedTotalCents,
            feeCentsPerPeriod = terms.expectedFeeCentsPerPeriod,
            periodsRemaining = terms.installmentCount,
            nextDueDateEpochDay = schedule.first().dueDateEpochDay,
            scheduleRevision = nextRevision,
            updatedAt = adjustedAt
        )
        appendAudit(
            planId = planId,
            eventType = "TERMS_ADJUSTED",
            occurredAt = adjustedAt,
            payload = JSONObject()
                .put("previousRevision", plan.scheduleRevision)
                .put("newRevision", nextRevision)
                .put("installmentCount", terms.installmentCount)
                .put("firstDueDateEpochDay", terms.firstDueDateEpochDay)
                .put("expectedInterestCentsPerPeriod", terms.expectedInterestCentsPerPeriod)
                .put("expectedFeeCentsPerPeriod", terms.expectedFeeCentsPerPeriod)
                .put("expectedPaymentCentsPerPeriod", terms.expectedPaymentCentsPerPeriod)
                .put("customSchedule", JSONArray().apply {
                    terms.customSchedule.forEach { line ->
                        put(JSONObject().put("dueDateEpochDay", line.dueDateEpochDay).put("expectedPaymentCents", line.expectedPaymentCents))
                    }
                })
        )
    }

    private fun buildSchedule(
        principalCents: Long,
        installmentCount: Int,
        firstDueDateEpochDay: Long,
        expectedInterestCentsPerPeriod: Long,
        expectedFeeCentsPerPeriod: Long,
        expectedPaymentCentsPerPeriod: Long?,
        customSchedule: List<CreditCardInstallmentScheduleDraft>
    ): List<CardInstallmentScheduleLine> {
        val base = buildEqualPrincipalCardInstallmentSchedule(
            principalCents = principalCents,
            installmentCount = installmentCount,
            firstDueDateEpochDay = firstDueDateEpochDay,
            expectedInterestCentsPerPeriod = if (customSchedule.isEmpty()) expectedInterestCentsPerPeriod else 0L,
            expectedFeeCentsPerPeriod = if (customSchedule.isEmpty()) expectedFeeCentsPerPeriod else 0L,
            expectedPaymentCentsPerPeriod = if (customSchedule.isEmpty()) expectedPaymentCentsPerPeriod else null
        )
        if (customSchedule.isEmpty()) return base
        require(customSchedule.size == installmentCount) { "逐期自填数量必须等于分期期数" }
        require(customSchedule.zipWithNext().all { (left, right) -> right.dueDateEpochDay > left.dueDateEpochDay }) {
            "逐期还款日期必须严格递增"
        }
        return base.zip(customSchedule).map { (line, custom) ->
            require(custom.expectedPaymentCents >= line.principalDueCents) { "每期待还不能低于当期本金" }
            line.copy(
                dueDateEpochDay = custom.dueDateEpochDay,
                expectedInterestCents = 0L,
                expectedFeeCents = 0L,
                expectedUnclassifiedChargeCents = custom.expectedPaymentCents - line.principalDueCents
            )
        }
    }

    suspend fun autoMatchTransfer(transferId: String): CardInstallmentPaymentMatchDecision = database.withTransaction {
        val matchDao = database.creditCardInstallmentPaymentMatchDao()
        val existing = matchDao.findByTransfer(transferId)
        if (existing.isNotEmpty()) return@withTransaction existing.toDecision()

        val transfer = database.transferDao().findById(transferId)
            ?: return@withTransaction noPaymentMatch()
        val fromAccount = database.accountDao().find(transfer.fromAccountId)
        val cardAccount = database.accountDao().find(transfer.toAccountId)
        if (fromAccount?.type != AccountType.ASSET.name ||
            cardAccount?.type != AccountType.CREDIT.name ||
            cardAccount.archived
        ) {
            return@withTransaction noPaymentMatch()
        }

        val activePlans = database.creditCardInstallmentDao().all()
            .filter { it.cardAccountId == cardAccount.id && it.status == STATUS_ACTIVE }
        val candidates = activePlans.flatMap { plan ->
            database.creditCardInstallmentScheduleDao().findByPlan(plan.id)
                .filter { it.revision == plan.scheduleRevision && it.status == SCHEDULE_UPCOMING }
                .mapNotNull { schedule ->
                    val principalRemaining = (schedule.principalDueCents - schedule.principalPaidCents).coerceAtLeast(0L)
                    if (principalRemaining == 0L) null else CardInstallmentPaymentCandidate(
                        scheduleId = schedule.id,
                        planId = plan.id,
                        cardAccountId = plan.cardAccountId,
                        dueDateEpochDay = schedule.dueDateEpochDay,
                        principalRemainingCents = principalRemaining,
                        expectedPaymentCents = principalRemaining +
                            schedule.expectedInterestCents +
                            schedule.expectedFeeCents +
                            schedule.expectedUnclassifiedChargeCents
                    )
                }
        }
        val decision = matchCardInstallmentPayment(
            cardAccountId = cardAccount.id,
            paymentCents = transfer.amountCents,
            paymentEpochDay = Instant.ofEpochMilli(transfer.occurredAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toEpochDay(),
            candidates = candidates
        )
        val matchedAt = now()
        val candidatesById = candidates.associateBy { it.scheduleId }
        when (decision.kind) {
            CardInstallmentPaymentMatchKind.MATCHED -> {
                val candidate = requireNotNull(candidatesById[decision.scheduleIds.single()])
                matchDao.insertAll(
                    listOf(candidate.toMatchEntity(transfer, STATUS_AUTO_MATCHED, matchedAt, matchedAt))
                )
                recomputeScheduleAndPlan(candidate.scheduleId, candidate.planId, matchedAt)
                appendPaymentAudit(
                    planId = candidate.planId,
                    transfer = transfer,
                    scheduleId = candidate.scheduleId,
                    principalCents = candidate.principalRemainingCents,
                    eventType = "PAYMENT_MATCHED",
                    occurredAt = matchedAt,
                    source = "AUTO"
                )
                recordPaymentEvidence(
                    planId = candidate.planId,
                    transfer = transfer,
                    scheduleId = candidate.scheduleId,
                    principalCents = candidate.principalRemainingCents,
                    occurredAt = matchedAt,
                    source = "AUTO"
                )
            }
            CardInstallmentPaymentMatchKind.AMBIGUOUS -> {
                matchDao.insertAll(
                    decision.scheduleIds.map { scheduleId ->
                        requireNotNull(candidatesById[scheduleId])
                            .toMatchEntity(transfer, STATUS_PENDING, matchedAt, null)
                    }
                )
            }
            CardInstallmentPaymentMatchKind.NO_MATCH -> Unit
        }
        decision
    }

    suspend fun confirmPaymentMatch(transferId: String, scheduleId: String, principalCents: Long) =
        database.withTransaction {
            require(principalCents > 0L) { "匹配本金必须大于零" }
            val transfer = requireNotNull(database.transferDao().findById(transferId)) { "信用卡还款不存在" }
            val schedule = requireNotNull(database.creditCardInstallmentScheduleDao().findById(scheduleId)) { "分期期次不存在" }
            val plan = requireNotNull(database.creditCardInstallmentDao().findById(schedule.planId)) { "分期计划不存在" }
            val fromAccount = requireNotNull(database.accountDao().find(transfer.fromAccountId))
            require(fromAccount.type == AccountType.ASSET.name && transfer.toAccountId == plan.cardAccountId) {
                "只能匹配资产账户到同一信用卡的真实还款"
            }
            require(plan.status == STATUS_ACTIVE && schedule.revision == plan.scheduleRevision) {
                "只能匹配进行中分期的当前期次"
            }

            val matchDao = database.creditCardInstallmentPaymentMatchDao()
            val transferMatches = matchDao.findByTransfer(transferId)
            val selected = transferMatches.firstOrNull { it.scheduleId == scheduleId }
            require(selected == null || selected.status == STATUS_PENDING) { "该还款匹配已经处理" }
            val confirmedOnTransfer = transferMatches
                .filter { it.status.isConfirmedPaymentMatch() }
                .sumOf { it.principalCents }
            require(confirmedOnTransfer + principalCents <= transfer.amountCents) { "匹配本金超过真实还款金额" }
            val confirmedOnSchedule = matchDao.findBySchedule(scheduleId)
                .filter { it.status.isConfirmedPaymentMatch() }
                .sumOf { it.principalCents }
            require(confirmedOnSchedule + principalCents <= schedule.principalDueCents) { "匹配本金超过当期剩余本金" }

            val resolvedAt = now()
            if (selected == null) {
                matchDao.insertAll(
                    listOf(
                        CreditCardInstallmentPaymentMatchEntity(
                            transferId = transferId,
                            scheduleId = scheduleId,
                            planId = plan.id,
                            paymentCents = transfer.amountCents,
                            principalCents = principalCents,
                            status = STATUS_USER_CONFIRMED,
                            source = "USER",
                            createdAt = resolvedAt,
                            resolvedAt = resolvedAt
                        )
                    )
                )
            } else {
                matchDao.resolve(
                    transferId = transferId,
                    scheduleId = scheduleId,
                    principalCents = principalCents,
                    status = STATUS_USER_CONFIRMED,
                    source = "USER",
                    resolvedAt = resolvedAt
                )
            }
            matchDao.rejectOtherPending(transferId, scheduleId, resolvedAt)
            recomputeScheduleAndPlan(scheduleId, plan.id, resolvedAt)
            appendPaymentAudit(
                planId = plan.id,
                transfer = transfer,
                scheduleId = scheduleId,
                principalCents = principalCents,
                eventType = "PAYMENT_MATCHED",
                occurredAt = resolvedAt,
                source = "USER"
            )
            recordPaymentEvidence(
                planId = plan.id,
                transfer = transfer,
                scheduleId = scheduleId,
                principalCents = principalCents,
                occurredAt = resolvedAt,
                source = "USER"
            )
        }

    suspend fun reverseTransferMatches(transferId: String) = database.withTransaction {
        val matchDao = database.creditCardInstallmentPaymentMatchDao()
        val matches = matchDao.findByTransfer(transferId)
        if (matches.isEmpty()) return@withTransaction
        val transfer = database.transferDao().findById(transferId) ?: return@withTransaction
        val confirmed = matches.filter { it.status.isConfirmedPaymentMatch() }
        val reversedAt = now()
        matchDao.reverseByTransfer(transferId, reversedAt)
        confirmed.forEach { match ->
            appendPaymentAudit(
                planId = match.planId,
                transfer = transfer,
                scheduleId = match.scheduleId,
                principalCents = match.principalCents,
                eventType = "PAYMENT_MATCH_REVERSED",
                occurredAt = reversedAt,
                source = "SYSTEM"
            )
            evidenceRecorder.lifecycle(
                subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
                subjectId = match.planId,
                action = EvidenceAction.EFFECT_REVERSED,
                occurredAt = reversedAt,
                payload = JSONObject()
                    .put("transferId", transferId)
                    .put("scheduleId", match.scheduleId)
                    .put("reason", "TRANSFER_TRASHED")
            )
        }
        confirmed.forEach { match -> recomputeSchedule(match.scheduleId) }
        confirmed.map { it.planId }.distinct().forEach { planId -> recomputePlan(planId, reversedAt) }
    }

    suspend fun cancel(planId: String) = database.withTransaction {
        val plan = requireNotNull(database.creditCardInstallmentDao().findById(planId)) { "分期计划不存在" }
        if (plan.status == STATUS_CANCELLED) return@withTransaction
        require(plan.status == STATUS_ACTIVE || plan.status == STATUS_LEGACY) { "当前分期状态不可取消" }
        require(database.creditCardInstallmentPaymentMatchDao().countPendingByPlan(planId) == 0) {
            "该分期有待确认还款，请先确认还款归属再取消"
        }
        val cancelledAt = now()
        database.creditCardInstallmentDao().updateStatus(planId, STATUS_CANCELLED, cancelledAt)
        database.creditCardInstallmentScheduleDao().cancelUpcoming(planId)
        appendAudit(
            planId = planId,
            eventType = "CANCELLED",
            occurredAt = cancelledAt,
            payload = JSONObject()
                .put("previousStatus", plan.status)
                .put("releasedPrincipalCents", if (plan.status == STATUS_ACTIVE) plan.remainingPrincipalCents else 0)
        )
    }

    private suspend fun appendAudit(
        planId: String,
        eventType: String,
        occurredAt: Long,
        payload: JSONObject,
        source: String = "USER"
    ) {
        database.creditCardInstallmentAuditDao().insert(
            CreditCardInstallmentAuditEventEntity(
                id = UUID.randomUUID().toString(),
                planId = planId,
                eventType = eventType,
                occurredAt = occurredAt,
                source = source,
                payloadJson = payload.toString()
            )
        )
    }

    private suspend fun recomputeScheduleAndPlan(scheduleId: String, planId: String, updatedAt: Long) {
        recomputeSchedule(scheduleId)
        recomputePlan(planId, updatedAt)
    }

    private suspend fun recomputeSchedule(scheduleId: String) {
        val schedule = database.creditCardInstallmentScheduleDao().findById(scheduleId) ?: return
        val plan = database.creditCardInstallmentDao().findById(schedule.planId) ?: return
        val paidPrincipal = database.creditCardInstallmentPaymentMatchDao().findBySchedule(scheduleId)
            .filter { it.status.isConfirmedPaymentMatch() }
            .sumOf { it.principalCents }
            .coerceAtMost(schedule.principalDueCents)
        val status = when {
            paidPrincipal == schedule.principalDueCents -> SCHEDULE_PAID
            schedule.revision == plan.scheduleRevision && plan.status !in setOf(STATUS_CANCELLED, STATUS_LEGACY) ->
                SCHEDULE_UPCOMING
            else -> SCHEDULE_CANCELLED
        }
        database.creditCardInstallmentScheduleDao().updatePaymentProgress(scheduleId, paidPrincipal, status)
    }

    private suspend fun recomputePlan(planId: String, updatedAt: Long) {
        val plan = database.creditCardInstallmentDao().findById(planId) ?: return
        val paidPrincipal = database.creditCardInstallmentPaymentMatchDao().all()
            .filter { it.planId == planId && it.status.isConfirmedPaymentMatch() }
            .sumOf { it.principalCents }
            .coerceAtMost(plan.originalPrincipalCents)
        val remainingPrincipal = plan.originalPrincipalCents - paidPrincipal
        val currentSchedules = database.creditCardInstallmentScheduleDao().findByPlan(planId)
            .filter { it.revision == plan.scheduleRevision }
        val status = when {
            plan.status == STATUS_CANCELLED -> STATUS_CANCELLED
            plan.status == STATUS_LEGACY -> STATUS_LEGACY
            remainingPrincipal == 0L -> STATUS_COMPLETED
            else -> STATUS_ACTIVE
        }
        val upcoming = currentSchedules.filter { it.status == SCHEDULE_UPCOMING }
        database.creditCardInstallmentDao().updatePaymentProgress(
            id = planId,
            remainingPrincipalCents = remainingPrincipal,
            periodsRemaining = upcoming.size,
            nextDueDateEpochDay = upcoming.minByOrNull { it.dueDateEpochDay }?.dueDateEpochDay,
            status = status,
            updatedAt = updatedAt
        )
    }

    private suspend fun appendPaymentAudit(
        planId: String,
        transfer: TransferEntity,
        scheduleId: String,
        principalCents: Long,
        eventType: String,
        occurredAt: Long,
        source: String
    ) {
        appendAudit(
            planId = planId,
            eventType = eventType,
            occurredAt = occurredAt,
            source = source,
            payload = JSONObject()
                .put("transferId", transfer.id)
                .put("scheduleId", scheduleId)
                .put("paymentCents", transfer.amountCents)
                .put("principalCents", principalCents)
                .put("unallocatedPaymentCents", (transfer.amountCents - principalCents).coerceAtLeast(0L))
        )
    }

    private suspend fun recordPaymentEvidence(
        planId: String,
        transfer: TransferEntity,
        scheduleId: String,
        principalCents: Long,
        occurredAt: Long,
        source: String
    ) {
        evidenceRecorder.link(
            groupId = "card-installment-payment:${transfer.id}:$scheduleId",
            subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
            subjectId = planId,
            subjectRole = "PAYMENT_BY_TRANSFER",
            sources = listOf(EvidenceSourceRef(EvidenceSourceType.LEDGER_EVENT, transfer.id)),
            linkedAt = occurredAt
        )
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.CARD_INSTALLMENT,
            subjectId = planId,
            action = EvidenceAction.LINKED,
            occurredAt = occurredAt,
            payload = JSONObject()
                .put("transferId", transfer.id)
                .put("scheduleId", scheduleId)
                .put("principalCents", principalCents)
                .put("source", source)
        )
        evidenceRecorder.lifecycle(
            subjectType = EvidenceSubjectType.TRANSFER,
            subjectId = transfer.id,
            action = EvidenceAction.LINKED,
            occurredAt = occurredAt,
            payload = JSONObject()
                .put("cardInstallmentPlanId", planId)
                .put("scheduleId", scheduleId)
        )
    }

    private fun CardInstallmentPaymentCandidate.toMatchEntity(
        transfer: TransferEntity,
        status: String,
        createdAt: Long,
        resolvedAt: Long?
    ) = CreditCardInstallmentPaymentMatchEntity(
        transferId = transfer.id,
        scheduleId = scheduleId,
        planId = planId,
        paymentCents = transfer.amountCents,
        principalCents = principalRemainingCents,
        status = status,
        source = "AUTO",
        createdAt = createdAt,
        resolvedAt = resolvedAt
    )

    private fun List<CreditCardInstallmentPaymentMatchEntity>.toDecision(): CardInstallmentPaymentMatchDecision {
        val confirmed = firstOrNull { it.status.isConfirmedPaymentMatch() }
        if (confirmed != null) {
            return CardInstallmentPaymentMatchDecision(
                CardInstallmentPaymentMatchKind.MATCHED,
                listOf(confirmed.scheduleId),
                confirmed.principalCents
            )
        }
        val pending = filter { it.status == STATUS_PENDING }.map { it.scheduleId }.sorted()
        return if (pending.isNotEmpty()) {
            CardInstallmentPaymentMatchDecision(CardInstallmentPaymentMatchKind.AMBIGUOUS, pending)
        } else {
            noPaymentMatch()
        }
    }

    private fun String.isConfirmedPaymentMatch(): Boolean =
        this == STATUS_AUTO_MATCHED || this == STATUS_USER_CONFIRMED

    private fun noPaymentMatch() =
        CardInstallmentPaymentMatchDecision(CardInstallmentPaymentMatchKind.NO_MATCH)

    private fun Long?.orZero(): Long = this ?: 0

    private fun moneyText(cents: Long): String = "¥%.2f".format(cents / 100.0)

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_LEGACY = "LEGACY_UNLINKED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_AUTO_MATCHED = "AUTO_MATCHED"
        const val STATUS_USER_CONFIRMED = "USER_CONFIRMED"
        const val SCHEDULE_UPCOMING = "UPCOMING"
        const val SCHEDULE_PAID = "PAID"
        const val SCHEDULE_CANCELLED = "CANCELLED"
        const val TYPE_POST_PURCHASE = "POST_PURCHASE_INSTALLMENT"
        const val TYPE_STATEMENT = "STATEMENT_INSTALLMENT"
    }
}
