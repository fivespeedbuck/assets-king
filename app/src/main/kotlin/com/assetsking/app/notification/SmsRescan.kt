package com.assetsking.app.notification

import android.content.Context
import android.provider.Telephony
import com.assetsking.database.LedgerRepository
import com.assetsking.database.RawNotificationEntity
import com.assetsking.ledger.SmsSenderWhitelist
import com.assetsking.usecase.NotificationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SmsRescanResult(
    val inserted: Int,
    val completed: Boolean
)

/**
 * 短信补扫（REQ 通知监听 §13/§17）：监听恢复或 App 重开后，从最后一次监听正常时间开始、
 * 最多回溯 7 天读取
 * 银行交易短信，把监听中断期间已从通知栏消失的短信补回为候选。不读短信内容以外的任何数据。
 */
object SmsRescan {
    suspend fun rescan(
        context: Context,
        repository: LedgerRepository,
        lastHealthyAt: Long = repository.lastListenerHealthyAtValue()
    ): SmsRescanResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val since = SmsSenderWhitelist.rescanSince(now, lastHealthyAt)
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val cursor = runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection,
                "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC"
            )
        }.getOrNull() ?: return@withContext SmsRescanResult(inserted = 0, completed = false)

        var inserted = 0
        cursor.use {
            while (it.moveToNext()) {
                val sender = it.getString(0)?.trim().orEmpty()
                val body = it.getString(1)?.trim().orEmpty()
                val date = it.getLong(2)
                if (!repository.isSmsSenderWhitelisted(sender)) continue
                if (body.isBlank()) continue
                // 只补能解析出金额的交易短信；验证码/营销/个人短信不进库
                if (NotificationParser.parse(body, sender).amountCents == null) continue
                repository.saveRawNotification(
                    RawNotificationEntity(
                        id = "sms:rescan:$sender:$date",
                        packageName = "sms",
                        sourceLabel = "短信补回 $sender",
                        title = sender,
                        content = body,
                        postedAt = date,
                        receivedAt = System.currentTimeMillis()
                    ),
                    updateLastReceived = false
                )
                inserted++
            }
        }
        SmsRescanResult(inserted = inserted, completed = true)
    }
}
