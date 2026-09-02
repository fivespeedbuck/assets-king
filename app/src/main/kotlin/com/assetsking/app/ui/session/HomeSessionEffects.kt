package com.assetsking.app.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 平时异步保存导航状态；切到其他 App 时在 ON_STOP 同步写入最后一个快照。
 * 独立的窄 Composable 避免把生命周期观察和 HomeScreen 的全部页面分支放进同一 FIR 边界。
 */
@Composable
internal fun PersistHomeSessionEffect(
    store: HomeSessionStore,
    snapshot: HomeSessionSnapshot
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSnapshot by rememberUpdatedState(snapshot)

    LaunchedEffect(store, snapshot) {
        store.save(snapshot)
    }

    DisposableEffect(lifecycleOwner, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                store.flush(latestSnapshot)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
