package com.assetsking.ui.format

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {
    @Test
    fun `grouped transaction time omits the repeated date`() {
        val occurredAt = Instant.parse("2026-08-20T02:16:00Z").toEpochMilli()

        assertEquals(
            "10:16",
            formatClockTime(occurredAt, TimeZone.getTimeZone("Asia/Shanghai"))
        )
    }

    @Test
    fun `editing date preserves local time and editing time preserves local date`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val original = Instant.parse("2026-08-20T02:16:00Z").toEpochMilli()
        val pickedDateUtc = Instant.parse("2026-08-03T00:00:00Z").toEpochMilli()

        val changedDate = replaceLocalDate(original, pickedDateUtc, zone)
        val changedTime = replaceLocalTime(changedDate, hour = 18, minute = 20, zone)

        val timeZone = TimeZone.getTimeZone(zone)
        assertEquals("08-03 10:16", formatTime(changedDate, timeZone))
        assertEquals("08-03 18:20", formatTime(changedTime, timeZone))
        assertEquals(pickedDateUtc, datePickerMillis(changedDate, zone))
    }
}
