package com.assetsking.ui.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

fun formatTime(time: Long): String = dateFormat.format(Date(time))
