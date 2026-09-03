package com.assetsking.app.ui.screen

import com.assetsking.usecase.MonthlyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.YearMonth

class StatsPresentationTest {
    @Test
    fun privacyModeUsesIndependentFakeTrendAndRestoresImmediately() {
        val bars = listOf(
            MonthlyBar("2026-07", 500_000L, 200_000L, 120_000L),
            MonthlyBar("2026-08", 680_000L, 350_000L, 180_000L)
        )
        val unrelatedRealValues = listOf(
            MonthlyBar("2026-07", 1L, 2L, 3L),
            MonthlyBar("2026-08", 4L, 5L, 6L)
        )
        val fakeFractions = listOf(
            Triple(0.31f, 0.42f, 0.18f),
            Triple(0.77f, 0.24f, 0.51f)
        )
        val hidden = privacySafeMonthlyBars(bars, privacyEnabled = true, fakeFractions = fakeFractions)

        assertEquals(
            hidden,
            privacySafeMonthlyBars(unrelatedRealValues, privacyEnabled = true, fakeFractions = fakeFractions)
        )
        assertTrue(hidden.all { it.incomeCents > 0L && it.expenseCents > 0L && it.repaymentCents > 0L })
        assertTrue(hidden.zip(bars).all { (fake, real) -> fake != real })
        assertEquals(bars, privacySafeMonthlyBars(bars, privacyEnabled = false))
    }

    @Test
    fun necessityLabelOnlyCallsOutNonNecessarySpending() {
        assertEquals("0%", categoryNecessityLabel(150_000L, 0L))
        assertEquals("25%", categoryNecessityLabel(100_000L, 25_000L))
    }

    @Test
    fun donutCalloutsUseSymmetricHorizontalLeaders() {
        val placements = layoutDonutCallouts(
            canvasWidth = 1_200f,
            canvasHeight = 600f,
            edgePadding = 24f,
            labelGap = 30f,
            verticalGap = 54f,
            inputs = listOf(
                DonutCalloutInput(0, right = false, rawY = 180f, labelWidth = 100f, labelHeight = 40f),
                DonutCalloutInput(1, right = true, rawY = 420f, labelWidth = 100f, labelHeight = 40f)
            )
        )

        val left = placements[0]
        val right = placements[1]
        assertEquals(left.elbowX - left.lineEndX, right.lineEndX - right.elbowX, 0.01f)
        assertEquals(30f, left.lineEndX - (left.labelX + 100f), 0.01f)
        assertEquals(30f, right.labelX - right.lineEndX, 0.01f)
    }

    @Test
    fun donutCalloutsMeasureWhitespaceFromVisibleGlyphEdges() {
        val placements = layoutDonutCallouts(
            canvasWidth = 1_200f,
            canvasHeight = 600f,
            edgePadding = 24f,
            labelGap = 30f,
            verticalGap = 54f,
            inputs = listOf(
                DonutCalloutInput(
                    index = 0,
                    right = false,
                    rawY = 180f,
                    labelWidth = 100f,
                    labelHeight = 40f,
                    visibleLeft = 4f,
                    visibleRight = 92f
                ),
                DonutCalloutInput(
                    index = 1,
                    right = true,
                    rawY = 420f,
                    labelWidth = 100f,
                    labelHeight = 40f,
                    visibleLeft = 6f,
                    visibleRight = 96f
                )
            )
        )

        val left = placements[0]
        val right = placements[1]
        assertEquals(30f, left.lineEndX - (left.labelX + 92f), 0.01f)
        assertEquals(30f, right.labelX + 6f - right.lineEndX, 0.01f)
    }

    @Test
    fun donutCalloutsKeepSameSideLabelsApartAndInsideCanvas() {
        val placements = layoutDonutCallouts(
            canvasWidth = 1_200f,
            canvasHeight = 300f,
            edgePadding = 24f,
            labelGap = 30f,
            verticalGap = 54f,
            inputs = listOf(
                DonutCalloutInput(0, right = false, rawY = 230f, labelWidth = 100f, labelHeight = 40f),
                DonutCalloutInput(1, right = false, rawY = 240f, labelWidth = 120f, labelHeight = 40f),
                DonutCalloutInput(2, right = false, rawY = 250f, labelWidth = 90f, labelHeight = 40f)
            )
        )

        assertTrue(placements.zipWithNext().all { (a, b) -> b.lineY - a.lineY >= 54f })
        assertTrue(placements.all { it.lineY in 44f..256f })
    }

    @Test
    fun donutCenterCyclesExpenseIncomeBalanceAndRepaymentWithoutChangingSlices() {
        val metrics = donutCenterMetrics(
            expenseCents = 320_350L,
            incomeCents = 698_983L,
            repaymentCents = 342_000L
        )

        assertEquals(listOf("总支出", "总收入", "总结余", "已还款"), metrics.map { it.label })
        assertEquals(listOf(320_350L, 698_983L, 36_633L, 342_000L), metrics.map { it.valueCents })
        assertEquals(1, nextDonutCenterMode(current = 0, count = metrics.size))
        assertEquals(2, nextDonutCenterMode(current = 1, count = metrics.size))
        assertEquals(3, nextDonutCenterMode(current = 2, count = metrics.size))
        assertEquals(0, nextDonutCenterMode(current = 3, count = metrics.size))
    }

    @Test
    fun trendCompositionFillsPositiveBalanceToIncomeHeight() {
        val composition = trendComposition(
            incomeCents = 698_983L,
            expenseCents = 320_350L,
            repaymentCents = 342_000L
        )

        assertEquals(662_350L, composition.outflowCents)
        assertEquals(662_350L, composition.incomeCoveredOutflowCents)
        assertEquals(36_633L, composition.balanceCents)
        assertEquals(36_633L, composition.positiveBalanceCents)
        assertEquals(0L, composition.deficitCents)
        assertEquals(698_983L, composition.compositionHeightCents)
    }

    @Test
    fun trendCompositionShowsDeficitWithoutBlueSegment() {
        val composition = trendComposition(
            incomeCents = 200_000L,
            expenseCents = 250_000L,
            repaymentCents = 50_000L
        )

        assertEquals(300_000L, composition.outflowCents)
        assertEquals(200_000L, composition.incomeCoveredOutflowCents)
        assertEquals(-100_000L, composition.balanceCents)
        assertEquals(0L, composition.positiveBalanceCents)
        assertEquals(100_000L, composition.deficitCents)
        assertEquals(300_000L, composition.compositionHeightCents)
    }

    @Test
    fun trendBarsShareOnePositiveAxisForIncomeAndOutflowComposition() {
        val axis = trendAxisRange(
            incomes = listOf(0L, 500_00L, 200_00L),
            outflows = listOf(0L, 200_00L, 450_00L)
        )

        val zeroY = axis.yFor(0L, top = 10f, bottom = 210f)
        assertEquals(zeroY, axis.yFor(0L, top = 10f, bottom = 210f), 0.01f)
        assertTrue(axis.yFor(500_00L, 10f, 210f) < zeroY)
        assertEquals(0L, axis.minCents)
    }

    @Test
    fun trendDefaultsToSixMonthsAndUsesRoundedHeadroom() {
        assertEquals(6, DEFAULT_TREND_MONTHS)

        val axis = trendAxisRange(
            incomes = listOf(665_000L, 698_983L),
            outflows = listOf(662_350L, 328_150L)
        )

        assertEquals(800_000L, axis.maxCents)
        assertEquals(200_000L, axis.tickStepCents)
        assertEquals(listOf(0L, 200_000L, 400_000L, 600_000L, 800_000L), axis.tickValues())
        assertTrue(axis.maxCents > 698_983L)

        val deficitAxis = trendAxisRange(
            incomes = listOf(682_000L),
            outflows = listOf(1_094_100L)
        )
        assertEquals(1_250_000L, deficitAxis.maxCents)
        assertEquals(250_000L, deficitAxis.tickStepCents)
        assertEquals(
            listOf(0L, 250_000L, 500_000L, 750_000L, 1_000_000L, 1_250_000L),
            deficitAxis.tickValues()
        )

    }

    @Test
    fun trendSelectionFallsBackToVisibleLatestMonth() {
        assertEquals(
            YearMonth.of(2026, 8),
            effectiveTrendMonth(listOf("2026-06", "2026-07", "2026-08"), YearMonth.of(2026, 3))
        )
        assertEquals(
            YearMonth.of(2026, 7),
            effectiveTrendMonth(listOf("2026-06", "2026-07", "2026-08"), YearMonth.of(2026, 7))
        )
    }

    @Test
    fun trendMonthTicksStayReadableAtThreeSixAndTwelveMonths() {
        val twelveMonths = (0..11).map { YearMonth.of(2025, 9).plusMonths(it.toLong()) }

        assertTrue((0..2).all { trendMonthTickVisible(it, 3, YearMonth.of(2026, 6 + it)) })
        assertTrue((0..5).all { trendMonthTickVisible(it, 6, YearMonth.of(2026, 3 + it)) })
        assertEquals(
            listOf(0, 4, 8, 11),
            twelveMonths.indices.filter { trendMonthTickVisible(it, 12, twelveMonths[it]) }
        )
    }
}
