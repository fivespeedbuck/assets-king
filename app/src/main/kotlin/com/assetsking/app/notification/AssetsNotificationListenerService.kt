package com.assetsking.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.assetsking.database.RawNotificationEntity
import com.assetsking.app.AssetsKingApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 系统绑定的通知入口。第一版先保留原始通知接入点，解析和入账由后续模块负责。
 */
class AssetsNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val content = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        ).distinct().joinToString("\n").ifBlank { sbn.notification.tickerText?.toString().orEmpty() }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrNull()

        serviceScope.launch {
            val repository = (application as? AssetsKingApplication)?.repository ?: return@launch
            repository.saveRawNotification(
                RawNotificationEntity(
                    id = "${sbn.key}:${sbn.postTime}:${System.nanoTime()}",
                    packageName = sbn.packageName,
                    sourceLabel = appLabel,
                    title = title,
                    content = content,
                    postedAt = sbn.postTime,
                    receivedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
