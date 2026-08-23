package com.assetsking.ui.format

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.assetsking.ui.privacy.PrivacyMode
import java.text.NumberFormat
import java.util.Locale

private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.CHINA)

fun formatMoney(cents: Long): String =
    if (PrivacyMode.enabled) PrivacyMode.maskedAmount() else currencyFormat.format(cents / 100.0)

/** 效果图口径：整元省略 .00，有角分时固定两位。 */
fun formatMoneyCompact(cents: Long): String {
    if (PrivacyMode.enabled) return PrivacyMode.maskedAmount()
    val (intPart, decPart) = splitMoney(cents)
    val sign = if (cents < 0) "−" else ""
    return buildString {
        append(sign)
        append('¥')
        append(intPart)
        if (decPart != null) append('.').append(decPart)
    }
}

/** 单一入口生成带方向的金额，避免调用方手写“−¥”后再拼一个自带 ¥ 的金额。 */
fun formatSignedMoney(cents: Long, positive: Boolean?): String {
    if (PrivacyMode.enabled) return PrivacyMode.maskedAmount()
    val unsigned = formatMoneyCompact(kotlin.math.abs(cents)).removePrefix("−")
    return when (positive) {
        true -> "+$unsigned"
        false -> "−$unsigned"
        null -> unsigned
    }
}

/** 月历每日净变化：小额保留角分，长金额按千/万缩写，确保 7 列窄单元格不裁切。 */
fun formatDailyNetChange(cents: Long): String {
    if (PrivacyMode.enabled) return PrivacyMode.maskedAmount()
    if (cents == 0L) return ""
    val absolute = kotlin.math.abs(cents)
    val unsigned = when {
        absolute >= 1_000_000L -> compactDecimal(absolute, 1_000_000L) + "万"
        absolute >= 100_000L -> compactDecimal(absolute, 100_000L) + "k"
        else -> compactDecimal(absolute, 100L)
    }
    return if (cents > 0) "+$unsigned" else "−$unsigned"
}

private fun compactDecimal(value: Long, unit: Long): String {
    val whole = value / unit
    val remainder = value % unit
    val roundedHundredths = (remainder * 100L + unit / 2L) / unit
    val roundedWhole = whole + roundedHundredths / 100L
    val decimals = (roundedHundredths % 100L).toInt()
    return when {
        decimals == 0 -> roundedWhole.toString()
        decimals % 10 == 0 -> "$roundedWhole.${decimals / 10}"
        else -> "$roundedWhole.${decimals.toString().padStart(2, '0')}"
    }
}

/**
 * 大金额「整数突出、小数弱化」（REQ 首页UI§15）：整元不显示 `.00`，存在角分时小数部分缩小。
 * 返回 (带千分位的整数部分, 两位小数部分?)；小数为 0 时小数部分为 null。
 */
fun splitMoney(cents: Long): Pair<String, String?> {
    val abs = kotlin.math.abs(cents)
    val intPart = "%,d".format(abs / 100)
    val dec = abs % 100
    return if (dec == 0L) intPart to null else intPart to "%02d".format(dec)
}

/**
 * 大金额展示组件：整数用大字号加粗，小数用小一号字号弱化；整元只显示整数。
 * 仅用于首页/总览等强调性大数字；账户详情与流水仍用 [formatMoney] 显示完整精确金额。
 */
@Composable
fun BigMoney(
    cents: Long,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium
) {
    if (PrivacyMode.enabled) {
        Text(
            text = PrivacyMode.maskedAmount(),
            modifier = modifier,
            style = style,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        return
    }
    val (intPart, decPart) = splitMoney(cents)
    val sign = if (cents < 0) "-" else ""
    val amount = buildAnnotatedString {
        append("$sign¥$intPart")
        if (decPart != null) {
            withStyle(SpanStyle(fontSize = style.fontSize * 0.6f)) { append(".$decPart") }
        }
    }
    Text(
        text = amount,
        modifier = modifier,
        style = style,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}
