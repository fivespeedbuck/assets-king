package com.assetsking.ledger

import java.util.Locale

/** 用户可见名称来自不同来源时，对同一支付渠道的稳定别名做等价判断。 */
object PaymentChannel {
    fun equivalent(left: String?, right: String?): Boolean {
        val a = canonical(left)
        val b = canonical(right)
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a == b
    }

    private fun canonical(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "")
            ?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            normalized == "微信" || normalized == "微信支付" || normalized == "财付通" ||
                normalized == "财付通-微信支付" -> "wechat"
            normalized == "支付宝" -> "alipay"
            normalized == "云闪付" -> "unionpay"
            else -> normalized
        }
    }
}
