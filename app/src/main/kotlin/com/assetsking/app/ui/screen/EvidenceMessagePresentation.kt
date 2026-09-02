package com.assetsking.app.ui.screen

internal data class EvidenceMessagePresentation(
    val title: String?,
    val content: String?
)

internal fun evidenceMessagePresentation(
    title: String?,
    content: String
): EvidenceMessagePresentation {
    val normalizedTitle = title?.trim()?.takeIf(String::isNotEmpty)
    val normalizedContent = content.trim().takeIf(String::isNotEmpty)
    return EvidenceMessagePresentation(
        title = normalizedTitle,
        content = normalizedContent?.takeUnless { it == normalizedTitle }
    )
}
