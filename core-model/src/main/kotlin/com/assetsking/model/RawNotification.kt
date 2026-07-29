package com.assetsking.model

enum class NotificationProcessingStatus { NEW, PARSED, PENDING_CONFIRMATION, IGNORED, LINKED }

data class RawNotification(
    val id: String,
    val packageName: String,
    val sourceLabel: String?,
    val title: String?,
    val content: String,
    val postedAt: Long,
    val receivedAt: Long,
    val status: NotificationProcessingStatus = NotificationProcessingStatus.NEW
)
