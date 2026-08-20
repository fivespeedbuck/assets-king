package com.assetsking.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.assetsking.app.AssetsKingApplication
import com.assetsking.database.RawNotificationEntity
import com.assetsking.usecase.NotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 短信直读兜底通道（决策 1）。
 *
 * 通知监听掉线时，银行扣款短信仍会触发 SMS_RECEIVED 广播——即使进程被 vivo 杀，
 * 这条受保护广播也会把进程重新拉起来执行本接收器。它是与通知监听完全独立的
 * 第二条采集通道，专门补监听中断窗口（划掉 App / 锁屏数小时）里的漏收。
 *
 * 与通知监听的去重：同一条短信两条通道各收一次时，由 [ProcessPendingUseCase]
 * 的判重（同额 + 同向 + 5 分钟 + 商户不冲突）合并成一条，不会记两遍。
 * 这里用确定性 id（发件人 + 送达时间），Room 的 OnConflict.IGNORE 也兜一道。
 */
class SmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: return
        if (messages.isEmpty()) return

        // 长短信会被拆成多段，拼起来才是完整原文
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (body.isBlank()) return
        val sender = messages.firstOrNull()?.originatingAddress?.trim().orEmpty()

        val app = context.applicationContext as? AssetsKingApplication ?: return
        val repository = app.repository
        // 与历史补扫共用同一持久化白名单；陌生发送方在解析前直接丢弃。
        if (!repository.isSmsSenderWhitelisted(sender)) return

        // 只落库能解析出金额的短信：验证码 / 营销 / 个人短信不进库，避免冲垮待确认箱。
        // 与通知监听共用同一套两层否决（硬否决 + 软否决），不是新写一套判断。
        if (NotificationParser.parse(body, sender).amountCents == null) return

        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        // goAsync：把接收器生命周期从 onReceive 结束延长到落库完成，防进程刚收到就又被杀
        val pendingResult = goAsync()
        scope.launch {
            try {
                repository.saveRawNotification(
                    RawNotificationEntity(
                        id = "sms:$sender:$timestamp",
                        packageName = "sms",
                        sourceLabel = sender,
                        title = sender,
                        content = body,
                        postedAt = timestamp,
                        receivedAt = System.currentTimeMillis()
                    )
                )
                app.processPending.invoke()
                PendingNotifier.scheduleDebounced(context.applicationContext, scope)
            } catch (_: Exception) {
                // ponytail: 静默失败比广播崩溃好
            } finally {
                pendingResult.finish()
            }
        }
    }
}
