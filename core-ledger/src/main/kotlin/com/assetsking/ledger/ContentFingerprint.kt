package com.assetsking.ledger

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
}
