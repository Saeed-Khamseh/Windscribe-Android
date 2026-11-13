package com.windscribe.vpn.scanner

import com.windscribe.vpn.model.User
import com.windscribe.vpn.state.ConnectionPolicy
import com.windscribe.vpn.state.ConnectionPolicy.ServiceMode
import com.windscribe.vpn.state.ConnectionPolicy.SessionEnd
import com.windscribe.vpn.state.WatchReason
import org.junit.Assert.assertEquals
import org.junit.Test

/** The end the scanner settles with must reproduce the service mode it found. */
class NetworkScannerSettleTest {
    private fun end(mode: ServiceMode?) = NetworkScanner.sessionEndFor(mode)

    @Test
    fun `idle or connected settles as a system end`() {
        assertEquals(SessionEnd.SystemDisconnect, end(null))
        assertEquals(SessionEnd.SystemDisconnect, end(ServiceMode.Off))
        assertEquals(SessionEnd.SystemDisconnect, end(ServiceMode.Watching(WatchReason.NetworkNotSecured)))
        assertEquals(SessionEnd.SystemDisconnect, end(ServiceMode.Watching(WatchReason.NetworkOptedOut)))
    }

    @Test
    fun `a stand-down stays a stand-down`() {
        assertEquals(SessionEnd.UserDisconnect, end(ServiceMode.Watching(WatchReason.StoodDownByUser)))
    }

    @Test
    fun `failure modes stay failures`() {
        assertEquals(SessionEnd.ConnectFailed, end(ServiceMode.Failed(blocking = true)))
        assertEquals(SessionEnd.NoNetwork, end(ServiceMode.NoNetwork))
        assertEquals(SessionEnd.AwaitingSession, end(ServiceMode.AwaitingSession))
        assertEquals(SessionEnd.AuthFailed, end(ServiceMode.AuthFailed))
        assertEquals(SessionEnd.UserPause, end(ServiceMode.Paused))
    }

    @Test
    fun `every mode maps to the end that policy turns back into that mode`() {
        // For modes that do not depend on the account or network, the round trip is exact.
        val input =
            ConnectionPolicy.PolicyInput(
                isAutoSecureOn = true,
                isNetworkStoodDown = false,
                isNetworkOptedIn = false,
                autoConnect = true,
                globalUserConnectionPreference = true,
                accountStatus = User.AccountStatus.Okay,
                status = com.windscribe.vpn.backend.VPNState.Status.Disconnected,
                serviceMode = null,
                canReadNetworkName = true,
            )
        listOf(ServiceMode.NoNetwork, ServiceMode.AwaitingSession, ServiceMode.AuthFailed, ServiceMode.Paused).forEach { mode ->
            assertEquals(mode, ConnectionPolicy.serviceModeAfterSession(input, end(mode)))
        }
        val failed = ConnectionPolicy.serviceModeAfterSession(input, end(ServiceMode.Failed(false)))
        assertEquals(true, failed is ServiceMode.Failed)
    }
}
