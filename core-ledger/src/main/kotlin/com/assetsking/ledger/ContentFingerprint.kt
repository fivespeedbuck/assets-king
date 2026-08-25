package com.assetsking.ledger

import java.security.MessageDigest

/**
 * 内容指纹（REQ 通知监听 §12）：去空白/标点后的规范化标题+正文。
 *
 * 同一条证据以不同 id 重生（补扫重读收件箱、通知重推产生新 postTime）时指纹相同，
 * 用于跨 id 的严格去重。只做归一化不做哈希——原始文本可直接当指纹，无碰撞且可调试。
 */
object ContentFingerprint {
    fun of(title: String?, content: String): String =
        buildString {
            if (!title.isNullOrBlank()) append(title)
            append('\n')
            append(content)
        }.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 短信实时广播与短信箱补扫必须得到同一个主键。
     *
     * 两条入口拿不到共同的系统行 id，只能使用「发送方 + 完整正文 + 证据日期」生成
     * 稳定标签。银行交易短信正文通常自带交易时间/余额；日期再兜住极少数固定文案，
     * 避免隔天同文案被永久吞掉。SHA-256 只缩短并隐藏主键里的短信原文，不承担解析。
     */
    fun stableSmsEvidenceId(sender: String?, content: String, postedAt: Long): String {
        val epochDay = Math.floorDiv(postedAt, MILLIS_PER_DAY)
        val seed = "$epochDay\n${of(sender, content)}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "sms:$digest"
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
}
