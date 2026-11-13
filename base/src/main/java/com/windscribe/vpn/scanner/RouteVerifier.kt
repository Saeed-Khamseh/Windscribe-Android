package com.windscribe.vpn.scanner

import android.os.SystemClock
import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.autoconnection.ProtocolConnectionStatus
import com.windscribe.vpn.autoconnection.ProtocolInformation
import com.windscribe.vpn.backend.VPNState
import com.windscribe.vpn.backend.utils.ConnectSource
import com.windscribe.vpn.backend.utils.SYSTEM_DISCONNECT
import com.windscribe.vpn.backend.utils.WindVpnController
import com.windscribe.vpn.commonutils.WindUtilities
import com.windscribe.vpn.repository.ConnectionDataRepository
import com.windscribe.vpn.state.VPNConnectionStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects through the real connect path, measures what the tunnel carries, and disconnects.
 *
 * "Connected" here is the app's own definition: the engine is up and the tunnel test passed. That is
 * not enough, so a connected route is then asked for a download. A route that connects and cannot
 * download is a failure with a reason, never a working route.
 *
 * Connects use [ConnectSource.Scanner] and go to [WindVpnController.connect] directly rather than
 * through failover, so a failed attempt ends here instead of walking the protocol list.
 */
@Singleton
class RouteVerifier
    @Inject
    constructor(
        private val controller: WindVpnController,
        private val stateManager: VPNConnectionStateManager,
        private val prefs: PreferencesHelper,
        private val guard: ScanPreferenceGuard,
        private val traffic: TrafficProbe,
        private val connectionDataRepository: ConnectionDataRepository,
    ) {
        private val logger = LoggerFactory.getLogger(SCANNER_LOG)

        private var credentialsRefreshed = false
        private var keyRetried = false

        /** Why the last attempt failed, when the failover path would have retried it. */
        private var lastFailure: VPNState.ErrorType? = null

        /** Call at the start of a scan: the one-time retries below are per scan. */
        fun beginScan() {
            credentialsRefreshed = false
            keyRetried = false
        }

        /**
         * Like the failover path (`AutoConnectionManager.connectionAttempt`): rejected credentials
         * are refreshed with `ConnectionDataRepository.update()` and the attempt repeated, and a
         * rejected WireGuard key gets one more try. Each happens once per scan, not once per
         * candidate, so a server that keeps refusing cannot turn the scan into a loop.
         */
        suspend fun verify(candidate: ScanCandidate): VerifyResult {
            val first = attemptOnce(candidate)
            when (lastFailure) {
                VPNState.ErrorType.AuthenticationError ->
                    if (!credentialsRefreshed) {
                        credentialsRefreshed = true
                        logger.info("Credentials were rejected; refreshing them once for this scan.")
                        if (connectionDataRepository.update()) {
                            return attemptOnce(candidate)
                        }
                        logger.info("The credential refresh failed; keeping the first result.")
                    }

                VPNState.ErrorType.WireguardAuthenticationError ->
                    if (!keyRetried) {
                        keyRetried = true
                        logger.info("The WireGuard key was rejected; retrying once for this scan.")
                        return attemptOnce(candidate)
                    }

                else -> Unit
            }
            return first
        }

        private suspend fun attemptOnce(candidate: ScanCandidate): VerifyResult {
            lastFailure = null
            logger.info("Verifying {}", candidate)
            val startMs = SystemClock.elapsedRealtime()
            try {
                guard.apply(candidate)
                val outcome = connect(candidate)
                val connectMs = SystemClock.elapsedRealtime() - startMs
                val failure = failureOf(outcome)
                if (failure != null) {
                    lastFailure = (outcome as? Outcome.Ended)?.state?.error?.error
                    logger.info("{} did not connect: {}", candidate, failure)
                    return VerifyResult(connected = false, connectMs = connectMs, error = failure)
                }
                // The node pin (a favourite, or the force-node parameter) outranks the hostname the
                // scan asked for. Then this tunnel says nothing about the candidate.
                val actual = prefs.selectedIp
                if (!WindUtilities.hostnamesMatch(actual, candidate.nodeHostname)) {
                    val message = "connected to ${actual ?: "another node"}, not the requested ${candidate.nodeHostname}"
                    logger.info("{}: {}", candidate, message)
                    return VerifyResult(connected = true, connectMs = connectMs, trafficOk = false, error = message)
                }
                val measured = traffic.measure()
                val result =
                    VerifyResult(
                        connected = true,
                        connectMs = connectMs,
                        trafficOk = measured.trafficOk,
                        latencyMs = measured.latencyMs,
                        downloadKbps = measured.downloadKbps,
                        error = if (measured.trafficOk) null else "connected but ${measured.error ?: "no traffic"}",
                    )
                logger.info("{} -> {}", candidate, result)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Verification of $candidate failed unexpectedly.", e)
                return VerifyResult(connected = false, error = "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // Whatever happened, including cancellation: no tunnel, preferences as they were.
                withContext(NonCancellable) {
                    runCatching { guard.restoreTweaks() }
                    disconnect()
                }
            }
        }

        private sealed interface Outcome {
            data object TimedOut : Outcome

            data class Ended(
                val state: VPNState,
            ) : Outcome
        }

        private suspend fun connect(candidate: ScanCandidate): Outcome {
            val connectionId = UUID.randomUUID()
            val protocol =
                ProtocolInformation(
                    candidate.protocol,
                    candidate.port.toString(),
                    "",
                    ProtocolConnectionStatus.Disconnected,
                )
            return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                coroutineScope {
                    // Collecting before connecting: the first state of an attempt must not be missed.
                    var sawConnecting = false
                    val ended =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            stateManager.state.first { state ->
                                if (state.connectionId == connectionId && state.status == VPNState.Status.Connecting) {
                                    sawConnecting = true
                                }
                                isFinal(state, connectionId, sawConnecting)
                            }
                        }
                    controller.connect(
                        source = ConnectSource.Scanner,
                        connectionId = connectionId,
                        protocolInformation = protocol,
                        attempt = 0,
                        hostname = candidate.nodeHostname,
                    )
                    Outcome.Ended(ended.await())
                }
            } ?: Outcome.TimedOut
        }

        /**
         * A profile that cannot be built ends in a Disconnected state with no connection id, which
         * only counts once this attempt has been seen starting: before that it is the previous
         * attempt's leftover.
         */
        private fun isFinal(
            state: VPNState,
            connectionId: UUID,
            sawConnecting: Boolean,
        ): Boolean {
            if (state.error?.error == VPNState.ErrorType.UserReconnect) return false
            val ours = state.connectionId == connectionId
            return when (state.status) {
                VPNState.Status.Connected -> ours
                VPNState.Status.Disconnected -> ours || (sawConnecting && state.connectionId == null)
                else -> false
            }
        }

        private fun failureOf(outcome: Outcome): String? =
            when (outcome) {
                Outcome.TimedOut -> "no connection within ${CONNECT_TIMEOUT_MS / 1000}s"
                is Outcome.Ended -> {
                    val state = outcome.state
                    if (state.status == VPNState.Status.Connected) {
                        null
                    } else {
                        val error = state.error
                        when (error?.error) {
                            null -> "disconnected before the tunnel came up"
                            VPNState.ErrorType.TimeoutError -> "handshake timed out"
                            VPNState.ErrorType.ConnectivityTestFailed -> "connected but the tunnel test failed"
                            VPNState.ErrorType.AuthenticationError -> "server rejected the credentials"
                            VPNState.ErrorType.WireguardAuthenticationError -> "WireGuard key rejected"
                            else -> "${error?.error}: ${error?.message}"
                        }
                    }
                }
            }

        /** Takes the tunnel down and waits for it, within a bound. A system disconnect: no stand-down. */
        private suspend fun disconnect() {
            if (!stateManager.isVPNActive()) return
            controller.disconnectAsync(SYSTEM_DISCONNECT)
            val down =
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                    stateManager.state.first { it.status == VPNState.Status.Disconnected }
                }
            if (down == null) {
                logger.warn("The tunnel did not report disconnected within {} ms.", DISCONNECT_TIMEOUT_MS)
            }
        }

        private companion object {
            const val CONNECT_TIMEOUT_MS = 20_000L
            const val DISCONNECT_TIMEOUT_MS = 8_000L
        }
    }
