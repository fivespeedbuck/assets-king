package com.assetsking.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.app.notification.ListenerHeartbeatReceiver
import com.assetsking.app.notification.PendingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自启动 — 触发 NotificationListenerService 重新绑定。
 * Android 系统会在开机完成后自动绑定声明了 BIND_NOTIFICATION_LISTENER_SERVICE 的服务，
 * 此 Receiver 主要用于确保 Application 被初始化。vivo 上需要用户开「自启动」权限
 * 才会收到这个广播；boot 广播带短暂的后台 FGS 豁免窗口，起不来会被吞掉，无害。
 * 闹钟不跨重启存活，这里重挂 60 秒心跳。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ListenerHeartbeatReceiver.schedule(context)
            AssetsNotificationListenerService.startKeepAlive(context)
            // 待确认防丢补发：开机时若有未通知的待确认，补一条
            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    PendingNotifier.ensureNotified(context)
                } catch (_: Exception) {
                    // ponytail: 补发失败不阻断开机流程
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
