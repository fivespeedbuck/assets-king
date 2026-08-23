package com.assetsking.app.ui.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class PrivacyChaosPatternTest {
    @Test
    fun compactAmountKeepsOneLineSummarySlotsShort() {
        assertEquals("+¥234.56", compactPrivacyAmount("+¥1,234.56"))
        assertEquals(8, compactPrivacyAmount("−¥9,876.54").length)
    }

    @Test
    fun patternIsStableForOneSeedAndChangesForAnother() {
        assertEquals(privacyChaosPattern(42), privacyChaosPattern(42))
        assertNotEquals(privacyChaosPattern(42), privacyChaosPattern(43))
    }

    @Test
    fun patternContainsOnlyBoundedFakeGeometry() {
        val pattern = privacyChaosPattern(20260822)

        assertEquals(5, pattern.fog.size)
        assertEquals(3, pattern.rings.size)
        assertEquals(9, pattern.trend.size)
        assertEquals(8, pattern.labels.size)
        assertTrue(pattern.fog.all { (point, radius) ->
            point.x in -0.05f..1.05f && point.y in 0.02f..0.98f && radius in 0.16f..0.32f
        })
        assertTrue(pattern.rings.all { ring ->
            ring.center.x in 0.14f..0.86f && ring.center.y in 0.16f..0.84f &&
                ring.radius in 0.08f..0.16f && ring.sweepAngle in 110f..280f
        })
        assertTrue(pattern.trend.zipWithNext().all { (left, right) -> left.x < right.x })
        assertTrue(pattern.labels.all { it.text.matches(Regex("[+−]¥[0-9,]+\\.\\d{2}")) })
    }

    @Test
    fun continuousFrameIsSeededBoundedAndIndependent() {
        val frame = privacyChaosFrame(20260822)

        assertEquals(frame, privacyChaosFrame(20260822))
        assertNotEquals(frame, privacyChaosFrame(20260823))
        assertTrue(abs(frame.innerRingFractions.sum() - 1f) < 0.0001f)
        assertTrue(abs(frame.outerRingFractions.sum() - 1f) < 0.0001f)
        assertTrue(frame.progressFractions.all { it in 0.08f..0.96f })
        assertTrue(frame.trendYFractions.all { it in 0.22f..0.78f })
        assertTrue(frame.barFractions.all { (income, expense, repayment) ->
            income in 0.22f..0.98f && expense in 0.12f..0.88f && repayment in 0.08f..0.72f
        })
        assertTrue(frame.fakeAmounts.all { it.matches(Regex("[+−]¥[0-9,]+\\.\\d{2}")) })
        assertEquals(setOf(10), frame.fakeAmounts.map(String::length).toSet())
    }

    @Test
    fun continuousFrameReturnsToOneSharedBeat() {
        val start = privacyChaosFrame(42, tick = 0)
        val next = privacyChaosFrame(42, tick = 1)

        assertNotEquals(start.fakeAmounts, next.fakeAmounts)
        assertTrue(start.fakeAmounts.zip(next.fakeAmounts).all { (left, right) -> left != right })
    }

    @Test
    fun ambientFogLayersStaySubtleSlowAndOutOfPhase() {
        assertEquals(4, PrivacyAmbientFogSpecs.size)
        assertEquals(4, PrivacyAmbientFogSpecs.map { it.durationMillis }.distinct().size)
        assertTrue(PrivacyAmbientFogSpecs.all { spec ->
            spec.durationMillis >= 12_000 && spec.alpha in 0.10f..0.25f
        })
    }

    @Test
    fun ambientFogGetsSafeStableVariationForEveryPrivacyEntry() {
        val first = privacyAmbientFogVariations(seed = 17)
        val again = privacyAmbientFogVariations(seed = 17)
        val nextEntry = privacyAmbientFogVariations(seed = 18)

        assertEquals(PrivacyAmbientFogSpecs.size, first.size)
        assertEquals(first, again)
        assertTrue(first != nextEntry)
        assertTrue(first.all { variation ->
            variation.offsetXDp in -18f..18f &&
                variation.offsetYDp in -16f..16f &&
                variation.scaleMultiplier in 0.97f..1.04f &&
                variation.rotationOffset in -2f..2f &&
                variation.direction in setOf(-1f, 1f) &&
                variation.alphaMultiplier in 0.90f..1.05f
        })
        assertEquals(1, first.map { it.flipX }.distinct().size)
        assertEquals(1, first.map { it.flipY }.distinct().size)
        val entries = (0..32).map(::privacyAmbientFogVariations)
        assertEquals(setOf(false, true), entries.map { it.first().flipX }.toSet())
        assertEquals(setOf(false, true), entries.map { it.first().flipY }.toSet())
    }
}
