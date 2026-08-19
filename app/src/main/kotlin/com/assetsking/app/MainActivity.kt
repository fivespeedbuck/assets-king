package com.assetsking.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.app.ui.screen.HomeScreen
import com.assetsking.app.ui.screen.MigrationGateScreen
import com.assetsking.app.ui.screen.isListenerEnabled
import com.assetsking.database.LedgerRepository
import com.assetsking.ui.theme.AppTheme
import com.assetsking.ui.theme.AssetsKingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as AssetsKingApplication
            val model: LedgerViewModel = viewModel(
                factory = LedgerViewModel.factory(app)
            )
            val themeKey by model.themeKey.collectAsStateWithLifecycle(initialValue = null)
            // 旧版 → 重构版迁移门禁（REQ 旧功能清理 §4-8）：完成前不放行进首页
            var gate by remember { mutableStateOf<LedgerRepository.MigrationStatus?>(null) }
            LaunchedEffect(Unit) { gate = app.repository.migrationStatus() }
            AssetsKingTheme(theme = AppTheme.byKey(themeKey)) {
                when (gate) {
                    null -> {}   // 检查中
                    LedgerRepository.MigrationStatus.DONE ->
                        HomeScreen(model = model, repository = app.repository)
                    else -> MigrationGateScreen(
                        repository = app.repository,
                        onDone = { gate = LedgerRepository.MigrationStatus.DONE }
                    )
                }
            }
        }
    }

    /**
     * 装了新 APK 后系统保留通知授权、却不重新绑定监听服务，于是「授权明明开着但一条都收不到」。
     * 每次回到前台顺手把绑定要回来，代替原先只能手工「关掉再打开授权」的恢复步骤。
     */
    override fun onResume() {
        super.onResume()
        if (isListenerEnabled(this)) {
            AssetsNotificationListenerService.rebindIfNeeded(this)
            // 前台起 FGS 保活：进程不再被 vivo 当缓存清掉，监听不掉线
            AssetsNotificationListenerService.startKeepAlive(this)
        }
    }
}
