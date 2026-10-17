package com.windscribe.vpn.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

interface ScanDriver {
    fun networkKey(): String

    suspend fun disconnect()

    suspend fun connect(route: ScanRoute): Boolean

    fun isRouteConnected(): Boolean

    suspend fun ping(target: String): Int
}

/** Sequential tests: no two tunnels or probes can overlap. Checkpoints contain completed tests only. */
class ScanEngine(
    private val driver: ScanDriver,
) {
    suspend fun run(
        settings: ScanSettings,
        plan: List<ScanRoute>,
        previous: List<ScanResult>,
        networkKey: String,
        checkpoint: suspend (List<ScanResult>) -> Unit,
        update: (ScanProgress) -> Unit,
    ): List<ScanResult> {
        require(settings.isValid() && plan.isNotEmpty())
        val keys = plan.map { it.key }.toSet()
        val results = previous.filter { it.route.key in keys }.distinctBy { it.route.key }.toMutableList()
        var keepConnected = false

        fun publish(
            route: ScanRoute?,
            step: ScanStep,
            message: String? = null,
        ) = update(ScanProgress(true, route, step, plan.size, results.toList(), message))

        fun checkNetwork() {
            check(driver.networkKey() == networkKey) { "Network changed. Start a new scan on this network." }
        }
        try {
            for (route in plan.filterNot { candidate -> results.any { it.route.key == candidate.key } }) {
                currentCoroutineContext().ensureActive()
                checkNetwork()
                publish(route, ScanStep.Disconnecting)
                // A disconnect failure aborts the scan; it must never contaminate the next route.
                withTimeout(15_000) { driver.disconnect() }
                publish(route, ScanStep.Connecting)
                val result =
                    try {
                        val connected = withTimeoutOrNull(settings.connectionTimeout * 1000L) { driver.connect(route) } ?: false
                        checkNetwork()
                        if (!connected) {
                            ScanResult(route, failure = "Connection failed or timed out")
                        } else {
                            publish(route, ScanStep.Settling)
                            delay(settings.settleDelay * 1000L)
                            check(driver.isRouteConnected()) { "VPN disconnected before measurement" }
                            publish(route, ScanStep.Measuring)
                            val samples =
                                (1..settings.pingCount).map { index ->
                                    currentCoroutineContext().ensureActive()
                                    checkNetwork()
                                    check(driver.isRouteConnected()) { "VPN disconnected during measurement" }
                                    val sample = withTimeoutOrNull(4_000) { driver.ping(settings.pingTarget) } ?: -1
                                    checkNetwork()
                                    check(driver.isRouteConnected()) { "VPN disconnected during measurement" }
                                    if (index < settings.pingCount) delay(250)
                                    sample
                                }
                            ScanResult(route, samples, if (samples.all { it < 0 }) "No ping replies" else null)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        checkNetwork()
                        ScanResult(route, failure = error.message ?: "Test failed")
                    }
                publish(route, ScanStep.Saving)
                results.add(result)
                checkpoint(results.toList())
                publish(route, ScanStep.Saving)
                if (settings.stopOnSuccess && result.working) {
                    keepConnected = true
                    break
                }
            }
            return results
        } finally {
            // Cancellation (including notification Stop) still tears down the test tunnel.
            withContext(NonCancellable) {
                publish(null, ScanStep.Stopping)
                if (!keepConnected) withTimeout(15_000) { driver.disconnect() }
            }
        }
    }
}
