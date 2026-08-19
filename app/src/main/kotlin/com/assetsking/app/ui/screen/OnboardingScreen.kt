package com.assetsking.app.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.assetsking.ui.component.GlassCard

/**
 * 首次配置引导（REQ 监听§15）：一次性引导开启通知使用权、短信读取、常驻通知、忽略电池优化。
 * 配置完成（或用户主动跳过）后写 onboarding_done，正常使用不再反复要求绑定。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(isListenerEnabled(context)) }
    var smsGranted by remember { mutableStateOf(hasSmsPermission(context)) }
    var notifGranted by remember { mutableStateOf(hasPostNotification(context)) }
    var batteryIgnored by remember { mutableStateOf(isBatteryIgnored(context)) }

    // 从系统设置页返回（ON_RESUME）时重读各权限真实状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                listenerEnabled = isListenerEnabled(context)
                smsGranted = hasSmsPermission(context)
                notifGranted = hasPostNotification(context)
                batteryIgnored = isBatteryIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val coreReady = listenerEnabled && smsGranted

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("首次配置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "资产大王靠自动监听银行短信和支付通知帮你记账。首次使用请开启以下权限，配置完成后不会再反复要求。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            OnboardingRow(
                title = "通知使用权",
                desc = "读取银行/支付通知，自动记账的核心",
                granted = listenerEnabled,
                actionLabel = "去开启"
            ) { openListenerSettings(context) }

            OnboardingRow(
                title = "短信读取",
                desc = "监听掉线时从收件箱补收银行短信",
                granted = smsGranted,
                actionLabel = "去授权"
            ) {
                smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
            }

            OnboardingRow(
                title = "常驻通知",
                desc = "锁屏显示监听状态，保持后台运行",
                granted = notifGranted,
                actionLabel = "去开启"
            ) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            OnboardingRow(
                title = "后台运行",
                desc = "加入电池优化白名单，防止系统清理监听导致漏账",
                granted = batteryIgnored,
                actionLabel = "去设置"
            ) { requestIgnoreBatteryOptimizations(context) }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), enabled = coreReady) {
                Text(if (coreReady) "开始使用" else "请先开启通知使用权与短信读取")
            }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("暂不配置，稍后再说", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OnboardingRow(
    title: String,
    desc: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentPadding = Modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Text("已开启", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            } else {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun hasPostNotification(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun isBatteryIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
