package com.assetsking.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.assetsking.database.LedgerRepository
import com.assetsking.model.AccountType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AssetsKingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, com.assetsking.app.R.layout.assets_king_widget)
        val clickIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(com.assetsking.app.R.id.widget_title, pendingIntent)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as AssetsKingApplication
                val repo = app.repository
                val accounts = repo.accounts.let { it.first() }
                val receivableAccountIds = repo.lendingPlans.first().mapTo(hashSetOf()) { it.receivableAccountId }
                val assets = accounts.filter {
                    it.type == AccountType.ASSET.name && it.id !in receivableAccountIds
                }.sumOf { it.balanceCents }
                val debts = accounts.filter { it.type != AccountType.ASSET.name }.sumOf { it.balanceCents }
                val netWorth = assets - debts
                val txs = repo.allTransactions()
                val thisMonth = txs.filter { tx ->
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = tx.occurredAt
                    cal.get(java.util.Calendar.MONTH) == java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
                }
                val expense = thisMonth.filter { it.type == "EXPENSE" }.sumOf { it.amountCents }
                val income = thisMonth.filter { it.type == "INCOME" || it.type == "REFUND" }.sumOf { it.amountCents }

                val netWorthStr = "¥%.2f".format(netWorth / 100.0)
                val summaryStr = "本月 收 ¥%.2f  支 ¥%.2f".format(income / 100.0, expense / 100.0)

                views.setTextViewText(com.assetsking.app.R.id.widget_net_worth, netWorthStr)
                views.setTextViewText(com.assetsking.app.R.id.widget_summary, summaryStr)
                manager.updateAppWidget(widgetId, views)
            } catch (_: Exception) { }
        }
    }
}
