package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_STEALTH
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_UDP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WIRE_GUARD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRankerTest {
    private fun cand(
        node: String,
        protocol: String,
        port: Int = 443,
        ip: String = "ip-$node-$protocol",
        tweak: ScanTweak = ScanTweak.None,
    ) = ScanCandidate(1, "City", node, ip, protocol, port, tweak)

    private fun up(ms: Long) = ProbeResult(true, ms, "ok")

    private val down = ProbeResult(false, null, "timeout")

    @Test
    fun `unreachable endpoints are never verified`() {
        val a = cand("a", PROTO_TCP)
        val b = cand("b", PROTO_TCP)
        val probes = mapOf(a.probeKey() to down, b.probeKey() to up(30))

        val out = ScanRanker.selectForVerification(listOf(a, b), probes, 10)

        assertEquals(listOf(b), out)
    }

    @Test
    fun `probeable candidates that were never probed are not verified`() {
        val a = cand("a", PROTO_TCP)

        assertTrue(ScanRanker.selectForVerification(listOf(a), emptyMap(), 10).isEmpty())
    }

    @Test
    fun `reachable candidates come fastest first`() {
        val slow = cand("slow", PROTO_TCP)
        val fast = cand("fast", PROTO_TCP)
        val mid = cand("mid", PROTO_TCP)
        val probes = mapOf(slow.probeKey() to up(300), fast.probeKey() to up(20), mid.probeKey() to up(90))

        val out = ScanRanker.selectForVerification(listOf(slow, fast, mid), probes, 10)

        assertEquals(listOf(fast, mid, slow), out)
    }

    @Test
    fun `unprobeable candidates follow, those on an IP with a successful probe first`() {
        val tcp = cand("a", PROTO_TCP, ip = "10.0.0.2")
        // WireGuard shares an address with a successful probe; the other WireGuard node does not.
        val wgGood = cand("a", PROTO_WIRE_GUARD, ip = "10.0.0.2")
        val wgOther = cand("z", PROTO_WIRE_GUARD, ip = "10.9.9.9")
        val probes = mapOf(tcp.probeKey() to up(40))

        val out = ScanRanker.selectForVerification(listOf(wgOther, wgGood, tcp), probes, 10)

        assertEquals(listOf(tcp, wgGood, wgOther), out)
    }

    @Test
    fun `unprobeable candidates are verified even with no probe success anywhere`() {
        val udp = cand("a", PROTO_UDP)

        assertEquals(listOf(udp), ScanRanker.selectForVerification(listOf(udp), emptyMap(), 5))
    }

    @Test
    fun `verification budget is respected`() {
        val list = (1..30).map { cand("n$it", PROTO_TCP) }
        val probes = list.associate { it.probeKey() to up(10) }

        assertEquals(7, ScanRanker.selectForVerification(list, probes, 7).size)
        assertTrue(ScanRanker.selectForVerification(list, probes, 0).isEmpty())
    }

    @Test
    fun `selection is spread across nodes and protocols`() {
        // One excellent node answering on many ports and two protocols, and two other nodes.
        val goodNode =
            listOf(443, 80, 53, 123, 587).map { cand("good", PROTO_TCP, it, ip = "g2") } +
                listOf(443, 80).map { cand("good", PROTO_STEALTH, it, ip = "g3") }
        val b = cand("b", PROTO_TCP, ip = "b2")
        val c = cand("c", PROTO_STEALTH, ip = "c3")
        val all = goodNode + b + c
        val probes = all.associate { it.probeKey() to up(if (it.nodeHostname == "good") 5 else 50) }

        val out = ScanRanker.selectForVerification(all, probes, 4)

        // Not four ports of the good node: it contributes one per protocol, then the others get a turn.
        assertEquals(4, out.size)
        assertEquals(setOf("good", "b", "c"), out.map { it.nodeHostname }.toSet())
        assertEquals(2, out.count { it.nodeHostname == "good" })
    }

    @Test
    fun `tweak variants of one endpoint stay in order inside their group`() {
        val none = cand("a", PROTO_STEALTH, tweak = ScanTweak.None)
        val padded = cand("a", PROTO_STEALTH, tweak = ScanTweak.ExtraTlsPadding)
        val probes = mapOf(none.probeKey() to up(10))

        val out = ScanRanker.selectForVerification(listOf(none, padded), probes, 5)

        assertEquals(listOf(none, padded), out)
    }

    @Test
    fun `a working route outscores everything else`() {
        val probe = up(10)
        val working = ScanRanker.score(probe, VerifyResult(true, 3000, true, 800, 1000, null))
        val noTraffic = ScanRanker.score(probe, VerifyResult(true, 1000, false, 20, null, "connected but download failed"))
        val notConnected = ScanRanker.score(probe, VerifyResult(false, null, false, null, null, "timeout"))
        val unverified = ScanRanker.score(probe, null)

        assertTrue(working > unverified)
        assertTrue(unverified > noTraffic)
        assertEquals(0.0, noTraffic, 0.0)
        assertEquals(0.0, notConnected, 0.0)
    }

    @Test
    fun `connected but no traffic is never scored as working`() {
        val score = ScanRanker.score(up(5), VerifyResult(connected = true, trafficOk = false, latencyMs = 1, downloadKbps = 99999))

        assertEquals(0.0, score, 0.0)
    }

    @Test
    fun `lower latency and higher throughput score higher`() {
        val probe = up(10)
        val base = VerifyResult(true, 4000, true, 200, 2000, null)

        val lowLatency = ScanRanker.score(probe, base.copy(latencyMs = 50))
        val highLatency = ScanRanker.score(probe, base.copy(latencyMs = 600))
        val fast = ScanRanker.score(probe, base.copy(downloadKbps = 15000))
        val slow = ScanRanker.score(probe, base.copy(downloadKbps = 500))

        assertTrue(lowLatency > highLatency)
        assertTrue(fast > slow)
    }

    @Test
    fun `unverified scores stay below any working route`() {
        val worst = ScanRanker.score(up(1), VerifyResult(true, 20000, true, 5000, 0, null))

        assertTrue(worst > ScanRanker.score(up(1), null))
        assertTrue(ScanRanker.score(up(1), null) > ScanRanker.score(ProbeResult.NOT_PROBEABLE, null))
        assertTrue(ScanRanker.score(ProbeResult.NOT_PROBEABLE, null) > ScanRanker.score(down, null))
    }

    @Test
    fun `rank puts working routes first and keeps ties in order`() {
        fun result(
            host: String,
            verify: VerifyResult?,
        ): ScanResult {
            val c = cand(host, PROTO_TCP)
            val probe = up(10)
            return ScanResult(c, probe, verify, ScanRanker.score(probe, verify), 0L, "net")
        }
        val failed1 = result("f1", VerifyResult(true, 1000, false, 10, null, "x"))
        val good = result("good", VerifyResult(true, 1000, true, 100, 5000, null))
        val failed2 = result("f2", VerifyResult(false, null, false, null, null, "y"))

        val ranked = ScanRanker.rank(listOf(failed1, good, failed2))

        assertEquals(listOf("good", "f1", "f2"), ranked.map { it.candidate.nodeHostname })
        assertTrue(ranked.first().isWorking)
        assertFalse(ranked[1].isWorking)
    }
}
