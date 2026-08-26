package com.assetsking.ledger

import java.time.LocalDate
import java.time.ZoneId
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

    /**
     * 东八区的 UTC 零点落在本地早上 8 点。按 UTC 日分桶会把同一个设备日的
     * 两条同文案短信切成两个证据；按本地日分桶必须仍是同一条。
     * UTC 机器上两端本来就同日，断言退化为恒真，不会误报。
     */
    @Test
    fun `same local day across utc midnight keeps one id`() {
        val content = "账户支出35.00元"
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 8, 26)
        val earlyLocal = day.atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        val lateLocal = day.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()

        assertEquals(
            ContentFingerprint.stableSmsEvidenceId("95555", content, earlyLocal, zone),
            ContentFingerprint.stableSmsEvidenceId("95555", content, lateLocal, zone)
        )
    }

    @Test
    fun `decimal point remains significant in fingerprint`() {
        assertNotEquals(
            ContentFingerprint.of(null, "支出3.5元"),
            ContentFingerprint.of(null, "支出35元")
        )
        assertNotEquals(
            ContentFingerprint.of(null, "支出3。5元"),
            ContentFingerprint.of(null, "支出35元")
        )
    }
}
