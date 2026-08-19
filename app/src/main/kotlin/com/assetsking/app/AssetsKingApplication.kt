package com.assetsking.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.assetsking.app.notification.ListenerHeartbeatReceiver
import com.assetsking.app.notification.ListenerHeartbeatWorker
import com.assetsking.database.AssetsKingDatabase
import com.assetsking.database.LedgerRepository
import com.assetsking.usecase.AddAccountUseCase
import com.assetsking.usecase.GetV5MetricsUseCase
import com.assetsking.usecase.ProcessPendingUseCase
import com.assetsking.usecase.RecordTransactionUseCase
import com.assetsking.usecase.RecordTransferUseCase
import com.assetsking.usecase.SeedAccountsUseCase
import com.assetsking.usecase.SpendPatternsUseCase
import com.assetsking.usecase.UpdateCategoryUseCase
import java.util.concurrent.TimeUnit

class AssetsKingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 监听保活：60 秒闹钟心跳（第一道）+ 15 分钟 WorkManager（兜底）。
        // KEEP：已存在同名单个周期任务就不重建，避免每次开 App 重置计时。
        ListenerHeartbeatReceiver.schedule(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "listener-heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ListenerHeartbeatWorker>(15, TimeUnit.MINUTES).build()
        )
        // 每周自动备份（REQ 备份§2）：保留最近 13 份
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "weekly-backup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
        )
    }

    val database by lazy { AssetsKingDatabase.get(this) }
    val repository by lazy { LedgerRepository(this, database, getSharedPreferences("app_prefs", MODE_PRIVATE)) }

    val seedAccounts by lazy { SeedAccountsUseCase(repository) }
    val recordTransaction by lazy { RecordTransactionUseCase(repository) }
    val recordTransfer by lazy { RecordTransferUseCase(repository) }
    val addAccount by lazy { AddAccountUseCase(repository) }
    val updateCategory by lazy { UpdateCategoryUseCase(repository) }
    val getV5Metrics by lazy { GetV5MetricsUseCase(repository) }
    val processPending by lazy { ProcessPendingUseCase(repository) }
    val spendPatterns by lazy { SpendPatternsUseCase(repository) }
}
