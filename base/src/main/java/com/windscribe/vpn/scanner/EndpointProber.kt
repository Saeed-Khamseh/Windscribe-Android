package com.windscribe.vpn.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Tests whether an endpoint answers, without authenticating. Probes use plain sockets on the
 * underlying network, so the VPN must be down while they run.
 */
interface EndpointProber {
    fun handles(kind: ProbeKind): Boolean

    /** Never throws for a network failure: that is a `reachable = false` result. */
    suspend fun probe(
        key: ProbeKey,
        timeoutMs: Long,
    ): ProbeResult
}

/**
 * Runs blocking socket code so that cancelling the caller returns promptly: [onCancel] closes the
 * socket, which aborts the blocked call. A plain `Socket.connect` ignores thread interruption.
 */
internal suspend fun <T> runCancellable(
    onCancel: () -> Unit,
    block: () -> T,
): T =
    coroutineScope {
        val work = async(Dispatchers.IO) { block() }
        try {
            work.await()
        } catch (e: CancellationException) {
            runCatching(onCancel)
            throw e
        }
    }
