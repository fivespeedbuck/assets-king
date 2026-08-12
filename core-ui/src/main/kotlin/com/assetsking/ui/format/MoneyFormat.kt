package com.assetsking.ui.format

import java.text.NumberFormat
import java.util.Locale

private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.CHINA)

fun formatMoney(cents: Long): String = currencyFormat.format(cents / 100.0)
