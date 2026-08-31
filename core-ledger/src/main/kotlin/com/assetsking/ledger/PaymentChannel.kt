package com.assetsking.ledger

import java.util.Locale

/** 用户可见名称来自不同来源时，对同一支付渠道的稳定别名做等价判断。 */
object PaymentChannel {
    private val knownNames = setOf("微信", "微信支付", "财付通", "财付通-微信支付", "支付宝", "云闪付", "银行卡", "现金")

    fun isKnown(value: String?): Boolean = value?.trim() in knownNames

    fun equivalent(left: String?, right: String?): Boolean {
        val a = canonical(left)
        val b = canonical(right)
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a == b
    }

    /**
     * 用户显式选择退款原消费时，兼容升级前没有保存渠道的历史流水。
     * 退款本身仍必须有渠道；已有渠道的原消费仍要求严格等价。
     */
    fun refundSourceCompatible(sourceChannel: String?, refundChannel: String?): Boolean {
        val refund = canonical(refundChannel) ?: return false
        val source = canonical(sourceChannel)
        return source == null || source == refund
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

/** 下单平台匹配：旧流水可能没有保存平台，已保存的平台必须严格一致。 */
object OrderPlatform {
    private val knownNames = setOf("美团", "淘宝", "京东", "拼多多", "淘宝闪购", "饿了么", "抖音")

    fun isKnown(value: String?): Boolean = value?.trim() in knownNames

    fun refundSourceCompatible(sourcePlatform: String?, refundPlatform: String?): Boolean {
        val source = canonical(sourcePlatform)
        val refund = canonical(refundPlatform)
        return source == null || (refund != null && source == refund)
    }

    private fun canonical(value: String?): String? =
        value?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "")?.takeIf { it.isNotEmpty() }
}
