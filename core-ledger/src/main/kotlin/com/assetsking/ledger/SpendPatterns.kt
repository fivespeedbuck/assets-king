package com.assetsking.ledger

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * 从历史流水反推两个本来要用户手填、但用户根本填不准的东西：
 *  1. 必要生活预算 —— 装记账软件就是为了搞清楚钱去哪了，不该上来先要用户填答案
 *  2. 固定扣款 —— "挺多扣款我自己都不知道"，知道就直接填了
 *
 * 两个都是纯函数：不依赖 Android，可单测。结果只做**建议**，
 * 采不采用由用户点确认（V5 §40 定义必要生活是用户设置项，不能自动改掉）。
 */

// ── 必要生活预算建议 ──

/** 一笔已发生的支出，只保留反推需要的字段 */
data class SpendSample(
    val yearMonth: String,      // "2026-07"
    val category: String,
    val amountCents: Long
)

data class NecessaryLivingSuggestion(
    val totalCents: Long,
    val monthsUsed: Int,
    val byCategoryCents: List<Pair<String, Long>>   // 分类 → 月度中位数，降序；合计恰好等于 totalCents
) {
    val hasData: Boolean get() = monthsUsed > 0 && totalCents > 0
}

/**
 * 必要生活预算 = 近 [lookbackMonths] 个**完整**月里，非「非必要」分类支出的月度水平。
 *
 * 三个刻意的选择：
 *  - 排除当月：月份没过完，算进去会系统性压低预算
 *  - 逐分类取中位数而不是平均：一次性大额（换手机、看牙）只出现在一个月，
 *    中位数会把它压掉，平均数不会 —— 预算要的是"每月都得花"的水平
 *  - 合计 = 各分类中位数之和：保证明细加起来正好等于总数，用户能对得上账
 */
fun suggestNecessaryLiving(
    spends: List<SpendSample>,
    optionalCategories: Set<String>,
    currentYearMonth: String,
    lookbackMonths: Int = 3
): NecessaryLivingSuggestion {
    require(lookbackMonths > 0) { "lookbackMonths must be positive" }
    val current = YearMonth.parse(currentYearMonth)
    val window = (1..lookbackMonths).map { current.minusMonths(it.toLong()).toString() }.toSet()

    val inWindow = spends.filter { it.yearMonth in window }
    // 有流水的月份（含只花了非必要的月）——某个月没买菜就该按 0 参与中位数
    val monthsWithData = inWindow.map { it.yearMonth }.distinct()
    if (monthsWithData.isEmpty()) return NecessaryLivingSuggestion(0, 0, emptyList())

    val necessary = inWindow.filter { it.category !in optionalCategories }
    val byCategory = necessary
        .groupBy { it.category }
        .mapValues { (_, rows) ->
            val perMonth = monthsWithData.map { ym ->
                rows.filter { it.yearMonth == ym }.sumOf { it.amountCents }
            }
            medianOf(perMonth)
        }
        .filterValues { it > 0 }

    return NecessaryLivingSuggestion(
        totalCents = byCategory.values.sum(),
        monthsUsed = monthsWithData.size,
        byCategoryCents = byCategory.entries.sortedByDescending { it.value }.map { it.key to it.value }
    )
}

// ── 固定扣款识别 ──

/** 一笔支出流水，只保留识别固定扣款需要的字段 */
data class ChargeSample(
    val merchant: String,
    val accountId: String,
    val category: String,
    val amountCents: Long,
    val occurredEpochDay: Long
)

data class DetectedRecurring(
    val merchant: String,
    val accountId: String,
    val category: String,
    val amountCents: Long,          // 中位金额
    val occurrences: Int,
    val intervalDays: Int,          // 中位间隔
    val dayOfMonth: Int,            // 常见扣款日
    val lastSeenEpochDay: Long
)

private val MONTHLY_INTERVAL = 24L..35L      // 月付：按 28/30/31 天浮动，再放宽几天
private const val AMOUNT_TOLERANCE_PCT = 15  // 金额飘动上限，容一次调价

/**
 * 找出"同商户 + 金额稳定 + 间隔约一个月 + 至少 [minOccurrences] 次"的扣款。
 *
 * ponytail: 只识别月付。周付太少见，年付要 3 年数据才够 3 次——不是我偷懒，是数据上不可能。
 * 命中后只做推荐，用户点确认才会变成 RecurringRule。
 */
fun detectRecurringCharges(
    samples: List<ChargeSample>,
    todayEpochDay: Long,
    minOccurrences: Int = 3
): List<DetectedRecurring> = samples
    .filter { it.merchant.isNotBlank() }
    .groupBy { it.merchant.trim() }
    .mapNotNull { (merchant, rows) ->
        // 同一天的重复流水算一次，否则一天刷三笔会被当成"高频扣款"
        val byDay = rows.groupBy { it.occurredEpochDay }
        if (byDay.size < minOccurrences) return@mapNotNull null

        val days = byDay.keys.sorted()
        val intervals = days.zipWithNext { a, b -> b - a }
        val intervalDays = medianOf(intervals)
        if (intervalDays !in MONTHLY_INTERVAL) return@mapNotNull null

        // 已经停扣的别再推荐：超过一个半周期没出现就当结束了
        if (todayEpochDay - days.last() > intervalDays * 3 / 2 + 7) return@mapNotNull null

        val amounts = rows.map { it.amountCents }
        val amount = medianOf(amounts)
        if (amount <= 0) return@mapNotNull null
        // 金额每次都不一样的（超市、加油）不是固定扣款：要求过半数贴近中位数
        val stable = amounts.count { abs(it - amount) * 100 <= amount * AMOUNT_TOLERANCE_PCT }
        if (stable * 2 <= amounts.size) return@mapNotNull null

        val last = rows.filter { it.occurredEpochDay == days.last() }.first()
        DetectedRecurring(
            merchant = merchant,
            accountId = last.accountId,
            category = last.category,
            amountCents = amount,
            occurrences = byDay.size,
            intervalDays = intervalDays.toInt(),
            dayOfMonth = LocalDate.ofEpochDay(days.last()).dayOfMonth,
            lastSeenEpochDay = days.last()
        )
    }
    .sortedByDescending { it.amountCents }

/** 偶数个取中间两个的均值；空列表按 0 */
internal fun medianOf(values: List<Long>): Long {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
