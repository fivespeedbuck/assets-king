package com.assetsking.ui.privacy

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/** Fixed-length mask: never exposes the original amount or its digit count. */
const val PRIVACY_MASK = "****"

/**
 * Process-wide privacy formatting gate.
 *
 * Compose owns the observable state; this small thread-safe mirror lets every existing
 * non-composable money formatter (including notifications) share the same decision.
 */
object PrivacyMode {
    private val enabledState = mutableStateOf(false)

    val enabled: Boolean get() = enabledState.value

    fun setEnabled(value: Boolean) {
        enabledState.value = value
    }

    fun maskedAmount(): String = PRIVACY_MASK
}

val LocalPrivacyEnabled = staticCompositionLocalOf { false }
