package com.assetsking.app.ui.screen

import com.assetsking.app.notification.VaultRuntimeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPresentationTest {
    @Test
    fun guardianModeUsesTheProductQuoteInsteadOfATechnicalLabel() {
        assertEquals(
            "我们是守护者，也是一群时刻对抗着危险和疯狂的可怜虫。——邓恩·史密斯",
            GUARDIAN_MODE_QUOTE
        )
        assertFalse(GUARDIAN_MODE_QUOTE.contains("高可靠"))
    }

    @Test
    fun settingsHomeHasExactlyThreeRequiredGroupsInProductOrder() {
        assertEquals(
            listOf("月度规划", "自动入库", "数据与隐私"),
            settingsHomeSectionSpecs.map { it.section.title }
        )
    }

    @Test
    fun allSettingsGroupsUseSecondaryPages() {
        assertEquals(
            listOf(
                SettingsHomeBehavior.SECONDARY_PAGE,
                SettingsHomeBehavior.SECONDARY_PAGE,
                SettingsHomeBehavior.SECONDARY_PAGE
            ),
            settingsHomeSectionSpecs.map { it.behavior }
        )
    }

    @Test
    fun privacyModeDisablesEverySettingsMutation() {
        assertFalse(settingsMutationEnabled(privacyEnabled = true))
        assertTrue(settingsMutationEnabled(privacyEnabled = false))
    }

    @Test
    fun missingSmsFallbackIsAWarningNotAListenerFailure() {
        assertEquals(
            SettingsPipelineSeverity.WARNING,
            settingsPipelineSeverity(
                listenerStatus = ListenerStatus.OK,
                runtimeStatus = VaultRuntimeStatus.IDLE,
                notificationPermissionGranted = true,
                smsFallbackGranted = false,
                batteryExemptionGranted = true
            )
        )
    }

    @Test
    fun missingAppNotificationPermissionIsAWarningNotAnIntakeFailure() {
        assertEquals(
            SettingsPipelineSeverity.WARNING,
            settingsPipelineSeverity(
                listenerStatus = ListenerStatus.OK,
                runtimeStatus = VaultRuntimeStatus.IDLE,
                notificationPermissionGranted = false,
                smsFallbackGranted = true,
                batteryExemptionGranted = true
            )
        )
    }

    @Test
    fun disabledOrDisconnectedListenerIsAnError() {
        listOf(ListenerStatus.DISABLED, ListenerStatus.DISCONNECTED).forEach { status ->
            assertEquals(
                SettingsPipelineSeverity.ERROR,
                settingsPipelineSeverity(
                    listenerStatus = status,
                    runtimeStatus = VaultRuntimeStatus.IDLE,
                    notificationPermissionGranted = true,
                    smsFallbackGranted = true,
                    batteryExemptionGranted = true
                )
            )
        }
    }

    @Test
    fun runtimeFailureIsAnErrorEvenWhileListenerIsConnected() {
        assertEquals(
            SettingsPipelineSeverity.ERROR,
            settingsPipelineSeverity(
                listenerStatus = ListenerStatus.OK,
                runtimeStatus = VaultRuntimeStatus.ERROR,
                notificationPermissionGranted = true,
                smsFallbackGranted = true,
                batteryExemptionGranted = true
            )
        )
    }
}
