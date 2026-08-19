package com.assetsking.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking

/**
 * 每周自动备份（REQ 备份§2-3）：保留最近 13 份（约三个月），手动备份永不自动删除。
 * 备份密码未设置时跳过（备份只属于主动开启备份的用户）。
 */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as? AssetsKingApplication ?: return Result.success()
        val ok = runBlocking { app.repository.backupNow(manual = false) }
        return if (ok) Result.success() else Result.success() // 密码未设/IO 失败都静默，不反复重试打扰用户
    }
}
