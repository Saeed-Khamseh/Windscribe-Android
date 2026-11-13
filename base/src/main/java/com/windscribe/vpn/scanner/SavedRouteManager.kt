package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.autoconnection.PreferredRoute
import com.windscribe.vpn.backend.VPNState
import com.windscribe.vpn.backend.utils.ConnectSource
import com.windscribe.vpn.localdatabase.LocalDbInterface
import com.windscribe.vpn.state.DeviceStateManager
import com.windscribe.vpn.state.NetworkInfoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Try the saved route first": for a normal user connect on a network with saved working scan
 * results, picks the best saved route, applies its tweak without drifting the user's settings, and
 * records how the first attempt went so a route that stops working is demoted.
 *
 * `AutoConnectionManager.connectInForeground` calls [resolve] before the attempt and
 * [recordOutcome] after it. The failover is untouched.
 */
@Singleton
class SavedRouteManager
    @Inject
    constructor(
        private val prefs: PreferencesHelper,
        private val store: ScanResultStore,
        private val deviceStateManager: DeviceStateManager,
        private val networkInfoManager: NetworkInfoManager,
        private val localDb: LocalDbInterface,
        private val baseline: TweakBaseline,
        private val settings: ScannerSettings,
        private val scope: CoroutineScope,
    ) {
        private val logger = LoggerFactory.getLogger(SCANNER_LOG)

        private data class Attempt(
            val network: String,
            val candidate: ScanCandidate,
        )

        @Volatile
        private var attempt: Attempt? = null

        /** The route to try first: [explicit] if given, else a saved one for a user connect, else null. */
        suspend fun resolve(
            source: ConnectSource,
            explicit: PreferredRoute?,
        ): PreferredRoute? {
            attempt = null
            if (explicit != null) return explicit
            if (source != ConnectSource.User) return null
            return try {
                choose()
            } catch (e: Exception) {
                logger.error("Saved route lookup failed; connecting normally.", e)
                null
            }
        }

        private suspend fun choose(): PreferredRoute? {
            // A scan is changing the same preferences; leave the baseline alone until it is over.
            if (ScanSession.active) {
                logger.info("No saved route: a scan is running.")
                return null
            }
            val network = deviceStateManager.getCurrentNetworkName()
            val skip =
                SavedRouteGate.skipReason(
                    SavedRouteGate.Inputs(
                        enabled = settings.trySavedFirst.value,
                        scanActive = false,
                        networkName = network,
                        connectionMode = prefs.connectionMode,
                        perNetworkPreferred = networkInfoManager.networkInfo.value?.isPreferredOn == true,
                        connectingToStaticIp = prefs.isConnectingToStaticIp,
                        connectingToConfig = prefs.isConnectingToConfigured,
                        decoyTraffic = prefs.isDecoyTrafficOn,
                    ),
                )
            if (skip != null || network == null) {
                logger.info("No saved route: {}.", skip)
                baseline.reconcile()
                return null
            }
            val cityId = prefs.selectedCity
            val pick =
                SavedRouteChooser.choose(
                    withContext(Dispatchers.IO) { store.loadSaved(network) },
                    cityId,
                    System.currentTimeMillis(),
                )
            if (pick == null) {
                logger.info("No saved route: nothing usable saved for '{}'.", network)
                baseline.reconcile()
                return null
            }
            val candidate = pick.route.result.candidate
            val hostname =
                if (pick.cityMatch) {
                    val nodes = withContext(Dispatchers.IO) { localDb.getServersByDatacenter(cityId) }.filter { it.isConnectable }
                    ScanRouteConnector.routeFor(candidate, nodes.map { it.hostname }).hostname
                } else {
                    null
                }
            baseline.applyRouteTweak(candidate.tweak)
            attempt = Attempt(network, candidate)
            logger.info(
                "Saved route used on '{}': {} (city match={}, node pinned={}).",
                network,
                candidate,
                pick.cityMatch,
                hostname != null,
            )
            return PreferredRoute(candidate.protocol, candidate.port.toString(), hostname)
        }

        /** The outcome of the connect that [resolve] chose a saved route for. Ignores any other. */
        fun recordOutcome(result: VPNState) {
            val current = attempt ?: return
            attempt = null
            val error = result.error?.error
            // The user cancelling says nothing about the route.
            if (error == VPNState.ErrorType.UserDisconnect || error == VPNState.ErrorType.UserReconnect) return
            val success = result.status == VPNState.Status.Connected
            scope.launch(Dispatchers.IO) {
                val failures = runCatching { store.recordOutcome(current.network, current.candidate, success) }.getOrNull() ?: return@launch
                logger.info(
                    "Saved route {} {}; consecutive failures {}{}.",
                    current.candidate,
                    if (success) "connected" else "failed",
                    failures,
                    if (failures >= SavedRouteChooser.DEMOTE_AFTER_FAILURES) " (demoted until a scan verifies it again)" else "",
                )
            }
        }
    }
