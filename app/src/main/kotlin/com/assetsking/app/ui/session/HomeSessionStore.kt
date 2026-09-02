package com.assetsking.app.ui.session

import android.content.SharedPreferences

/**
 * 保存可以安全恢复的首页导航上下文。
 *
 * 这些值只保存本地对象 ID 和页面位置，不保存账户余额、通知原文或其他账务数据。
 * 这样即使 OriginOS 重新创建 MainActivity，HomeScreen 也能回到用户离开前的页面。
 */
internal data class HomeSessionSnapshot(
    val selectedTab: Int = 0,
    val previousTab: Int? = null,
    val showPendingBox: Boolean = false,
    val showEditor: Boolean = false,
    val showBills: Boolean = false,
    val showReimbursement: Boolean = false,
    val showReconciliation: Boolean = false,
    val editorPendingNotificationId: String? = null,
    val editorTransactionId: String? = null,
    val editorInitialLoanPlanId: String? = null,
    val editingAccountId: String? = null,
    val accountDetailId: String? = null,
    val detailTransactionId: String? = null,
    val addingAccountType: String? = null
)

internal class HomeSessionStore(private val prefs: SharedPreferences) {
    fun load(): HomeSessionSnapshot = HomeSessionSnapshot(
        selectedTab = prefs.getInt(KEY_SELECTED_TAB, 0).coerceIn(0, 4),
        previousTab = prefs.getInt(KEY_PREVIOUS_TAB, NO_TAB).takeUnless { it == NO_TAB },
        showPendingBox = prefs.getBoolean(KEY_SHOW_PENDING_BOX, false),
        showEditor = prefs.getBoolean(KEY_SHOW_EDITOR, false),
        showBills = prefs.getBoolean(KEY_SHOW_BILLS, false),
        showReimbursement = prefs.getBoolean(KEY_SHOW_REIMBURSEMENT, false),
        showReconciliation = prefs.getBoolean(KEY_SHOW_RECONCILIATION, false),
        editorPendingNotificationId = nullable(KEY_EDITOR_PENDING_ID),
        editorTransactionId = nullable(KEY_EDITOR_TRANSACTION_ID),
        editorInitialLoanPlanId = nullable(KEY_EDITOR_LOAN_PLAN_ID),
        editingAccountId = nullable(KEY_EDITING_ACCOUNT_ID),
        accountDetailId = nullable(KEY_ACCOUNT_DETAIL_ID),
        detailTransactionId = nullable(KEY_DETAIL_TRANSACTION_ID),
        addingAccountType = nullable(KEY_ADDING_ACCOUNT_TYPE)
    )

    fun save(snapshot: HomeSessionSnapshot) {
        editor(snapshot).apply()
    }

    /** Activity 退到后台时同步刷盘，避免系统紧接着回收进程而丢失最后一次页面位置。 */
    fun flush(snapshot: HomeSessionSnapshot): Boolean = editor(snapshot).commit()

    private fun editor(snapshot: HomeSessionSnapshot): SharedPreferences.Editor =
        prefs.edit()
            .putInt(KEY_SELECTED_TAB, snapshot.selectedTab.coerceIn(0, 4))
            .putInt(KEY_PREVIOUS_TAB, snapshot.previousTab ?: NO_TAB)
            .putBoolean(KEY_SHOW_PENDING_BOX, snapshot.showPendingBox)
            .putBoolean(KEY_SHOW_EDITOR, snapshot.showEditor)
            .putBoolean(KEY_SHOW_BILLS, snapshot.showBills)
            .putBoolean(KEY_SHOW_REIMBURSEMENT, snapshot.showReimbursement)
            .putBoolean(KEY_SHOW_RECONCILIATION, snapshot.showReconciliation)
            .putNullable(KEY_EDITOR_PENDING_ID, snapshot.editorPendingNotificationId)
            .putNullable(KEY_EDITOR_TRANSACTION_ID, snapshot.editorTransactionId)
            .putNullable(KEY_EDITOR_LOAN_PLAN_ID, snapshot.editorInitialLoanPlanId)
            .putNullable(KEY_EDITING_ACCOUNT_ID, snapshot.editingAccountId)
            .putNullable(KEY_ACCOUNT_DETAIL_ID, snapshot.accountDetailId)
            .putNullable(KEY_DETAIL_TRANSACTION_ID, snapshot.detailTransactionId)
            .putNullable(KEY_ADDING_ACCOUNT_TYPE, snapshot.addingAccountType)

    private fun nullable(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotBlank() }

    private fun SharedPreferences.Editor.putNullable(key: String, value: String?): SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val NO_TAB = -1
        const val KEY_SELECTED_TAB = "selected_tab"
        const val KEY_PREVIOUS_TAB = "previous_tab"
        const val KEY_SHOW_PENDING_BOX = "show_pending_box"
        const val KEY_SHOW_EDITOR = "show_editor"
        const val KEY_SHOW_BILLS = "show_bills"
        const val KEY_SHOW_REIMBURSEMENT = "show_reimbursement"
        const val KEY_SHOW_RECONCILIATION = "show_reconciliation"
        const val KEY_EDITOR_PENDING_ID = "editor_pending_notification_id"
        const val KEY_EDITOR_TRANSACTION_ID = "editor_transaction_id"
        const val KEY_EDITOR_LOAN_PLAN_ID = "editor_initial_loan_plan_id"
        const val KEY_EDITING_ACCOUNT_ID = "editing_account_id"
        const val KEY_ACCOUNT_DETAIL_ID = "account_detail_id"
        const val KEY_DETAIL_TRANSACTION_ID = "detail_transaction_id"
        const val KEY_ADDING_ACCOUNT_TYPE = "adding_account_type"
    }
}
