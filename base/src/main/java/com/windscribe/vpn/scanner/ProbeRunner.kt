package com.windscribe.vpn.scanner

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probes the distinct endpoints of a candidate list with bounded concurrency and a per-probe
 * timeout. Candidates that differ only by tweak share one probe. Everything is cancellable: when the
 * caller is cancelled the in-flight probes close their sockets and the call returns.
 */
class ProbeRunner(
    private val probers: List<EndpointProber>,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val probeTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val logger = LoggerFactory.getLogger(SCANNER_LOG)

    /**
     * Probes every distinct endpoint of [candidates] and records each answer in [sink] as it
     * arrives, so a caller that cancels or times out this call still keeps the answers so far.
     * Endpoints with no honest probe are not touched: look them up as [ProbeResult.NOT_PROBEABLE].
     */
    suspend fun run(
        candidates: List<ScanCandidate>,
        sink: MutableMap<ProbeKey, ProbeResult>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val keys = candidates.map { it.probeKey() }.filter { it.kind != ProbeKind.NONE }.distinct()
        val total = keys.size
        logger.info("Probing {} endpoints (concurrency {}, timeout {} ms).", total, concurrency, probeTimeoutMs)
        onProgress(0, total)
        val done = AtomicInteger(0)
        val permits = Semaphore(concurrency)
        coroutineScope {
            keys
                .map { key ->
                    async {
                        permits.withPermit {
                            sink[key] = probeOne(key)
                        }
                        onProgress(done.incrementAndGet(), total)
                    }
                }.awaitAll()
        }
        logger.info("Probing finished: {} reachable of {}.", sink.values.count { it.reachable == true }, total)
    }

    private suspend fun probeOne(key: ProbeKey): ProbeResult {
        val prober =
            probers.firstOrNull { it.handles(key.kind) }
                ?: return ProbeResult(null, null, "no prober for ${key.kind}")
        // The backstop sits a little past the prober's own deadline, which gives the nicer detail.
        return withTimeoutOrNull(probeTimeoutMs + BACKSTOP_MS) { prober.probe(key, probeTimeoutMs) }
            ?: ProbeResult(false, null, "timeout")
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 16
        const val DEFAULT_TIMEOUT_MS = 4_000L
        private const val BACKSTOP_MS = 250L
    }
}
