package com.assetsking.ui.format

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun formatTime(time: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).apply { this.timeZone = timeZone }.format(Date(time))

/** 日期已由分组标题表达时，记录行只显示时分，避免重复日期挤压业务元数据。 */
fun formatClockTime(time: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).apply { this.timeZone = timeZone }.format(Date(time))

/** Material DatePicker 使用 UTC 零点；这里把本地日期转换为它需要的毫秒值。 */
fun datePickerMillis(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** 替换本地日期并保留原时分秒。 */
fun replaceLocalDate(
    originalMillis: Long,
    pickedDateUtcMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val date = Instant.ofEpochMilli(pickedDateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val time = Instant.ofEpochMilli(originalMillis).atZone(zone).toLocalTime()
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

/** 替换本地时分并保留原日期；秒归零以匹配分钟级时间选择器。 */
fun replaceLocalTime(
    originalMillis: Long,
    hour: Int,
    minute: Int,
    zone: ZoneId = ZoneId.systemDefault()
): Long {
    val date = Instant.ofEpochMilli(originalMillis).atZone(zone).toLocalDate()
    return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
