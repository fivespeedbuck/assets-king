package com.assetsking.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.assetsking.app.MainActivity
import com.assetsking.database.RawNotificationEntity
import com.assetsking.app.AssetsKingApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AssetsNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        startForegroundService()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

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
            try {
                val app = application as? AssetsKingApplication ?: return@launch
                val repository = app.repository
                repository.saveRawNotification(
                    RawNotificationEntity(
                        id = "${sbn.key}:${sbn.postTime}",
                        packageName = sbn.packageName,
                        sourceLabel = appLabel,
                        title = title,
                        content = content,
                        postedAt = sbn.postTime,
                        receivedAt = System.currentTimeMillis()
                    )
                )
                app.processPending.invoke()

                // 更新前台通知的净资产数据
                updateForegroundNotification(app)
            } catch (_: Exception) {
                // ponytail: 静默失败比服务崩溃好
            }
        }
    }

    override fun onDestroy() {
        isConnected = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台运行",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "资产大王后台监听通知所需"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundService() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("记账监听运行中")
            .setContentText("正在后台监听支付通知")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, notification)
    }

    private fun updateForegroundNotification(app: AssetsKingApplication) {
        serviceScope.launch {
            try {
                val repo = app.repository
                val accounts = repo.accounts.first()
                val assets = accounts.filter { it.type == "ASSET" }.sumOf { it.balanceCents }
                val debts = accounts.filter { it.type != "ASSET" }.sumOf { it.balanceCents }
                val net = assets - debts
                val txs = repo.allTransactions()
                val thisMonth = txs.filter { tx ->
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = tx.occurredAt
                    cal.get(java.util.Calendar.MONTH) == java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
                }
                val expense = thisMonth.filter { it.type == "EXPENSE" }.sumOf { it.amountCents }
                val text = "净值 ¥%.2f · 本月支出 ¥%.2f".format(net / 100.0, expense / 100.0)
                val intent = Intent(this@AssetsNotificationListenerService, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    this@AssetsNotificationListenerService, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = Notification.Builder(this@AssetsNotificationListenerService, CHANNEL_ID)
                    .setContentTitle("记账监听运行中")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(FOREGROUND_ID, notification)
            } catch (_: Exception) { }
        }
    }

    companion object {
        @Volatile
        var isConnected = false
        private const val CHANNEL_ID = "assets_king_foreground"
        private const val FOREGROUND_ID = 1
    }
}
