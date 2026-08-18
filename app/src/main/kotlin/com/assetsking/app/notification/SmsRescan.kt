package com.assetsking.app.notification

import android.content.Context
import android.provider.Telephony
import com.assetsking.database.LedgerRepository
import com.assetsking.database.RawNotificationEntity
import com.assetsking.usecase.NotificationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 常见银行短信发送短号（种子，后续可在通知来源里增删）。 */
val DEFAULT_BANK_SMS_SENDERS: Set<String> = setOf(
    "95555", "95533", "95588", "95599", "95566", "95528", "95568",
    "95595", "95558", "95511", "95561", "95577", "95508", "95574",
    "95559", "95501", "95580", "95505"
)

/**
 * 短信补扫（REQ 通知监听 §13/§17）：监听恢复或 App 重开后，从短信收件箱读最近 7 天的
 * 银行交易短信，把监听中断期间已从通知栏消失的短信补回为候选。不读短信内容以外的任何数据。
 */
object SmsRescan {
    suspend fun rescan(context: Context, repository: LedgerRepository): Int = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val cursor = runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection,
                "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC"
            )
        }.getOrNull() ?: return@withContext 0

        var inserted = 0
        cursor.use {
            while (it.moveToNext()) {
                val sender = it.getString(0)?.trim().orEmpty()
                val body = it.getString(1)?.trim().orEmpty()
                val date = it.getLong(2)
                if (!isBankSender(sender)) continue
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
        inserted
    }

    private fun isBankSender(sender: String): Boolean =
        DEFAULT_BANK_SMS_SENDERS.any { sender.startsWith(it) }
}
