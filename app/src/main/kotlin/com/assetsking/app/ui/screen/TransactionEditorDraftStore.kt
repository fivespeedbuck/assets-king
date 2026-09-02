package com.assetsking.app.ui.screen

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 编辑器草稿只存放在应用私有 SharedPreferences 中，用于 Activity/任务被重建时恢复未提交输入。
 * 正式入账前不会把草稿当成账务数据；保存、确认或取消后由编辑器清理对应 key。
 */
internal data class TransactionEditorDraft(
    val submissionId: String? = null,
    val kind: String? = null,
    val directionChosen: Boolean? = null,
    val incomeSub: String? = null,
    val repaySub: String? = null,
    val lendingSub: String? = null,
    val amountExpr: String? = null,
    val occurredAt: Long? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val channel: String? = null,
    val customChannelSelected: Boolean? = null,
    val orderPlatform: String? = null,
    val customOrderPlatformSelected: Boolean? = null,
    val merchantText: String? = null,
    val categoryId: String? = null,
    val necessity: Boolean? = null,
    val isReimbursable: Boolean? = null,
    val refundOfId: String? = null,
    val note: String? = null,
    val balanceResolution: String? = null,
    val loanPlanId: String? = null,
    val principalExpr: String? = null,
    val interestExpr: String? = null,
    val feeExpr: String? = null,
    val lendingPlanId: String? = null,
    val transferFeeExpr: String? = null,
    val expenseIds: List<String> = emptyList(),
    val reimbursementSelectionTouched: Boolean? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .putNullable("submissionId", submissionId)
        .putNullable("kind", kind)
        .putNullable("directionChosen", directionChosen)
        .putNullable("incomeSub", incomeSub)
        .putNullable("repaySub", repaySub)
        .putNullable("lendingSub", lendingSub)
        .putNullable("amountExpr", amountExpr)
        .putNullable("occurredAt", occurredAt)
        .putNullable("accountId", accountId)
        .putNullable("toAccountId", toAccountId)
        .putNullable("channel", channel)
        .putNullable("customChannelSelected", customChannelSelected)
        .putNullable("orderPlatform", orderPlatform)
        .putNullable("customOrderPlatformSelected", customOrderPlatformSelected)
        .putNullable("merchantText", merchantText)
        .putNullable("categoryId", categoryId)
        .putNullable("necessity", necessity)
        .putNullable("isReimbursable", isReimbursable)
        .putNullable("refundOfId", refundOfId)
        .putNullable("note", note)
        .putNullable("balanceResolution", balanceResolution)
        .putNullable("loanPlanId", loanPlanId)
        .putNullable("principalExpr", principalExpr)
        .putNullable("interestExpr", interestExpr)
        .putNullable("feeExpr", feeExpr)
        .putNullable("lendingPlanId", lendingPlanId)
        .putNullable("transferFeeExpr", transferFeeExpr)
        .put("expenseIds", JSONArray(expenseIds))
        .putNullable("reimbursementSelectionTouched", reimbursementSelectionTouched)

    companion object {
        fun fromJson(json: JSONObject): TransactionEditorDraft = TransactionEditorDraft(
            submissionId = json.optNullableString("submissionId"),
            kind = json.optNullableString("kind"),
            directionChosen = json.optNullableBoolean("directionChosen"),
            incomeSub = json.optNullableString("incomeSub"),
            repaySub = json.optNullableString("repaySub"),
            lendingSub = json.optNullableString("lendingSub"),
            amountExpr = json.optNullableString("amountExpr"),
            occurredAt = json.optNullableLong("occurredAt"),
            accountId = json.optNullableString("accountId"),
            toAccountId = json.optNullableString("toAccountId"),
            channel = json.optNullableString("channel"),
            customChannelSelected = json.optNullableBoolean("customChannelSelected"),
            orderPlatform = json.optNullableString("orderPlatform"),
            customOrderPlatformSelected = json.optNullableBoolean("customOrderPlatformSelected"),
            merchantText = json.optNullableString("merchantText"),
            categoryId = json.optNullableString("categoryId"),
            necessity = json.optNullableBoolean("necessity"),
            isReimbursable = json.optNullableBoolean("isReimbursable"),
            refundOfId = json.optNullableString("refundOfId"),
            note = json.optNullableString("note"),
            balanceResolution = json.optNullableString("balanceResolution"),
            loanPlanId = json.optNullableString("loanPlanId"),
            principalExpr = json.optNullableString("principalExpr"),
            interestExpr = json.optNullableString("interestExpr"),
            feeExpr = json.optNullableString("feeExpr"),
            lendingPlanId = json.optNullableString("lendingPlanId"),
            transferFeeExpr = json.optNullableString("transferFeeExpr"),
            expenseIds = json.optJSONArray("expenseIds")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.orEmpty(),
            reimbursementSelectionTouched = json.optNullableBoolean("reimbursementSelectionTouched")
        )
    }
}

internal class TransactionEditorDraftStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(key: String): TransactionEditorDraft? =
        prefs.getString(keyFor(key), null)?.let { raw ->
            runCatching { TransactionEditorDraft.fromJson(JSONObject(raw)) }.getOrNull()
        }

    /** 为一次编辑会话分配代次；清理后，旧协程持有的代次不能再写回同一个草稿。 */
    fun openSession(key: String): Long = synchronized(PROCESS_SESSION_LOCK) {
        val next = prefs.getLong(generationKeyFor(key), 0L) + 1L
        prefs.edit().putLong(generationKeyFor(key), next).commit()
        next
    }

    fun save(key: String, generation: Long, draft: TransactionEditorDraft): Boolean = synchronized(PROCESS_SESSION_LOCK) {
        if (prefs.getLong(generationKeyFor(key), 0L) != generation) return@synchronized false
        prefs.edit().putString(keyFor(key), draft.toJson().toString()).apply()
        true
    }

    /**
     * Activity 退到后台时同步落盘。草稿 JSON 很小；这里优先保证进程随后被系统回收时
     * 用户已经输入的表单内容不会停留在尚未刷盘的异步 SharedPreferences 队列中。
     */
    fun flush(key: String, generation: Long, draft: TransactionEditorDraft): Boolean = synchronized(PROCESS_SESSION_LOCK) {
        if (prefs.getLong(generationKeyFor(key), 0L) != generation) return@synchronized false
        prefs.edit().putString(keyFor(key), draft.toJson().toString()).commit()
    }

    /** 原子失效当前代次并同步清除；已启动的防抖协程和 ON_STOP observer 都无法复活草稿。 */
    fun clearAndInvalidate(key: String, generation: Long): Boolean = synchronized(PROCESS_SESSION_LOCK) {
        if (prefs.getLong(generationKeyFor(key), 0L) != generation) return@synchronized false
        prefs.edit()
            .putLong(generationKeyFor(key), generation + 1L)
            .remove(keyFor(key))
            .commit()
    }

    fun clear(key: String) {
        synchronized(PROCESS_SESSION_LOCK) {
            val next = prefs.getLong(generationKeyFor(key), 0L) + 1L
            prefs.edit().putLong(generationKeyFor(key), next).remove(keyFor(key)).commit()
        }
    }

    private fun keyFor(key: String): String = KEY_PREFIX + key
    private fun generationKeyFor(key: String): String = GENERATION_PREFIX + key

    private companion object {
        const val PREFS_NAME = "transaction_editor_drafts"
        const val KEY_PREFIX = "draft:"
        const val GENERATION_PREFIX = "generation:"
        val PROCESS_SESSION_LOCK = Any()
    }
}

internal fun transactionEditorDraftKey(
    pendingNotificationId: String?,
    transactionId: String?,
    initialLoanPlanId: String?
): String = when {
    pendingNotificationId != null -> "pending:$pendingNotificationId"
    transactionId != null -> "transaction:$transactionId"
    initialLoanPlanId != null -> "loan-payment:$initialLoanPlanId"
    else -> "manual"
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

private fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)
