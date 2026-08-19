package com.assetsking.ui.format

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import java.text.NumberFormat
import java.util.Locale

private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.CHINA)

fun formatMoney(cents: Long): String = currencyFormat.format(cents / 100.0)

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
    val (intPart, decPart) = splitMoney(cents)
    val sign = if (cents < 0) "-" else ""
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text("$sign¥$intPart", style = style, fontWeight = FontWeight.Bold, color = color)
        if (decPart != null) {
            Text(
                ".$decPart",
                style = style.copy(fontSize = style.fontSize * 0.6f),
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
