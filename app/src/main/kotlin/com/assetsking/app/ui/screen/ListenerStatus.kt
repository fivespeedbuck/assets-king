package com.assetsking.app.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetsking.app.notification.AssetsNotificationListenerService

/**
 * 通知监听的三种状态。以前 UI 只判「有没有授权」，于是出现了最坑的一种情况：
 * 授权还在、服务却没被系统绑上，界面显示一切正常，实际一条通知都收不到。
 */
enum class ListenerStatus { DISABLED, DISCONNECTED, OK }

/**
 * 监听状态，随生命周期自动刷新。
 *
 * 授权状态只能在 ON_RESUME 时重新读（系统不给回调），所以从系统设置页返回时会立刻更新——
 * 修掉了「开完权限回来横幅还说没开」的问题；连接状态直接观察 StateFlow，绑定完成即刷新。
 */
@Composable
fun rememberListenerStatus(): ListenerStatus {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(isListenerEnabled(context)) }
    val connected by AssetsNotificationListenerService.connected.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = isListenerEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return when {
        !enabled -> ListenerStatus.DISABLED
        !connected -> ListenerStatus.DISCONNECTED
        else -> ListenerStatus.OK
    }
}

fun isListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

fun hasSmsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 短信读取权限状态，与监听授权一样只在 ON_RESUME 时重读（REQ 监听§14）。
 * 授权弹窗关掉后返回页面立即刷新，不用等重组。
 */
@Composable
fun rememberSmsGranted(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasSmsPermission(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasSmsPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return granted
}

fun openListenerSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}
