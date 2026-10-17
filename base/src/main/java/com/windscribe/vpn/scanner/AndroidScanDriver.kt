package com.windscribe.vpn.scanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.autoconnection.ProtocolConnectionStatus
import com.windscribe.vpn.autoconnection.ProtocolInformation
import com.windscribe.vpn.backend.VPNState
import com.windscribe.vpn.backend.utils.WindVpnController
import com.windscribe.vpn.commonutils.WindUtilities
import com.windscribe.vpn.repository.LocationRepository
import com.windscribe.vpn.services.ping.Ping
import com.windscribe.vpn.state.DeviceStateManager
import com.windscribe.vpn.state.VPNConnectionStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject

class AndroidScanDriver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val controller: WindVpnController,
        private val state: VPNConnectionStateManager,
        private val preferences: PreferencesHelper,
        private val locations: LocationRepository,
        private val device: DeviceStateManager,
        private val session: ScanSession,
    ) : ScanDriver {
        private val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        private var currentId: UUID? = null
        private var expectedRoute: ScanRoute? = null

        override fun networkKey(): String {
            check(VpnService.prepare(context) == null) { "VPN permission was revoked. Grant permission before scanning again." }
            // VPN transport changes do not change this key. An underlying network change does.
            val networks =
                connectivity.allNetworks
                    .filter { network ->
                        connectivity.getNetworkCapabilities(network)?.let {
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        } == true
                    }.map { it.networkHandle }
                    .sorted()
            check(networks.isNotEmpty()) { "No network connection" }
            return "${preferences.userName}:${device.networkDetail.value}:$networks"
        }

        override suspend fun disconnect() {
            // Reject any late permission/service launch belonging to the previous attempt.
            currentId = null
            expectedRoute = null
            session.authorize(UUID.randomUUID())
            controller.disconnectForScanner()
            withTimeout(10_000) {
                while (controller.vpnBackendHolder.activeBackend?.active == true ||
                    state.state.value.status != VPNState.Status.Disconnected
                ) {
                    delay(50)
                }
            }
            // Allow backend teardown callbacks to drain before the next profile is created.
            delay(250)
        }

        override suspend fun connect(route: ScanRoute): Boolean {
            expectedRoute = route
            preferences.isConnectingToConfigured = false
            preferences.isConnectingToStaticIp = false
            preferences.globalUserConnectionPreference = true
            locations.setSelectedCity(route.location.id)
            locations.selectedCity.first { it == route.location.id }
            val id = UUID.randomUUID()
            currentId = id
            session.authorize(id)
            controller.connect(
                connectionId = id,
                protocolInformation = ProtocolInformation(route.protocol, route.port, "", ProtocolConnectionStatus.NextUp),
                hostname = route.location.hostname,
            )
            val terminal =
                state.state.first {
                    (it.connectionId == id && it.status in listOf(VPNState.Status.Connected, VPNState.Status.Disconnected)) ||
                        (it.status == VPNState.Status.Disconnected && controller.vpnBackendHolder.activeBackend?.active != true)
                }
            return terminal.connectionId == id && terminal.status == VPNState.Status.Connected && routeMatches()
        }

        override fun isRouteConnected(): Boolean {
            val network = connectivity.activeNetwork ?: return false
            val throughVpn = connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            return currentId != null &&
                state.state.value.connectionId == currentId &&
                state.isVPNConnected() &&
                throughVpn &&
                routeMatches()
        }

        private fun routeMatches(): Boolean =
            expectedRoute?.let {
                preferences.selectedCity == it.location.id &&
                    preferences.selectedProtocol == it.protocol &&
                    preferences.selectedPort == it.port &&
                    WindUtilities.hostnamesMatch(preferences.selectedIp, it.location.hostname)
            } == true

        override suspend fun ping(target: String): Int =
            withContext(Dispatchers.IO) {
                // ICMP only. A TCP fallback must not be mislabeled as ICMP packet loss.
                try {
                    check(isRouteConnected())
                    val network = checkNotNull(connectivity.activeNetwork)
                    val address = network.getAllByName(target).first()
                    val started = SystemClock.elapsedRealtime()
                    Ping.run(address, 2_000, network)
                    (SystemClock.elapsedRealtime() - started).toInt()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    -1
                }
            }
    }
