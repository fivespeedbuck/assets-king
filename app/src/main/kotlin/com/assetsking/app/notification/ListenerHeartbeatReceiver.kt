package com.assetsking.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.assetsking.app.ui.screen.isListenerEnabled

/**
 * 60 秒心跳（AlarmManager 周期闹钟的下限就是 60s，30s 会被系统限流）。
 *
 * 划掉 App 后 vivo 会杀进程，但闹钟还在 —— 到点系统拉起进程执行本 Receiver，
 * 进程一起来 Android 会自动重绑通知监听（requestRebind 再补一道）。
 * 监听活着时每次触发只读一个标志位，开销可忽略。
 * WorkManager 的 15 分钟周期任务是第二道兜底（防闹钟被省电策略吞掉）。
 */
class ListenerHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isListenerEnabled(context)) return
        if (!AssetsNotificationListenerService.isConnected) {
            runCatching {
                NotificationListenerService.requestRebind(
                    AssetsNotificationListenerService.componentName(context)
                )
            }
        }
    }

    companion object {
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, ListenerHeartbeatReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, 60_000, 60_000, pi)
        }
    }
}
