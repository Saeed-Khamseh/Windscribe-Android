package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_IKev2
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_STEALTH
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_UDP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WIRE_GUARD
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WS_TUNNEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCandidateGeneratorTest {
    private fun node(
        city: Int,
        n: Int,
    ) = ScanNode(
        hostname = "c$city-n$n",
        ip = "10.$city.$n.1",
        ip2 = "10.$city.$n.2",
        ip3 = "10.$city.$n.3",
    )

    private fun city(
        id: Int,
        nodes: Int = 3,
        favourite: Boolean = false,
        ping: Int? = null,
    ) = ScanCity(id, "City$id", (1..nodes).map { node(id, it) }, favourite, ping)

    private val portMap =
        mapOf(
            PROTO_IKev2 to listOf(500),
            PROTO_UDP to listOf(80, 443, 53),
            PROTO_TCP to listOf(587, 443, 80),
            PROTO_STEALTH to listOf(80, 443),
            PROTO_WIRE_GUARD to listOf(80, 443),
        )

    private fun inventory(
        cities: List<ScanCity>,
        presets: List<String> = listOf("p1", "p2"),
    ) = ScanInventory(cities, portMap, presets)

    private val tenCities =
        (1..10).map { city(it, ping = it * 10) }

    @Test
    fun `quick scan takes eight cities and two nodes each`() {
        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        assertEquals(8, out.map { it.cityId }.distinct().size)
        // Per city and protocol and port and tweak, two nodes.
        out
            .groupBy { listOf(it.cityId, it.protocol, it.port, it.tweak) }
            .values
            .forEach { assertEquals(2, it.size) }
    }

    @Test
    fun `quick scan prefers favourites then lowest ping`() {
        val cities =
            listOf(
                city(1, ping = 500),
                city(2, ping = 20),
                city(3, ping = null),
                city(4, ping = 300, favourite = true),
                city(5, ping = 40),
                city(6, ping = 60),
                city(7, ping = 80),
                city(8, ping = 100),
                city(9, ping = 120),
                city(10, ping = 10),
            )

        val chosen =
            ScanCandidateGenerator
                .generate(inventory(cities), ScanRequest.Quick())
                .map { it.cityId }
                .distinct()

        // The favourite is first of the sort, then 10, 20, 40, 60, 80, 100, 120; 500 and unknown fall off.
        assertEquals(setOf(4, 10, 2, 5, 6, 7, 8, 9), chosen.toSet())
        assertFalse(1 in chosen)
        assertFalse(3 in chosen)
    }

    @Test
    fun `quick scan orders protocols by censorship resistance`() {
        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        val order = out.map { it.protocol }.distinct()
        assertEquals(
            listOf(PROTO_WS_TUNNEL, PROTO_STEALTH, PROTO_TCP, PROTO_WIRE_GUARD, PROTO_IKev2, PROTO_UDP),
            order,
        )
        // Contiguous: each protocol appears as one block.
        val blocks = out.map { it.protocol }.fold(emptyList<String>()) { acc, p -> if (acc.lastOrNull() == p) acc else acc + p }
        assertEquals(order, blocks)
    }

    @Test
    fun `port 443 comes first where offered and the port map order follows`() {
        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        fun portsOf(protocol: String) =
            out
                .filter { it.protocol == protocol && it.tweak == ScanTweak.None }
                .map { it.port }
                .distinct()
        assertEquals(listOf(443, 587, 80), portsOf(PROTO_TCP))
        assertEquals(listOf(443, 80, 53), portsOf(PROTO_UDP))
        assertEquals(listOf(500), portsOf(PROTO_IKev2))
    }

    @Test
    fun `a protocol missing from the port map falls back to its default port`() {
        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        assertEquals(listOf(443), out.filter { it.protocol == PROTO_WS_TUNNEL }.map { it.port }.distinct())
    }

    @Test
    fun `none is the first tweak of every protocol`() {
        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        out.groupBy { it.protocol }.forEach { (protocol, list) ->
            assertEquals("first tweak of $protocol", ScanTweak.None, list.first().tweak)
        }
    }

    @Test
    fun `wireguard tries plain then each amnezia preset`() {
        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        val tweaks = out.filter { it.protocol == PROTO_WIRE_GUARD }.map { it.tweak }.distinct()
        assertEquals(listOf(ScanTweak.None, ScanTweak.AmneziaWg("p1"), ScanTweak.AmneziaWg("p2")), tweaks)
    }

    @Test
    fun `tweaks are only generated for protocols they apply to`() {
        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        out.forEach { assertTrue("${it.tweak} on ${it.protocol}", it.tweak.appliesTo(it.protocol)) }
    }

    @Test
    fun `each protocol dials the address VPNProfileCreator gives it`() {
        val out = ScanCandidateGenerator.generate(inventory(listOf(city(1, nodes = 1))), ScanRequest.Quick())

        fun ipOf(protocol: String) = out.first { it.protocol == protocol }.nodeIp
        assertEquals("10.1.1.1", ipOf(PROTO_IKev2))
        assertEquals("10.1.1.1", ipOf(PROTO_WS_TUNNEL))
        assertEquals("10.1.1.2", ipOf(PROTO_TCP))
        assertEquals("10.1.1.2", ipOf(PROTO_UDP))
        assertEquals("10.1.1.3", ipOf(PROTO_STEALTH))
        assertEquals("10.1.1.3", ipOf(PROTO_WIRE_GUARD))
    }

    @Test
    fun `a node without the address a protocol needs is skipped for that protocol`() {
        val bare = ScanCity(1, "Bare", listOf(ScanNode("h", ip = "1.1.1.1", ip2 = "", ip3 = "")))

        val out = ScanCandidateGenerator.generate(inventory(listOf(bare)), ScanRequest.Quick())

        assertEquals(setOf(PROTO_IKev2, PROTO_WS_TUNNEL), out.map { it.protocol }.toSet())
    }

    @Test
    fun `cities without nodes are ignored`() {
        val out = ScanCandidateGenerator.generate(inventory(listOf(city(1, nodes = 0), city(2))), ScanRequest.Quick())

        assertEquals(setOf(2), out.map { it.cityId }.toSet())
    }

    @Test
    fun `probe limit bounds distinct probed endpoints but keeps tweak variants of included ones`() {
        val limits = ScanLimits(maxProbes = 20, maxVerifications = 5, timeBudgetMs = 1000)

        val out = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick(limits))

        val probed = out.filter { it.probeKind != ProbeKind.NONE }
        assertTrue(probed.map { it.probeKey() }.distinct().size <= 20)
        assertEquals(20, probed.map { it.probeKey() }.distinct().size)
        // Variants of an included endpoint do not use up probes: padding variants are present.
        assertTrue(out.any { it.tweak == ScanTweak.ExtraTlsPadding })
        // Unprobeable candidates are not limited.
        assertTrue(out.any { it.protocol == PROTO_WIRE_GUARD })
    }

    @Test
    fun `tweak variants share one probe key`() {
        val a = ScanCandidate(1, "c", "h", "1.2.3.4", PROTO_STEALTH, 443, ScanTweak.None)
        val b = a.copy(tweak = ScanTweak.ExtraTlsPadding)
        val c = a.copy(tweak = ScanTweak.TlsServerName("example.org"))

        assertEquals(a.probeKey(), b.probeKey())
        assertEquals("example.org", c.probeKey().sni)
        assertFalse(a.probeKey() == c.probeKey())
    }

    @Test
    fun `custom scan honours city order, protocols, ports, nodes and tweaks`() {
        val request =
            ScanRequest.Custom(
                cityIds = listOf(5, 2, 99),
                protocols = listOf(PROTO_TCP, PROTO_STEALTH),
                ports = listOf(443),
                tweaks = listOf(ScanTweak.ProtocolTweaks, ScanTweak.TlsServerName("front.example"), ScanTweak.AmneziaWg("p1")),
                nodesPerCity = 1,
            )

        val out = ScanCandidateGenerator.generate(inventory(tenCities), request)

        // City 99 does not exist; order of the request is kept.
        assertEquals(listOf(5, 2), out.map { it.cityId }.distinct())
        assertEquals(setOf(PROTO_STEALTH, PROTO_TCP), out.map { it.protocol }.toSet())
        assertEquals(setOf(443), out.map { it.port }.toSet())
        assertEquals(setOf("c5-n1", "c2-n1"), out.map { it.nodeHostname }.toSet())
        // The amnezia preset is for WireGuard only, which was not asked for.
        assertFalse(out.any { it.tweak is ScanTweak.AmneziaWg })
        val tcpTweaks = out.filter { it.protocol == PROTO_TCP }.map { it.tweak }.distinct()
        assertEquals(listOf(ScanTweak.None, ScanTweak.ProtocolTweaks), tcpTweaks)
        val stealthTweaks = out.filter { it.protocol == PROTO_STEALTH }.map { it.tweak }.distinct()
        assertEquals(listOf(ScanTweak.None, ScanTweak.TlsServerName("front.example")), stealthTweaks)
    }

    @Test
    fun `custom scan with empty protocols and ports takes everything from the port map`() {
        val request = ScanRequest.Custom(cityIds = listOf(1), nodesPerCity = 1)

        val out = ScanCandidateGenerator.generate(inventory(tenCities), request)

        assertEquals(ScanTweak.ALL_PROTOCOLS, out.map { it.protocol }.toSet())
        assertEquals(setOf(443, 587, 80), out.filter { it.protocol == PROTO_TCP }.map { it.port }.toSet())
    }

    @Test
    fun `generation is deterministic`() {
        val a = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())
        val b = ScanCandidateGenerator.generate(inventory(tenCities), ScanRequest.Quick())

        assertEquals(a, b)
        assertEquals(a.size, a.toSet().size)
    }

    @Test
    fun `tweak keys round trip`() {
        listOf(
            ScanTweak.None,
            ScanTweak.ProtocolTweaks,
            ScanTweak.ExtraTlsPadding,
            ScanTweak.AlternateRouting,
            ScanTweak.AmneziaWg("abc:1"),
            ScanTweak.TlsServerName("front.example"),
        ).forEach { assertEquals(it, ScanTweak.fromKey(it.key)) }
    }
}
