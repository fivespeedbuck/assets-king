package com.assetsking.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.assetsking.app.AssetsKingApplication
import com.assetsking.app.MainActivity
import com.assetsking.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 待确认通知（P0）。
 *
 * 合并完成后只发一条「待确认 N 笔」：同一笔证据的微信/支付宝/银行短信在约 10 秒内先后到达，
 * 这里用 10 秒防抖——每来一条新证据就重置计时，最后一次证据后 10 秒才弹通知，避免同笔多推。
 * 锁屏隐私：内容设为 PRIVATE，锁屏不显示金额/商户/账户；点击进入首页待确认箱。
 */
object PendingNotifier {
    private const val CHANNEL_PENDING = "assets_king_pending"
    private const val NOTIF_ID = 2
    private const val DEBOUNCE_MS = 10_000L

    private var debounceJob: Job? = null

    /** 每条新证据到达后调用：10 秒防抖，静默期间重来就重置计时。 */
    fun scheduleDebounced(context: Context, scope: CoroutineScope) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val app = context.applicationContext as? AssetsKingApplication ?: return@launch
            val count = runCatching { app.repository.pendingNotifications.first().size }.getOrDefault(0)
            if (count > 0) notify(context, count)
        }
    }

    private fun notify(context: Context, count: Int) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PENDING, "待确认账目", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val tap = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = Notification.Builder(context, CHANNEL_PENDING)
                .setContentTitle("资产大王")
                .setContentText("有 $count 笔账目待确认")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE) // 锁屏隐藏内容
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }
    }
}
