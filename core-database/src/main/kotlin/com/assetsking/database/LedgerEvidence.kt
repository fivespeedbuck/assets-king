package com.assetsking.database

import org.json.JSONObject
import java.util.UUID

object EvidenceSubjectType {
    const val TRANSACTION = "TRANSACTION"
    const val TRANSFER = "TRANSFER"
    const val LOAN_PLAN = "LOAN_PLAN"
    const val CARD_INSTALLMENT = "CARD_INSTALLMENT"
    const val RECURRING_RULE = "RECURRING_RULE"
    const val LENDING_PLAN = "LENDING_PLAN"
}

object EvidenceSourceType {
    const val RAW_NOTIFICATION = "RAW_NOTIFICATION"
    const val MANUAL_ENTRY = "MANUAL_ENTRY"
    const val OPENING_BALANCE = "OPENING_BALANCE"
    const val RECURRING_RULE = "RECURRING_RULE"
    const val SYSTEM_EVENT = "SYSTEM_EVENT"
    const val LEDGER_EVENT = "LEDGER_EVENT"
    const val LEGACY_IMPORT = "LEGACY_IMPORT"
}

object EvidenceAction {
    const val CREATED = "CREATED"
    const val EDITED = "EDITED"
    const val LINKED = "LINKED"
    const val EFFECT_REVERSED = "EFFECT_REVERSED"
    const val EFFECT_RESTORED = "EFFECT_RESTORED"
    const val TRASHED = "TRASHED"
    const val RESTORED = "RESTORED"
    const val PURGED = "PURGED"
}

object LendingOriginType {
    const val OPENING_BALANCE = "OPENING_BALANCE"
    const val PENDING_DISBURSEMENT = "PENDING_DISBURSEMENT"
    const val DISBURSEMENT_TRANSFER = "DISBURSEMENT_TRANSFER"
}

object LendingPlanStatus {
    const val PENDING_DISBURSEMENT = "PENDING_DISBURSEMENT"
    const val ACTIVE = "ACTIVE"
    const val COMPLETED = "COMPLETED"
}

object LendingTransferRole {
    const val DISBURSEMENT = "DISBURSEMENT"
    const val PRINCIPAL_REPAYMENT = "PRINCIPAL_REPAYMENT"
}

data class EvidenceSourceRef(
    val type: String,
    val id: String
)

data class LendingPlanDraft(
    val label: String,
    val borrowerName: String,
    val principalCents: Long,
    val expectedInterestCents: Long = 0,
    val startDateEpochDay: Long,
    val expectedDueDateEpochDay: Long? = null,
    val originType: String = LendingOriginType.OPENING_BALANCE
)

internal class LedgerEvidenceRecorder(
    private val database: AssetsKingDatabase
) {
    suspend fun link(
        groupId: String,
        subjectType: String,
        subjectId: String,
        subjectRole: String,
        sources: Collection<EvidenceSourceRef>,
        linkedAt: Long
    ) {
        require(groupId.isNotBlank())
        require(subjectId.isNotBlank())
        require(sources.isNotEmpty()) { "账务对象至少需要一个来源证据" }
        database.ledgerEvidenceLinkDao().insertAll(
            sources.distinct().map { source ->
                require(source.type.isNotBlank() && source.id.isNotBlank())
                LedgerEvidenceLinkEntity(
                    groupId = groupId,
                    subjectType = subjectType,
                    subjectId = subjectId,
                    subjectRole = subjectRole,
                    sourceType = source.type,
                    sourceId = source.id,
                    linkedAt = linkedAt
                )
            }
        )
    }

    suspend fun lifecycle(
        subjectType: String,
        subjectId: String,
        action: String,
        occurredAt: Long = System.currentTimeMillis(),
        payload: JSONObject = JSONObject()
    ) {
        database.ledgerLifecycleEventDao().insert(
            LedgerLifecycleEventEntity(
                id = UUID.randomUUID().toString(),
                subjectType = subjectType,
                subjectId = subjectId,
                action = action,
                occurredAt = occurredAt,
                payloadJson = payload.toString()
            )
        )
    }
}
