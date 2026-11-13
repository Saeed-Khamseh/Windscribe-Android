package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_STEALTH
import com.windscribe.vpn.autoconnection.ProtocolConnectionStatus
import com.windscribe.vpn.model.User
import com.windscribe.vpn.serverlist.entity.DatacenterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanRouteConnectorTest {
    private fun candidate(host: String = "us-central-099.example.net") =
        ScanCandidate(
            cityId = 7,
            cityName = "Dallas",
            nodeHostname = host,
            nodeIp = "10.0.0.9",
            protocol = PROTO_STEALTH,
            port = 443,
            tweak = ScanTweak.ExtraTlsPadding,
        )

    @Test
    fun `the route carries the scanned protocol and port`() {
        val route = ScanRouteConnector.routeFor(candidate(), listOf("us-central-099.example.net"))
        assertEquals(PROTO_STEALTH, route.protocol)
        assertEquals("443", route.port)
    }

    @Test
    fun `the node is pinned when the city still has it, matching on the host prefix`() {
        val route = ScanRouteConnector.routeFor(candidate(), listOf("us-central-001.other.net", "us-central-099.other.net"))
        assertEquals("us-central-099.example.net", route.hostname)
    }

    @Test
    fun `a node that left the server list is not pinned`() {
        val route = ScanRouteConnector.routeFor(candidate(), listOf("us-central-001.example.net"))
        assertNull(route.hostname)
        assertEquals("443", route.port)
    }

    @Test
    fun `a route becomes a disconnected protocol entry for the failover list`() {
        val info = ScanRouteConnector.routeFor(candidate(), emptyList()).toProtocolInformation()
        assertEquals(PROTO_STEALTH, info.protocol)
        assertEquals("443", info.port)
        assertEquals(ProtocolConnectionStatus.Disconnected, info.type)
    }

    private fun refusal(
        scanActive: Boolean = false,
        online: Boolean = true,
        account: User.AccountStatus? = User.AccountStatus.Okay,
        status: DatacenterStatus? = DatacenterStatus.Available,
    ) = ScanRouteConnector.refusalFor(scanActive, online, account, status)

    @Test
    fun `a healthy connect is not refused`() {
        assertNull(refusal())
        // No user loaded yet is not a reason to refuse: the connect path decides.
        assertNull(refusal(account = null))
    }

    @Test
    fun `a running scan wins over every other reason`() {
        assertEquals(ConnectOutcome.ScanRunning, refusal(scanActive = true, online = false))
    }

    @Test
    fun `offline is refused before the account and the location`() {
        assertEquals(ConnectOutcome.NoNetwork, refusal(online = false, account = User.AccountStatus.Banned))
    }

    @Test
    fun `expired and banned accounts are refused`() {
        assertEquals(ConnectOutcome.AccountBlocked, refusal(account = User.AccountStatus.Expired))
        assertEquals(ConnectOutcome.AccountBlocked, refusal(account = User.AccountStatus.Banned))
    }

    @Test
    fun `a missing, down or pro-only city is unavailable`() {
        assertEquals(ConnectOutcome.LocationUnavailable, refusal(status = null))
        assertEquals(ConnectOutcome.LocationUnavailable, refusal(status = DatacenterStatus.UnderMaintenance))
        assertEquals(ConnectOutcome.LocationUnavailable, refusal(status = DatacenterStatus.Pro))
    }
}
