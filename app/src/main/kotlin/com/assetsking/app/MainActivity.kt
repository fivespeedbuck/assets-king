package com.assetsking.app

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.app.ui.screen.HomeScreen
import com.assetsking.app.ui.privacy.PrivacyModeSurface
import com.assetsking.app.ui.screen.MigrationGateScreen
import com.assetsking.app.ui.screen.OnboardingScreen
import com.assetsking.app.ui.screen.isListenerEnabled
import com.assetsking.database.LedgerRepository
import com.assetsking.ui.theme.AppTheme
import com.assetsking.ui.theme.AssetsKingTheme
import com.assetsking.ui.privacy.PrivacyMode

class MainActivity : ComponentActivity() {
    private var privacyEnabledState: MutableState<Boolean>? = null
    private var pendingPrivacyExit: (() -> Unit)? = null
    private var listenerLaunchRecoveryRequested = false
    private val credentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingPrivacyExit?.also { pendingPrivacyExit = null }?.invoke()
        } else {
            pendingPrivacyExit = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val initialPrivacy = when {
            privacyAutoLockTriggered(prefs.getLong(PRIVACY_BACKGROUND_AT, 0L), now) -> true
            prefs.contains("privacy_mode") -> prefs.getBoolean("privacy_mode", true)
            else -> true
        }
        PrivacyMode.setEnabled(initialPrivacy)
        prefs.edit()
            .putBoolean("privacy_mode", initialPrivacy)
            .remove(PRIVACY_BACKGROUND_AT)
            .apply()
        enableEdgeToEdge()
        setContent {
            val app = application as AssetsKingApplication
            val model: LedgerViewModel = viewModel(
                factory = LedgerViewModel.factory(app)
            )
            // 旧版 → 重构版迁移门禁（REQ 旧功能清理 §4-8）：完成前不放行进首页
            var gate by remember { mutableStateOf<LedgerRepository.MigrationStatus?>(null) }
            LaunchedEffect(Unit) { gate = app.repository.migrationStatus() }
            // 首次配置引导（REQ 监听§15）：一次性引导开权限，完成后不再反复要求
            var onboardingDone by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }
            val privacyState = remember { mutableStateOf(initialPrivacy) }
            privacyEnabledState = privacyState
            var privacyEnabled by privacyState
            AssetsKingTheme(theme = themeForPrivacy(privacyEnabled)) {
                PrivacyModeSurface(enabled = privacyEnabled) {
                    when {
                        !onboardingDone -> OnboardingScreen(onDone = {
                            prefs.edit().putBoolean("onboarding_done", true).apply()
                            onboardingDone = true
                        })
                        gate == null -> {}   // 检查中
                        gate == LedgerRepository.MigrationStatus.DONE ->
                            HomeScreen(
                                model = model,
                                repository = app.repository,
                                privacyEnabled = privacyEnabled,
                                onTogglePrivacy = {
                                    fun setPrivacy(next: Boolean) {
                                        PrivacyMode.setEnabled(next)
                                        prefs.edit().putBoolean("privacy_mode", next).apply()
                                        privacyEnabled = next
                                    }
                                    if (privacyEnabled) {
                                        authenticatePrivacyExit { setPrivacy(false) }
                                    } else {
                                        setPrivacy(true)
                                    }
                                }
                            )
                        else -> MigrationGateScreen(
                            repository = app.repository,
                            onDone = { gate = LedgerRepository.MigrationStatus.DONE }
                        )
                    }
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
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val backgroundedAt = prefs.getLong(PRIVACY_BACKGROUND_AT, 0L)
        if (backgroundedAt > 0L) {
            if (privacyAutoLockTriggered(backgroundedAt, System.currentTimeMillis())) {
                PrivacyMode.setEnabled(true)
                prefs.edit()
                    .putBoolean("privacy_mode", true)
                    .remove(PRIVACY_BACKGROUND_AT)
                    .apply()
                privacyEnabledState?.value = true
            } else {
                prefs.edit().remove(PRIVACY_BACKGROUND_AT).apply()
            }
        }
        if (isListenerEnabled(this)) {
            // 前台起 FGS 保活：进程不再被 vivo 当缓存清掉，监听不掉线
            AssetsNotificationListenerService.startKeepAlive(this)
            if (!listenerLaunchRecoveryRequested) {
                // 真正重开 App 时自动执行一次与「立即恢复」相同的强制重连。
                // vivo 冻结或系统重启后可能保留授权和旧连接外观，却没有恢复实际回调；
                // 只调用 requestRebind 不能稳定修复这种半连接状态。
                listenerLaunchRecoveryRequested = true
                AssetsNotificationListenerService.recoverNow(this)
            } else {
                // 同一 Activity 从后台回到前台只做幂等轻量检查，避免频繁解绑健康监听。
                AssetsNotificationListenerService.scheduleRebind(this)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putLong(PRIVACY_BACKGROUND_AT, System.currentTimeMillis())
            .apply()
    }

    private fun authenticatePrivacyExit(onAuthenticated: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            showBiometricPrompt(onAuthenticated)
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "验证身份",
            "退出隐秘模式"
        )
        if (intent == null) {
            Toast.makeText(this, "请先在系统中设置锁屏验证", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPrivacyExit = onAuthenticated
        credentialLauncher.launch(intent)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun showBiometricPrompt(onAuthenticated: () -> Unit) {
        val builder = BiometricPrompt.Builder(this)
            .setTitle("验证身份")
            .setSubtitle("退出隐秘模式")

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> builder.setDeviceCredentialAllowed(true)
            else -> builder.setNegativeButton("取消", mainExecutor) { _, _ -> }
        }

        builder.build().authenticate(
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onAuthenticated()
                }
            }
        )
    }
}

internal const val PRIVACY_BACKGROUND_AT = "privacy_background_at"
internal const val PRIVACY_AUTO_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

internal fun privacyAutoLockTriggered(backgroundedAt: Long, now: Long): Boolean =
    backgroundedAt > 0L && now >= backgroundedAt && now - backgroundedAt >= PRIVACY_AUTO_LOCK_TIMEOUT_MS

internal fun themeForPrivacy(privacyEnabled: Boolean): AppTheme =
    if (privacyEnabled) AppTheme.LONG_NEST else AppTheme.LIGHT_GREEN
