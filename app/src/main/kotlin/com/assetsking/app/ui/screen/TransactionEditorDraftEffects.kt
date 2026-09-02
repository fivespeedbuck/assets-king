package com.assetsking.app.ui.screen

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.assetsking.database.LedgerRepository
import com.assetsking.usecase.ParsedNotification
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 将统一编辑器当前状态做短防抖后保存。单独的窄 Composable 边界可以让 K2/FIR
 * 分析持久化协程，而不是把它和编辑器所有字段的条件分支放在同一个大函数里。
 */
@Composable
internal fun PersistTransactionEditorDraftEffect(
    store: TransactionEditorDraftStore,
    key: String,
    generation: Long,
    enabled: Boolean,
    draft: TransactionEditorDraft
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestDraft by rememberUpdatedState(draft)
    val latestEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(key, generation, enabled, draft) {
        if (!enabled) return@LaunchedEffect
        delay(250)
        if (latestEnabled) store.save(key, generation, draft)
    }

    // 切到其他 App 后 OriginOS 可能很快回收 Activity；ON_STOP 时同步刷盘，避免只依赖防抖协程。
    DisposableEffect(lifecycleOwner, store, key, generation) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && latestEnabled) {
                store.flush(key, generation, latestDraft)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** 记录待确认编辑器入口时的不可变解析/推断基线，不读取延迟后的可编辑状态。 */
@Composable
internal fun RecordPendingPrefillBaselineEffect(
    notificationId: String?,
    entryDraft: TransactionEditorDraft?,
    restoredFromSavedDraft: Boolean,
    parsed: ParsedNotification?,
    repository: LedgerRepository
) {
    LaunchedEffect(notificationId, entryDraft, restoredFromSavedDraft) {
        val id = notificationId ?: return@LaunchedEffect
        val payload = JSONObject()
            .put("schemaVersion", 2)
            .put("capturedAt", System.currentTimeMillis())
            .put("captureReason", "pending_editor_entry_baseline")
            .put("restoredFromSavedDraft", restoredFromSavedDraft)
            .put("entryDraft", entryDraft?.toJson() ?: JSONObject.NULL)
            .put("parsedAmountCents", parsed?.amountCents ?: JSONObject.NULL)
            .put("parsedMerchant", parsed?.merchant ?: JSONObject.NULL)
            .put("parsedIsExpense", parsed?.isExpense ?: JSONObject.NULL)
            .put("parsedIsRefund", parsed?.isRefund ?: JSONObject.NULL)
            .put("parsedBankHint", parsed?.bankHint ?: JSONObject.NULL)
            .put("parsedCardTail", parsed?.cardTail ?: JSONObject.NULL)
            .put("parsedBalanceCents", parsed?.balanceCents ?: JSONObject.NULL)
            .put("parsedPaymentChannel", parsed?.paymentChannel ?: JSONObject.NULL)
        try {
            repository.recordPendingPrefillBaseline(id, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 预填快照是分析旁路，暂时不可写时不影响用户确认流水。
        }
    }
}
