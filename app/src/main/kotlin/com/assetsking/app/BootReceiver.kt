package com.assetsking.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启动 — 触发 NotificationListenerService 重新绑定。
 * Android 系统会在开机完成后自动绑定声明了 BIND_NOTIFICATION_LISTENER_SERVICE 的服务，
 * 此 Receiver 主要用于确保 Application 被初始化。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // NotificationListenerService 由系统自动绑定，这里仅触发 Application 初始化
            // 确保数据库和依赖已就绪
        }
    }
}
