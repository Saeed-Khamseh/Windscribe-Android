package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.apppreference.PreferencesKeyConstants
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ScanTweakApplierTest {
    private val prefs = mockk<PreferencesHelper>(relaxed = true)

    @Test
    fun `none touches nothing`() {
        ScanTweakApplier.apply(prefs, ScanTweak.None)
        verify(exactly = 0) { prefs.protocolTweaksMode = any() }
        verify(exactly = 0) { prefs.serverRoutingMode = any() }
        verify(exactly = 0) { prefs.tlsServerName = any() }
    }

    @Test
    fun `protocol tweaks switch the mode to manual`() {
        ScanTweakApplier.apply(prefs, ScanTweak.ProtocolTweaks)
        verify { prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL }
    }

    @Test
    fun `an amnezia preset selects the preset and enables tweaks`() {
        ScanTweakApplier.apply(prefs, ScanTweak.AmneziaWg("preset-2"))
        verify { prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL }
        verify { prefs.selectedUnblockWgParam = "preset-2" }
    }

    @Test
    fun `tls padding enables tweaks and the padding switch`() {
        ScanTweakApplier.apply(prefs, ScanTweak.ExtraTlsPadding)
        verify { prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL }
        verify { prefs.extraTlsPaddingEnabled = true }
    }

    @Test
    fun `a server name and alternate routing are written as they are`() {
        ScanTweakApplier.apply(prefs, ScanTweak.TlsServerName("front.example.org"))
        verify { prefs.tlsServerName = "front.example.org" }
        ScanTweakApplier.apply(prefs, ScanTweak.AlternateRouting)
        verify { prefs.serverRoutingMode = PreferencesKeyConstants.SERVER_ROUTING_ALTERNATE }
    }
}
