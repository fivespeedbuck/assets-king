package com.assetsking.database

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * 垃圾箱依赖快照的唯一 JSON 口径。
 *
 * 删除/恢复与只读证据链审计必须比较同一份字段，避免一边新增字段后另一边仍把旧快照误判为可恢复。
 */
internal fun loanPlanToTrashJson(plan: LoanPlanEntity): JSONObject = JSONObject()
    .put("id", plan.id)
    .put("accountId", plan.accountId)
    .put("principalCents", plan.principalCents)
    .put("startDateEpochDay", plan.startDateEpochDay)
    .put("repaymentMethod", plan.repaymentMethod)
    .put("installmentsJson", plan.installmentsJson)
    .put("annualRateBps", plan.annualRateBps)
    .put("remainingPrincipalCents", plan.remainingPrincipalCents)
    .put("earlyRepaidCents", plan.earlyRepaidCents)
    .put("repaymentDay", plan.repaymentDay ?: JSONObject.NULL)
    .put("status", plan.status)
    .put("originType", plan.originType)
    .put("disbursementTransactionId", plan.disbursementTransactionId ?: JSONObject.NULL)
    .put("ledgerBaselinePrincipalCents", plan.ledgerBaselinePrincipalCents)
    .put("ledgerBaselineAt", plan.ledgerBaselineAt)

internal fun trashJsonToLoanPlan(json: JSONObject): LoanPlanEntity = LoanPlanEntity(
    id = json.getString("id"),
    accountId = json.getString("accountId"),
    principalCents = json.getLong("principalCents"),
    startDateEpochDay = json.getLong("startDateEpochDay"),
    repaymentMethod = json.getString("repaymentMethod"),
    installmentsJson = json.getString("installmentsJson"),
    annualRateBps = json.getInt("annualRateBps"),
    remainingPrincipalCents = json.getLong("remainingPrincipalCents"),
    earlyRepaidCents = json.getLong("earlyRepaidCents"),
    repaymentDay = if (json.isNull("repaymentDay")) null else json.getInt("repaymentDay"),
    status = json.getString("status"),
    originType = json.optString("originType", "OPENING_BALANCE"),
    disbursementTransactionId = if (!json.has("disbursementTransactionId") || json.isNull("disbursementTransactionId")) null else json.getString("disbursementTransactionId"),
    ledgerBaselinePrincipalCents = json.optLong("ledgerBaselinePrincipalCents", json.getLong("remainingPrincipalCents")),
    ledgerBaselineAt = json.optLong("ledgerBaselineAt", System.currentTimeMillis())
)

internal fun lendingPlanToTrashJson(plan: LendingPlanEntity): JSONObject = JSONObject()
    .put("id", plan.id)
    .put("receivableAccountId", plan.receivableAccountId)
    .put("label", plan.label)
    .put("borrowerName", plan.borrowerName)
    .put("principalCents", plan.principalCents)
    .put("remainingPrincipalCents", plan.remainingPrincipalCents)
    .put("expectedInterestCents", plan.expectedInterestCents)
    .put("receivedInterestCents", plan.receivedInterestCents)
    .put("startDateEpochDay", plan.startDateEpochDay)
    .put("expectedDueDateEpochDay", plan.expectedDueDateEpochDay ?: JSONObject.NULL)
    .put("status", plan.status)
    .put("originType", plan.originType)
    .put("disbursementTransferId", plan.disbursementTransferId ?: JSONObject.NULL)
    .put("ledgerBaselinePrincipalCents", plan.ledgerBaselinePrincipalCents)
    .put("ledgerBaselineInterestCents", plan.ledgerBaselineInterestCents)
    .put("ledgerBaselineAt", plan.ledgerBaselineAt)
    .put("createdAt", plan.createdAt)
    .put("updatedAt", plan.updatedAt)

internal fun trashJsonToLendingPlan(json: JSONObject): LendingPlanEntity = LendingPlanEntity(
    id = json.getString("id"),
    receivableAccountId = json.getString("receivableAccountId"),
    label = json.getString("label"),
    borrowerName = json.getString("borrowerName"),
    principalCents = json.getLong("principalCents"),
    remainingPrincipalCents = json.getLong("remainingPrincipalCents"),
    expectedInterestCents = json.getLong("expectedInterestCents"),
    receivedInterestCents = json.getLong("receivedInterestCents"),
    startDateEpochDay = json.getLong("startDateEpochDay"),
    expectedDueDateEpochDay = if (json.isNull("expectedDueDateEpochDay")) null else json.getLong("expectedDueDateEpochDay"),
    status = json.getString("status"),
    originType = json.getString("originType"),
    disbursementTransferId = if (json.isNull("disbursementTransferId")) null else json.getString("disbursementTransferId"),
    ledgerBaselinePrincipalCents = json.getLong("ledgerBaselinePrincipalCents"),
    ledgerBaselineInterestCents = json.getLong("ledgerBaselineInterestCents"),
    ledgerBaselineAt = json.getLong("ledgerBaselineAt"),
    createdAt = json.getLong("createdAt"),
    updatedAt = json.getLong("updatedAt")
)

internal fun installmentMatchToTrashJson(value: CreditCardInstallmentPaymentMatchEntity): JSONObject = JSONObject()
    .put("transferId", value.transferId)
    .put("scheduleId", value.scheduleId)
    .put("planId", value.planId)
    .put("paymentCents", value.paymentCents)
    .put("principalCents", value.principalCents)
    .put("status", value.status)
    .put("source", value.source)
    .put("createdAt", value.createdAt)
    .put("resolvedAt", value.resolvedAt ?: JSONObject.NULL)

internal fun trashJsonToInstallmentMatch(value: JSONObject): CreditCardInstallmentPaymentMatchEntity =
    CreditCardInstallmentPaymentMatchEntity(
        transferId = value.getString("transferId"),
        scheduleId = value.getString("scheduleId"),
        planId = value.getString("planId"),
        paymentCents = value.getLong("paymentCents"),
        principalCents = value.getLong("principalCents"),
        status = value.getString("status"),
        source = value.getString("source"),
        createdAt = value.getLong("createdAt"),
        resolvedAt = if (value.isNull("resolvedAt")) null else value.getLong("resolvedAt")
    )

internal fun installmentScheduleToTrashJson(value: CreditCardInstallmentScheduleEntity): JSONObject = JSONObject()
    .put("id", value.id)
    .put("planId", value.planId)
    .put("revision", value.revision)
    .put("number", value.number)
    .put("dueDateEpochDay", value.dueDateEpochDay)
    .put("principalDueCents", value.principalDueCents)
    .put("expectedInterestCents", value.expectedInterestCents)
    .put("expectedFeeCents", value.expectedFeeCents)
    .put("expectedUnclassifiedChargeCents", value.expectedUnclassifiedChargeCents)
    .put("principalPaidCents", value.principalPaidCents)
    .put("interestPaidCents", value.interestPaidCents)
    .put("feePaidCents", value.feePaidCents)
    .put("status", value.status)

internal fun trashJsonToInstallmentSchedule(value: JSONObject): CreditCardInstallmentScheduleEntity =
    CreditCardInstallmentScheduleEntity(
        id = value.getString("id"),
        planId = value.getString("planId"),
        revision = value.getInt("revision"),
        number = value.getInt("number"),
        dueDateEpochDay = value.getLong("dueDateEpochDay"),
        principalDueCents = value.getLong("principalDueCents"),
        expectedInterestCents = value.getLong("expectedInterestCents"),
        expectedFeeCents = value.getLong("expectedFeeCents"),
        expectedUnclassifiedChargeCents = value.getLong("expectedUnclassifiedChargeCents"),
        principalPaidCents = value.getLong("principalPaidCents"),
        interestPaidCents = value.getLong("interestPaidCents"),
        feePaidCents = value.getLong("feePaidCents"),
        status = value.getString("status")
    )

internal fun installmentPlanToTrashJson(value: CreditCardInstallmentEntity): JSONObject = JSONObject()
    .put("id", value.id)
    .put("cardAccountId", value.cardAccountId)
    .put("label", value.label)
    .put("originalPrincipalCents", value.originalPrincipalCents)
    .put("remainingPrincipalCents", value.remainingPrincipalCents)
    .put("monthlyPaymentCents", value.monthlyPaymentCents)
    .put("feeCentsPerPeriod", value.feeCentsPerPeriod)
    .put("periodsRemaining", value.periodsRemaining)
    .put("startDateEpochDay", value.startDateEpochDay)
    .put("installmentType", value.installmentType)
    .put("installmentCount", value.installmentCount)
    .put("nextDueDateEpochDay", value.nextDueDateEpochDay ?: JSONObject.NULL)
    .put("statementCycleStartEpochDay", value.statementCycleStartEpochDay ?: JSONObject.NULL)
    .put("status", value.status)
    .put("scheduleRevision", value.scheduleRevision)
    .put("createdAt", value.createdAt)
    .put("updatedAt", value.updatedAt)

internal fun trashJsonToInstallmentPlan(value: JSONObject): CreditCardInstallmentEntity = CreditCardInstallmentEntity(
    id = value.getString("id"),
    cardAccountId = value.getString("cardAccountId"),
    label = value.getString("label"),
    originalPrincipalCents = value.getLong("originalPrincipalCents"),
    remainingPrincipalCents = value.getLong("remainingPrincipalCents"),
    monthlyPaymentCents = value.getLong("monthlyPaymentCents"),
    feeCentsPerPeriod = value.getLong("feeCentsPerPeriod"),
    periodsRemaining = value.getInt("periodsRemaining"),
    startDateEpochDay = value.getLong("startDateEpochDay"),
    installmentType = value.getString("installmentType"),
    installmentCount = value.getInt("installmentCount"),
    nextDueDateEpochDay = if (value.isNull("nextDueDateEpochDay")) null else value.getLong("nextDueDateEpochDay"),
    statementCycleStartEpochDay = if (value.isNull("statementCycleStartEpochDay")) null else value.getLong("statementCycleStartEpochDay"),
    status = value.getString("status"),
    scheduleRevision = value.getInt("scheduleRevision"),
    createdAt = value.getLong("createdAt"),
    updatedAt = value.getLong("updatedAt")
)

internal fun jsonStructurallyEquals(first: JSONObject, second: JSONObject): Boolean =
    jsonValueEquals(first, second)

private fun jsonValueEquals(first: Any?, second: Any?): Boolean {
    if (first === JSONObject.NULL || first == null) return second === JSONObject.NULL || second == null
    if (second === JSONObject.NULL || second == null) return false
    return when {
        first is JSONObject && second is JSONObject -> {
            val firstKeys = first.keys().asSequence().toSet()
            val secondKeys = second.keys().asSequence().toSet()
            firstKeys == secondKeys && firstKeys.all { key -> jsonValueEquals(first.get(key), second.get(key)) }
        }
        first is JSONArray && second is JSONArray ->
            first.length() == second.length() && (0 until first.length()).all { index ->
                jsonValueEquals(first.get(index), second.get(index))
            }
        first is Number && second is Number ->
            BigDecimal(first.toString()).compareTo(BigDecimal(second.toString())) == 0
        else -> first == second
    }
}
