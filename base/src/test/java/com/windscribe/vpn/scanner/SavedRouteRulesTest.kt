package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_STEALTH
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRouteRulesTest {
    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun route(
        city: Int = 1,
        node: String = "n1",
        protocol: String = PROTO_STEALTH,
        port: Int = 443,
        tweak: ScanTweak = ScanTweak.None,
        working: Boolean = true,
        connected: Boolean = true,
        score: Double = 1000.0,
        ageMs: Long = day,
        failures: Int = 0,
    ) = SavedRoute(
        ScanResult(
            ScanCandidate(city, "C$city", node, "10.0.0.1", protocol, port, tweak),
            ProbeResult(true),
            VerifyResult(connected = connected, trafficOk = working),
            score,
            now - ageMs,
            "net",
        ),
        failures,
    )

    @Test
    fun `only working, recent, undemoted routes are usable`() {
        val routes =
            listOf(
                route(node = "ok"),
                route(node = "notraffic", working = false),
                route(node = "notconnected", connected = false),
                route(node = "old", ageMs = 15 * day),
                route(node = "demoted", failures = 2),
                route(node = "once", failures = 1),
            )
        assertEquals(setOf("ok", "once"), SavedRouteChooser.usable(routes, now).map { it.result.candidate.nodeHostname }.toSet())
        assertEquals(2, SavedRouteChooser.usable(routes, now).size)
    }

    @Test
    fun `the 14 day boundary is inclusive`() {
        assertEquals(1, SavedRouteChooser.usable(listOf(route(ageMs = 14 * day)), now).size)
    }

    @Test
    fun `a newer failure of a route hides its older success`() {
        val routes = listOf(route(ageMs = 5 * day), route(working = false, ageMs = day))
        assertTrue(SavedRouteChooser.usable(routes, now).isEmpty())
    }

    @Test
    fun `a city match wins over a better route elsewhere and keeps its node`() {
        val routes =
            listOf(
                route(city = 1, node = "a", score = 1500.0),
                route(city = 2, node = "b", score = 1100.0),
                route(city = 2, node = "c", score = 1200.0),
            )
        val pick = SavedRouteChooser.choose(routes, cityId = 2, nowMs = now)!!
        assertTrue(pick.cityMatch)
        assertEquals("c", pick.route.result.candidate.nodeHostname)
    }

    @Test
    fun `without a city match the best overall is used and flagged as no match`() {
        val routes =
            listOf(
                route(city = 1, node = "a", score = 1100.0, protocol = PROTO_TCP, port = 80),
                route(city = 3, node = "b", score = 1400.0),
            )
        val pick = SavedRouteChooser.choose(routes, cityId = 9, nowMs = now)!!
        assertFalse(pick.cityMatch)
        assertEquals("b", pick.route.result.candidate.nodeHostname)
    }

    @Test
    fun `nothing usable gives no pick`() {
        assertNull(SavedRouteChooser.choose(emptyList(), 1, now))
        assertNull(SavedRouteChooser.choose(listOf(route(failures = 2)), 1, now))
    }

    @Test
    fun `failures count up, reset on success, and demote at two`() {
        assertEquals(1, SavedRouteChooser.nextFailures(0, success = false))
        assertEquals(2, SavedRouteChooser.nextFailures(1, success = false))
        assertEquals(0, SavedRouteChooser.nextFailures(2, success = true))
        assertEquals(0, SavedRouteChooser.nextFailures(0, success = true))
    }

    private fun inputs(
        enabled: Boolean = true,
        scan: Boolean = false,
        network: String? = "home",
        mode: String? = PreferencesKeyConstants.CONNECTION_MODE_AUTO,
        preferred: Boolean = false,
        staticIp: Boolean = false,
        config: Boolean = false,
        decoy: Boolean = false,
    ) = SavedRouteGate.Inputs(enabled, scan, network, mode, preferred, staticIp, config, decoy)

    @Test
    fun `a plain auto connect on a known network may use a saved route`() {
        assertNull(SavedRouteGate.skipReason(inputs()))
    }

    @Test
    fun `every user choice or unknown network blocks it`() {
        listOf(
            inputs(enabled = false),
            inputs(scan = true),
            inputs(network = null),
            inputs(network = ScanResult.UNKNOWN_NETWORK),
            inputs(network = " "),
            inputs(mode = PreferencesKeyConstants.CONNECTION_MODE_MANUAL),
            inputs(mode = null),
            inputs(preferred = true),
            inputs(staticIp = true),
            inputs(config = true),
            inputs(decoy = true),
        ).forEach { assertTrue("$it", SavedRouteGate.skipReason(it) != null) }
    }
}
