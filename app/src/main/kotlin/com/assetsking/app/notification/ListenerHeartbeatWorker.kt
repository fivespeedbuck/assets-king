package com.assetsking.app.notification

import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.assetsking.app.ui.screen.isListenerEnabled
import kotlinx.coroutines.runBlocking

/**
 * 15 分钟心跳，监听保活的第二道闸（FGS 是第一道）。
 *
 * vivo 杀掉进程后，JobScheduler 里登记的周期任务还在（single-cleaner 只杀进程
 * 不销任务），到点系统会把进程重新拉起来执行本 Worker —— 新进程里 isConnected
 * 必为 false，requestRebind 把监听绑定要回来，漏探窗口 ≤15 分钟。
 * 这里不起 FGS：后台 startForegroundService 在 Android 12+ 必抛，起了也白起。
 */
class ListenerHeartbeatWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // 待确认防丢补发（与监听状态无关）
        runBlocking { runCatching { PendingNotifier.ensureNotified(applicationContext) } }
        if (isListenerEnabled(applicationContext) && !AssetsNotificationListenerService.isConnected) {
            runCatching {
                NotificationListenerService.requestRebind(
                    AssetsNotificationListenerService.componentName(applicationContext)
                )
            }
        }
        return Result.success()
    }
}
