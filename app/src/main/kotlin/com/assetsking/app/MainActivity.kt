package com.assetsking.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.app.ui.screen.HomeScreen
import com.assetsking.app.ui.screen.isListenerEnabled
import com.assetsking.ui.theme.AssetsKingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssetsKingTheme {
                val app = application as AssetsKingApplication
                val model: LedgerViewModel = viewModel(
                    factory = LedgerViewModel.factory(app)
                )
                HomeScreen(model = model, repository = app.repository)
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
