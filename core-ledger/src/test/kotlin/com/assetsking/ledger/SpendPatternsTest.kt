package com.assetsking.ledger

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpendPatternsTest {

    private val optional = setOf("ENTERTAINMENT", "SHOPPING", "OTHER")

    // ── 必要生活预算建议 ──

    private fun spend(ym: String, cat: String, cents: Long) = SpendSample(ym, cat, cents)

    @Test
    fun `necessary living sums per-category medians of complete months`() {
        val s = suggestNecessaryLiving(
            spends = listOf(
                spend("2026-05", "HOUSING", 200_000), spend("2026-05", "DINING", 180_000),
                spend("2026-06", "HOUSING", 200_000), spend("2026-06", "DINING", 190_000),
                spend("2026-07", "HOUSING", 200_000), spend("2026-07", "DINING", 170_000)
            ),
            optionalCategories = optional,
            currentYearMonth = "2026-08"
        )
        assertEquals(3, s.monthsUsed)
        assertEquals(380_000, s.totalCents)                 // 房租 2000 + 餐饮中位 1800
        assertEquals("HOUSING" to 200_000L, s.byCategoryCents.first())
        // 明细加起来必须正好等于总数，否则用户对不上账
        assertEquals(s.totalCents, s.byCategoryCents.sumOf { it.second })
    }

    @Test
    fun `one-off big spend does not inflate the budget`() {
        val s = suggestNecessaryLiving(
            spends = listOf(
                spend("2026-05", "DINING", 180_000),
                spend("2026-06", "DINING", 180_000),
                spend("2026-07", "DINING", 180_000),
                spend("2026-06", "MEDICAL", 800_000)     // 看了一次牙，只这个月有
            ),
            optionalCategories = optional,
            currentYearMonth = "2026-08"
        )
        // 中位数把一次性大额压掉：MEDICAL 三个月是 [0, 8000, 0] → 中位 0
        assertEquals(180_000, s.totalCents)
        assertTrue(s.byCategoryCents.none { it.first == "MEDICAL" })
    }

    @Test
    fun `optional categories are excluded and current month is ignored`() {
        val s = suggestNecessaryLiving(
            spends = listOf(
                spend("2026-07", "DINING", 180_000),
                spend("2026-07", "ENTERTAINMENT", 500_000),  // 非必要 → 不进预算
                spend("2026-08", "DINING", 900_000)          // 当月未过完 → 不参与
            ),
            optionalCategories = optional,
            currentYearMonth = "2026-08"
        )
        assertEquals(180_000, s.totalCents)
        assertEquals(1, s.monthsUsed)
    }

    @Test
    fun `no history yields no suggestion instead of a bogus zero budget`() {
        val s = suggestNecessaryLiving(emptyList(), optional, "2026-08")
        assertFalse(s.hasData)
        assertEquals(0, s.monthsUsed)
    }

    // ── 固定扣款识别 ──

    private fun charge(merchant: String, cents: Long, date: LocalDate, account: String = "cmb") =
        ChargeSample(merchant, account, "DIGITAL_SERVICES", cents, date.toEpochDay())

    private val today = LocalDate.of(2026, 8, 13)

    @Test
    fun `detects a monthly subscription and reports its amount and day`() {
        val found = detectRecurringCharges(
            samples = listOf(
                charge("网易云音乐", 1_500, LocalDate.of(2026, 5, 8)),
                charge("网易云音乐", 1_500, LocalDate.of(2026, 6, 8)),
                charge("网易云音乐", 1_500, LocalDate.of(2026, 7, 8)),
                charge("网易云音乐", 1_500, LocalDate.of(2026, 8, 8))
            ),
            todayEpochDay = today.toEpochDay()
        )
        assertEquals(1, found.size)
        assertEquals(1_500, found[0].amountCents)
        assertEquals(4, found[0].occurrences)
        assertEquals(8, found[0].dayOfMonth)
        assertTrue(found[0].intervalDays in 30..31)
    }

    @Test
    fun `supermarket-style varying amounts are not recurring charges`() {
        val found = detectRecurringCharges(
            samples = listOf(
                charge("盒马", 12_000, LocalDate.of(2026, 5, 8)),
                charge("盒马", 38_000, LocalDate.of(2026, 6, 9)),
                charge("盒马", 7_600, LocalDate.of(2026, 7, 8)),
                charge("盒马", 25_000, LocalDate.of(2026, 8, 7))
            ),
            todayEpochDay = today.toEpochDay()
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `cancelled subscription is not recommended anymore`() {
        val found = detectRecurringCharges(
            samples = listOf(
                charge("某会员", 2_500, LocalDate.of(2026, 1, 8)),
                charge("某会员", 2_500, LocalDate.of(2026, 2, 8)),
                charge("某会员", 2_500, LocalDate.of(2026, 3, 8))   // 3 月后就没了
            ),
            todayEpochDay = today.toEpochDay()
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `two charges are not enough and same-day duplicates do not count twice`() {
        val twice = detectRecurringCharges(
            listOf(
                charge("A", 1_000, LocalDate.of(2026, 7, 8)),
                charge("A", 1_000, LocalDate.of(2026, 8, 8))
            ),
            today.toEpochDay()
        )
        assertTrue(twice.isEmpty())

        // 一天刷三笔 ≠ 三次扣款
        val sameDay = detectRecurringCharges(
            listOf(
                charge("B", 1_000, LocalDate.of(2026, 8, 8)),
                charge("B", 1_000, LocalDate.of(2026, 8, 8)),
                charge("B", 1_000, LocalDate.of(2026, 8, 8))
            ),
            today.toEpochDay()
        )
        assertTrue(sameDay.isEmpty())
    }
}
