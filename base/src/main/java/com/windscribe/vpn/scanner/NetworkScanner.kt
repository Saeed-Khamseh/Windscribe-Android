package com.windscribe.vpn.scanner

import android.os.SystemClock
import com.windscribe.common.tunnel.TunnelManager
import com.windscribe.vpn.autoconnection.AutoConnectionManager
import com.windscribe.vpn.backend.VPNState
import com.windscribe.vpn.backend.utils.SYSTEM_DISCONNECT
import com.windscribe.vpn.backend.utils.WindVpnController
import com.windscribe.vpn.state.ConnectionPolicy
import com.windscribe.vpn.state.DeviceStateManager
import com.windscribe.vpn.state.VPNConnectionStateManager
import com.windscribe.vpn.state.WatchReason
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds server / protocol / port / tweak combinations that work on the current network.
 *
 * Probe, then verify. The VPN is taken down first and the probes run on the underlying network;
 * the best probed candidates are then connected for real, one at a time, and measured. Whatever
 * happens (finish, [stop], a failure, a cancelled scope), the preferences are restored and the scan
 * flag cleared in `finally`. A process that dies mid-scan is repaired by [repairIfInterrupted].
 */
@Singleton
class NetworkScanner
    @Inject
    constructor(
        private val scope: CoroutineScope,
        private val inventoryLoader: ScanInventoryLoader,
        private val probeRunner: ProbeRunner,
        private val verifier: RouteVerifier,
        private val store: ScanResultStore,
        private val guard: ScanPreferenceGuard,
        private val controller: WindVpnController,
        private val stateManager: VPNConnectionStateManager,
        private val deviceStateManager: DeviceStateManager,
        private val autoConnectionManager: AutoConnectionManager,
        private val tunnelManager: Lazy<TunnelManager>,
    ) {
        private val logger = LoggerFactory.getLogger(SCANNER_LOG)
        private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
        val state: StateFlow<ScanState> = _state.asStateFlow()

        private var job: Job? = null

        /** Starts a scan. Returns false, and does nothing, if one is already running. */
        @Synchronized
        fun start(request: ScanRequest): Boolean {
            if (job?.isActive == true || ScanSession.active) {
                logger.info("A scan is already running; ignoring the request.")
                return false
            }
            // Before the launch, so no hook point can see a scan that has started but is not flagged.
            ScanSession.begin()
            _state.value = ScanState.Probing(0, 0)
            job = scope.launch(Dispatchers.Default) { run(request) }
            return true
        }

        /** Cancels the running scan. Returns at once; the scan restores everything as it unwinds. */
        fun stop() {
            logger.info("Stop requested.")
            job?.cancel()
        }

        /** Call at app start: undoes the preference changes of a scan that was killed part-way. */
        fun repairIfInterrupted() {
            if (guard.repairIfInterrupted()) {
                logger.warn("Repaired preferences left behind by an interrupted scan.")
            }
        }

        private suspend fun run(request: ScanRequest) {
            val results = ArrayList<ScanResult>()
            var network = ScanResult.UNKNOWN_NETWORK
            var terminal: ScanState? = null
            val priorMode = stateManager.serviceMode.value
            val wasTunnelUp = stateManager.isTunnelUp()
            // A TUN held with no engine is the kill switch's blocking hold.
            val hadHeldTunnel = !wasTunnelUp && tunnelManager.get().hasTunnel
            try {
                if (!deviceStateManager.isOnline.value) {
                    terminal = ScanState.Failed("There is no network connection.")
                    return
                }
                network = deviceStateManager.getCurrentNetworkName() ?: ScanResult.UNKNOWN_NETWORK
                logger.info("Scan starting on '{}': {}", network, request)
                // A failover walk in progress would fight the scan for the connect path.
                autoConnectionManager.stop()
                guard.beginSession()
                verifier.beginScan()
                takeVpnDown()
                releaseBlockingHold()

                val inventory = inventoryLoader.load()
                if (inventory.cities.isEmpty()) {
                    terminal = ScanState.Failed("The server list is empty. Update it, then scan again.")
                    return
                }
                val candidates = ScanCandidateGenerator.generate(inventory, request)
                if (candidates.isEmpty()) {
                    terminal = ScanState.Failed("Nothing to scan with these settings.")
                    return
                }
                logger.info("{} candidates generated.", candidates.size)

                val limits = request.limits
                val startedMs = SystemClock.elapsedRealtime()
                val probes = ConcurrentHashMap<ProbeKey, ProbeResult>()
                // Half the budget at most: the rest is for the connections, which are the point.
                val probed =
                    withTimeoutOrNull(limits.timeBudgetMs / 2) {
                        probeRunner.run(candidates, probes) { done, total ->
                            _state.value = ScanState.Probing(done, total)
                        }
                    }
                if (probed == null) {
                    logger.warn("Probing hit its share of the time budget; using {} answers.", probes.size)
                }

                val selection = ScanRanker.selectForVerification(candidates, probes, limits.maxVerifications)
                logger.info("{} candidates selected for verification.", selection.size)
                for ((index, candidate) in selection.withIndex()) {
                    if (SystemClock.elapsedRealtime() - startedMs >= limits.timeBudgetMs) {
                        logger.info("Time budget used up after {} verifications.", index)
                        break
                    }
                    if ((deviceStateManager.getCurrentNetworkName() ?: ScanResult.UNKNOWN_NETWORK) != network) {
                        terminal = ScanState.Failed("The network changed during the scan.")
                        break
                    }
                    _state.value = ScanState.Verifying(index + 1, selection.size, candidate)
                    val probe = probes[candidate.probeKey()] ?: ProbeResult.NOT_PROBEABLE
                    val verify = verifier.verify(candidate)
                    results.add(
                        ScanResult(
                            candidate = candidate,
                            probe = probe,
                            verify = verify,
                            score = ScanRanker.score(probe, verify),
                            timestampMs = System.currentTimeMillis(),
                            networkName = network,
                        ),
                    )
                }
                if (terminal == null) {
                    terminal = ScanState.Finished(ScanRanker.rank(results))
                }
            } catch (e: CancellationException) {
                logger.info("Scan cancelled with {} verified results.", results.size)
                terminal = ScanState.Cancelled(ScanRanker.rank(results))
                throw e
            } catch (e: Exception) {
                logger.error("Scan failed.", e)
                terminal = ScanState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                withContext(NonCancellable) {
                    cleanUp(priorMode, wasTunnelUp || hadHeldTunnel || priorMode != null)
                    val finalState = terminal ?: ScanState.Failed("The scan ended unexpectedly.")
                    // What was measured is worth keeping even when the scan was stopped.
                    val kept =
                        when (finalState) {
                            is ScanState.Finished -> finalState.results
                            is ScanState.Cancelled -> finalState.results
                            else -> results.sortedByDescending { it.score }
                        }
                    runCatching { store.save(network, kept) }
                        .onFailure { logger.error("Could not save the scan results.", it) }
                    logger.info("Scan ended: {}", finalState.javaClass.simpleName)
                    _state.value = finalState
                }
            }
        }

        /** The user's session, if any, goes down first, as a system disconnect: nothing is stood down. */
        private suspend fun takeVpnDown() {
            if (!stateManager.isVPNActive()) return
            logger.info("Taking the VPN down before probing.")
            controller.disconnectAsync(SYSTEM_DISCONNECT)
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                stateManager.state.first { it.status == VPNState.Status.Disconnected }
            } ?: logger.warn("The VPN did not report disconnected within {} ms.", DISCONNECT_TIMEOUT_MS)
        }

        /**
         * Probes are plain sockets, so nothing may hold the device back while they run. The hold
         * (kill switch) is released here and put back by [cleanUp] through the normal policy path.
         * [ScanSession] stops anything from installing another while the scan runs.
         */
        private fun releaseBlockingHold() {
            if (!stateManager.isTunnelUp() && tunnelManager.get().releaseHeldTunnel()) {
                logger.info("Released the blocking hold for the length of the scan.")
            }
        }

        /**
         * Leaves the app as the scan found it. Order matters: the tunnel down, the preferences back,
         * and only then the flag cleared, so the hooks stay quiet until nothing of ours is left.
         */
        private suspend fun cleanUp(
            priorMode: ConnectionPolicy.ServiceMode?,
            hadSession: Boolean,
        ) {
            runCatching {
                if (stateManager.isVPNActive()) {
                    controller.disconnectAsync(SYSTEM_DISCONNECT)
                    withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                        stateManager.state.first { it.status == VPNState.Status.Disconnected }
                    }
                }
            }.onFailure { logger.error("Could not take the tunnel down after the scan.", it) }
            runCatching { guard.endSession() }
                .onFailure { logger.error("Could not restore preferences after the scan.", it) }
            ScanSession.end()
            // Settled through the ordinary path now that the hooks are quiet: ConnectionPolicy decides
            // whether blocking applies (a held TUN is established again), and the service publishes
            // the mode. The end is chosen to reproduce the mode the scan found. Skipped when the
            // scan found a plain idle app, which has nothing to settle.
            if (hadSession) {
                controller.endSession(sessionEndFor(priorMode))
            }
        }

        companion object {
            private const val DISCONNECT_TIMEOUT_MS = 8_000L

            /** The session end that leaves the service in [mode] again. Null (connected or idle) is a system end. */
            internal fun sessionEndFor(mode: ConnectionPolicy.ServiceMode?): ConnectionPolicy.SessionEnd =
                when (mode) {
                    is ConnectionPolicy.ServiceMode.Failed -> ConnectionPolicy.SessionEnd.ConnectFailed
                    ConnectionPolicy.ServiceMode.NoNetwork -> ConnectionPolicy.SessionEnd.NoNetwork
                    ConnectionPolicy.ServiceMode.AwaitingSession -> ConnectionPolicy.SessionEnd.AwaitingSession
                    ConnectionPolicy.ServiceMode.AuthFailed -> ConnectionPolicy.SessionEnd.AuthFailed
                    ConnectionPolicy.ServiceMode.Paused -> ConnectionPolicy.SessionEnd.UserPause
                    is ConnectionPolicy.ServiceMode.Watching ->
                        if (mode.reason == WatchReason.StoodDownByUser) {
                            ConnectionPolicy.SessionEnd.UserDisconnect
                        } else {
                            ConnectionPolicy.SessionEnd.SystemDisconnect
                        }

                    else -> ConnectionPolicy.SessionEnd.SystemDisconnect
                }
        }
    }
