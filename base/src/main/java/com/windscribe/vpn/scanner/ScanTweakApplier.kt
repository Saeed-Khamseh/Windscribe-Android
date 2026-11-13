package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.apppreference.PreferencesKeyConstants

/**
 * Writes a [ScanTweak] to the real preferences. One definition for both uses: the scan applies it
 * for one verification and restores it ([ScanPreferenceGuard]), and [ScanRouteConnector] applies it
 * for good when the user picks a result.
 */
internal object ScanTweakApplier {
    fun apply(
        prefs: PreferencesHelper,
        tweak: ScanTweak,
    ) {
        when (tweak) {
            ScanTweak.None -> Unit
            ScanTweak.ProtocolTweaks -> prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL
            is ScanTweak.AmneziaWg -> {
                prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL
                prefs.selectedUnblockWgParam = tweak.presetId
            }

            ScanTweak.ExtraTlsPadding -> {
                prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL
                prefs.extraTlsPaddingEnabled = true
            }

            is ScanTweak.TlsServerName -> prefs.tlsServerName = tweak.sni
            ScanTweak.AlternateRouting -> prefs.serverRoutingMode = PreferencesKeyConstants.SERVER_ROUTING_ALTERNATE
        }
    }
}
