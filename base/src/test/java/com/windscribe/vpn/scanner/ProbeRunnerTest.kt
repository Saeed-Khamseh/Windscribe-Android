package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_STEALTH
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WIRE_GUARD
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeRunnerTest {
    private fun tcp(i: Int) = ScanCandidate(1, "c", "h$i", "10.0.0.$i", PROTO_TCP, 443)

    private class FakeProber(
        private val kinds: Set<ProbeKind>,
        private val block: suspend (ProbeKey) -> ProbeResult,
    ) : EndpointProber {
        val calls = AtomicInteger()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()

        override fun handles(kind: ProbeKind) = kind in kinds

        override suspend fun probe(
            key: ProbeKey,
            timeoutMs: Long,
        ): ProbeResult {
            calls.incrementAndGet()
            val now = running.incrementAndGet()
            maxRunning.updateAndGet { maxOf(it, now) }
            try {
                return block(key)
            } finally {
                running.decrementAndGet()
            }
        }
    }

    @Test
    fun `concurrency is bounded`() =
        runTest {
            val prober =
                FakeProber(setOf(ProbeKind.TCP_CONNECT)) {
                    delay(100)
                    ProbeResult(true, 100)
                }
            val sink = ConcurrentHashMap<ProbeKey, ProbeResult>()

            ProbeRunner(listOf(prober), concurrency = 16).run((1..100).map { tcp(it) }, sink)

            assertEquals(100, sink.size)
            assertEquals(16, prober.maxRunning.get())
            // 100 probes of 100 ms, 16 at a time: 7 waves.
            assertEquals(700, currentTime)
        }

    @Test
    fun `default concurrency is sixteen and the timeout four seconds`() {
        assertEquals(16, ProbeRunner.DEFAULT_CONCURRENCY)
        assertEquals(4_000L, ProbeRunner.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `a probe that never answers is cut off at the timeout`() =
        runTest {
            val prober = FakeProber(setOf(ProbeKind.TCP_CONNECT)) { awaitCancellation() }
            val sink = ConcurrentHashMap<ProbeKey, ProbeResult>()
            val candidate = tcp(1)

            ProbeRunner(listOf(prober)).run(listOf(candidate), sink)

            val result = sink.getValue(candidate.probeKey())
            assertEquals(false, result.reachable)
            assertEquals("timeout", result.detail)
            // The 4 s timeout plus the small backstop, in virtual time.
            assertTrue(currentTime in 4_000L..4_500L)
        }

    @Test
    fun `tweak variants share one probe`() =
        runTest {
            val prober = FakeProber(setOf(ProbeKind.TLS_HANDSHAKE)) { ProbeResult(true, 5) }
            val sink = ConcurrentHashMap<ProbeKey, ProbeResult>()
            val base = ScanCandidate(1, "c", "h", "10.0.0.1", PROTO_STEALTH, 443)
            val candidates = listOf(base, base.copy(tweak = ScanTweak.ExtraTlsPadding), base.copy(port = 80))

            ProbeRunner(listOf(prober)).run(candidates, sink)

            assertEquals(2, prober.calls.get())
            assertEquals(2, sink.size)
        }

    @Test
    fun `a custom SNI is a different endpoint`() =
        runTest {
            val prober = FakeProber(setOf(ProbeKind.TLS_HANDSHAKE)) { ProbeResult(true, 5) }
            val sink = ConcurrentHashMap<ProbeKey, ProbeResult>()
            val base = ScanCandidate(1, "c", "h", "10.0.0.1", PROTO_STEALTH, 443)

            ProbeRunner(listOf(prober)).run(listOf(base, base.copy(tweak = ScanTweak.TlsServerName("x.example"))), sink)

            assertEquals(2, prober.calls.get())
        }

    @Test
    fun `protocols without an honest probe are not probed`() =
        runTest {
            val prober = FakeProber(ProbeKind.values().toSet()) { ProbeResult(true, 5) }
            val sink = ConcurrentHashMap<ProbeKey, ProbeResult>()
            val wg = ScanCandidate(1, "c", "h", "10.0.0.1", PROTO_WIRE_GUARD, 443)

            ProbeRunner(listOf(prober)).run(listOf(wg), sink)

            assertEquals(0, prober.calls.get())
            assertTrue(sink.isEmpty())
        }

    @Test
    fun `progress is reported up to the total`() =
        runTest {
            val prober = FakeProber(setOf(ProbeKind.TCP_CONNECT)) { ProbeResult(true, 1) }
            val seen = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()

            ProbeRunner(listOf(prober)).run((1..5).map { tcp(it) }, ConcurrentHashMap()) { done, total -> seen.add(done to total) }

            assertTrue(0 to 5 in seen)
            assertTrue(5 to 5 in seen)
        }

    @Test
    fun `no prober for a kind reads as unknown not unreachable`() =
        runTest {
            val sink = ConcurrentHashMap<ProbeKey, ProbeResult>()

            ProbeRunner(emptyList()).run(listOf(tcp(1)), sink)

            assertNull(sink.getValue(tcp(1).probeKey()).reachable)
        }

    @Test
    fun `cancelling the run stops the probes and keeps the answers so far`() =
        runTest {
            val prober =
                FakeProber(setOf(ProbeKind.TCP_CONNECT)) { key ->
                    if (key.host.endsWith(".1")) ProbeResult(true, 1) else awaitCancellation()
                }
            val sink = ConcurrentHashMap<ProbeKey, ProbeResult>()

            val job = launch { ProbeRunner(listOf(prober)).run((1..5).map { tcp(it) }, sink) }
            runCurrent()
            advanceTimeBy(1_000)
            assertFalse(job.isCompleted)
            job.cancel()
            runCurrent()

            assertTrue(job.isCancelled)
            assertEquals(1, sink.size)
            assertEquals(0, prober.running.get())
        }
}
