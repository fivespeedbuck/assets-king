package com.assetsking.ledger

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 内容指纹（REQ 通知监听 §12）：去空白/标点后的规范化标题+正文。
 *
 * 同一条证据以不同 id 重生（补扫重读收件箱、通知重推产生新 postTime）时指纹相同，
 * 用于跨 id 的严格去重。只做归一化不做哈希；保留小数点，避免金额边界被压成同一指纹。
 */
object ContentFingerprint {
    const val DEDUP_WINDOW_MS = 5 * 60_000L
    private val smsMirrorPackages = setOf(
        "com.android.mms.service",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.miui.smsextra",
        "com.google.android.apps.messaging"
    )

    fun of(title: String?, content: String): String =
        buildString {
            if (!title.isNullOrBlank()) append(title)
            append('\n')
            append(content)
        }.map { char ->
            when (char) {
                '。', '．' -> '.'
                else -> char
            }
        }.filter { it.isLetterOrDigit() || it == '.' }.joinToString("").lowercase()

    private fun bodyOnly(content: String): String =
        content.map { char ->
            when (char) {
                '。', '．' -> '.'
                else -> char
            }
        }.filter { it.isLetterOrDigit() || it == '.' }.joinToString("").lowercase()

    private fun isSmsMirrorPackage(packageName: String): Boolean =
        packageName == "sms" || packageName in smsMirrorPackages

    /** 短信接收器与系统短信通知是同一条短信的两个入口；它们的标题可能不同。 */
    fun isSameSmsMirrorContent(
        aPackage: String,
        aContent: String,
        aPostedAt: Long,
        bPackage: String,
        bContent: String,
        bPostedAt: Long
    ): Boolean =
        aPackage != bPackage &&
            isSmsMirrorPackage(aPackage) &&
            isSmsMirrorPackage(bPackage) &&
            bodyOnly(aContent).isNotBlank() &&
            bodyOnly(aContent) == bodyOnly(bContent) &&
            abs(aPostedAt - bPostedAt) < DEDUP_WINDOW_MS

    /**
     * 短信实时广播与短信箱补扫必须得到同一个主键。
     *
     * 两条入口拿不到共同的系统行 id，只能使用「发送方 + 完整正文 + 证据日期」生成
     * 稳定标签。银行交易短信正文通常自带交易时间/余额；日期再兜住极少数固定文案，
     * 避免隔天同文案被永久吞掉。SHA-256 只缩短并隐藏主键里的短信原文，不承担解析。
     */
    fun stableSmsEvidenceId(
        sender: String?,
        content: String,
        postedAt: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val epochDay = Instant.ofEpochMilli(postedAt)
            .atZone(zone)
            .toLocalDate()
            .toEpochDay()
        val seed = "$epochDay\n${of(sender, content)}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "sms:$digest"
    }

    /**
     * 判定两个已持久化来源是否是同一条证据重生。
     *
     * 普通 Android 通知同包不同系统 key 仍视为两次真实发布；内部 sms 来源没有稳定系统 key，
     * 因此实时接收和短信箱补扫按指纹与原始时间合并。
     */
    fun isSameEvidence(
        aPackage: String,
        aId: String,
        aFingerprint: String,
        aPostedAt: Long,
        bPackage: String,
        bId: String,
        bFingerprint: String,
        bPostedAt: Long
    ): Boolean {
        if (aFingerprint.isBlank() || aFingerprint != bFingerprint) return false
        if (aPackage == bPackage && aPackage != "sms") {
            val aKey = notificationKey(aId)
            val bKey = notificationKey(bId)
            // 同一个系统通知 key 的尾部时间戳只是重投时间；即使隔了数小时或数天，
            // 也仍是同一条证据。两个明确不同的 key 则必须保留为两次真实发布。
            if (aKey != null && bKey != null) return aKey == bKey
        }
        return abs(aPostedAt - bPostedAt) < DEDUP_WINDOW_MS
    }

    private fun notificationKey(id: String): String? {
        val separator = id.lastIndexOf(':')
        if (separator <= 0 || id.substring(separator + 1).toLongOrNull() == null) return null
        return id.substring(0, separator)
    }

}
