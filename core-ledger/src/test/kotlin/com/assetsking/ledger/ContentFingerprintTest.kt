package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentFingerprintTest {
    @Test
    fun `sms live receiver and rescan share one stable id`() {
        val content = "【招商银行】您账户3683于08月25日17:47快捷支付35.00元，余额3138.74"
        val postedAt = 1_787_651_241_232L

        val live = ContentFingerprint.stableSmsEvidenceId("95555", content, postedAt)
        val rescan = ContentFingerprint.stableSmsEvidenceId("95555", content, postedAt + 2_000L)

        assertEquals(live, rescan)
        assertTrue(live.matches(Regex("sms:[0-9a-f]{64}")))
    }

    @Test
    fun `same fixed sms text on another day gets a different id`() {
        val content = "账户支出35.00元"
        val first = ContentFingerprint.stableSmsEvidenceId("95555", content, 86_400_000L)
        val nextDay = ContentFingerprint.stableSmsEvidenceId("95555", content, 2 * 86_400_000L)

        assertNotEquals(first, nextDay)
    }
}
