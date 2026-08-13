package com.assetsking.app.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.assetsking.app.AssetsKingApplication
import com.assetsking.database.RawNotificationEntity
import com.assetsking.usecase.NotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 通知读取服务。
 *
 * 这里**没有前台服务**，是刻意的：NotificationListenerService 由系统绑定并保活，
 * 本来就不需要 startForeground。原先那段 startForeground 在 Android 12+ 会抛
 * ForegroundServiceStartNotAllowedException（后台不允许起前台服务），异常连带把
 * onListenerConnected 打挂，正是「授权了却收不到通知」的元凶之一 —— 用户反馈
 * 「那条常驻通知我从没见到过」即为实锤。
 */
class AssetsNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        // App 更新、重启、系统解绑期间推的通知，系统不会补发，原先一律永久丢失。
        // 绑定成功后补扫一遍通知栏：RawNotification 主键是 "key:postTime"，
        // DAO 是 onConflict=IGNORE，同一条扫几次都只入库一次。
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        // 系统在 app 更新/重启后会断开绑定且不主动重连，这里主动要一次
        runCatching { requestRebind(componentName(this)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 自己的通知、常驻通知（音乐/下载）、分组摘要（内容重复）一律不看
        if (sbn.packageName == packageName) return
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val app = application as? AssetsKingApplication ?: return
        val repository = app.repository

        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrNull()

        // 先登记来源（不放行的也记）——设置页据此列出开关，漏配的银行一键就能打开
        repository.recordNotificationSource(sbn.packageName, appLabel)
        if (!repository.isWhitelisted(sbn.packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val content = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        ).distinct().joinToString("\n").ifBlank { sbn.notification.tickerText?.toString().orEmpty() }
        if (content.isBlank() && title.isNullOrBlank()) return

        // 白名单挡不住微信——它必须放行（要收支付通知），但聊天消息远多于付款。
        // 聊天消息不进流水：真机取证——微信支付推 VPushChannel_1，聊天走
        // message_channel_new_id 且 category=msg。聊天内容带"75元一斤"会被当成
        // 金额冲进待确认箱（用户实报），按通道拦掉，不能靠文本判断。
        if (sbn.notification.category == Notification.CATEGORY_MESSAGE) return
        if (sbn.packageName == "com.tencent.mm" && sbn.notification.channelId == "message_channel_new_id") return

        // 解析不出金额的直接不入库，否则一天几百条聊天记录白占数据库。
        if (NotificationParser.parse(content, title).amountCents == null) return

        serviceScope.launch {
            try {
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

    companion object {
        // StateFlow 而非普通 Boolean：绑定是异步完成的，UI 得能在完成那一刻自己更新，
        // 否则刚点完「重新绑定」还会继续显示「已断开」直到下次进前台
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        var isConnected: Boolean
            get() = _connected.value
            private set(value) { _connected.value = value }

        fun componentName(context: Context) =
            ComponentName(context, AssetsNotificationListenerService::class.java)

        /**
         * 已授权但未连接时把绑定要回来。
         * 装了新 APK 后系统保留授权却不重新 bind，服务会静默收不到任何东西 ——
         * 以前只能靠「设置里关掉再打开」恢复，这行代替了那个手工步骤。
         */
        fun rebindIfNeeded(context: Context) {
            if (isConnected) return
            runCatching { requestRebind(componentName(context)) }
        }
    }
}
