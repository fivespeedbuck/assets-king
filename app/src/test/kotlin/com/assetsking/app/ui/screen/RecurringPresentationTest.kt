package com.assetsking.app.ui.screen

import com.assetsking.database.RecurringRuleEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurringPresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun sameDayAmountWithinToleranceFromAnyAccountIsShownAsMatchCandidate() {
        val date = LocalDate.of(2026, 8, 30)
        val dueAt = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val rule = recurringRule(dueAt).copy(amountCents = 3_000L)
        val sameDay = transaction("same-day", dueAt, accountId = "savings", amountCents = 2_500L)
        val wrongAmount = transaction("wrong-amount", dueAt, accountId = "savings", amountCents = 3_200L)
        val otherDay = transaction("other-day", date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), accountId = "cash", amountCents = rule.amountCents)
        val alreadyLinked = transaction("linked", dueAt, accountId = "cash", amountCents = 2_500L, recurringRuleId = "another-rule")

        assertEquals(
            listOf("wrong-amount", "same-day"),
            recurringMatchCandidates(rule, listOf(wrongAmount, otherDay, alreadyLinked, sameDay), zone).map { it.id }
        )
    }

    @Test
    fun multipleSameDayAmountsRemainSelectableInsteadOfBeingGuessed() {
        val date = LocalDate.of(2026, 8, 30)
        val dueAt = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val rule = recurringRule(dueAt)
        val first = transaction("first", dueAt + 1_000, accountId = "cash", amountCents = rule.amountCents)
        val second = transaction("second", dueAt + 2_000, accountId = "savings", amountCents = rule.amountCents)

        assertEquals(listOf("second", "first"), recurringMatchCandidates(rule, listOf(first, second), zone).map { it.id })
    }

    private fun recurringRule(dueAt: Long) = RecurringRuleEntity(
        id = "rule",
        accountId = "",
        amountCents = 88_000L,
        type = TransactionType.EXPENSE.name,
        category = "",
        merchant = null,
        note = "房租",
        interval = "MONTHLY",
        nextRunAt = dueAt
    )

    private fun transaction(
        id: String,
        occurredAt: Long,
        accountId: String,
        amountCents: Long,
        recurringRuleId: String? = null
    ) = TransactionEntity(
        id = id,
        accountId = accountId,
        amountCents = amountCents,
        type = TransactionType.EXPENSE.name,
        category = "",
        occurredAt = occurredAt,
        merchant = "房东",
        recurringRuleId = recurringRuleId
    )
}
