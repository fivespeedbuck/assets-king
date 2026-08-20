package com.assetsking.ledger

/** Shared rules for the real-time SMS receiver and inbox rescan. */
object SmsSenderWhitelist {
    const val MAX_RESCAN_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Common bank short codes; the persisted set can replace these defaults. */
    val defaults: Set<String> = setOf(
        "95555", "95533", "95588", "95599", "95566", "95528", "95568",
        "95595", "95558", "95511", "95561", "95577", "95508", "95574",
        "95559", "95501", "95580", "95505"
    )

    /** Entries are prefixes so carriers that append routing digits still match. */
    fun isAllowed(sender: String, allowed: Set<String>): Boolean {
        val normalizedSender = sender.trim()
        return normalizedSender.isNotEmpty() && allowed.any { prefix ->
            prefix.trim().isNotEmpty() && normalizedSender.startsWith(prefix.trim())
        }
    }

    fun rescanSince(now: Long, lastHealthyAt: Long): Long =
        lastHealthyAt.coerceIn(now - MAX_RESCAN_AGE_MS, now)
}
