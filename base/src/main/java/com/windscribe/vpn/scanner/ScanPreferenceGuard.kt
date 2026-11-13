package com.windscribe.vpn.scanner

import android.content.Context
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.apppreference.PreferencesKeyConstants
import com.windscribe.vpn.autoconnection.ProtocolConnectionStatus
import com.windscribe.vpn.repository.LocationRepository
import dagger.Lazy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lends the app's preferences to the scan and gives them back.
 *
 * A verification connects through the normal connect path, which reads the selected city, the
 * protocol and the Anti-Censorship preferences. So a scan changes those for the length of one
 * verification. Before it changes anything, the originals are written to their own file with a
 * commit, so a scan that dies (process killed, crash) is repaired by [repairIfInterrupted] on the
 * next start. The file is plain `SharedPreferences`, not DataStore: it has to be readable
 * synchronously and written before the first change.
 */
@Singleton
class ScanPreferenceGuard
    @Inject
    constructor(
        app: Windscribe,
        private val prefs: PreferencesHelper,
        private val locationRepository: Lazy<LocationRepository>,
    ) {
        private val logger = LoggerFactory.getLogger(SCANNER_LOG)
        private val store = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        private var snapshot: Snapshot? = null

        private data class Snapshot(
            val protocolTweaksMode: String,
            val unblockWgParam: String?,
            val extraTlsPadding: Boolean,
            val tlsServerName: String,
            val serverRoutingMode: String,
            val selectedCity: Int,
            val selectedProtocol: String,
            val selectedPort: String,
            val selectedProtocolType: String,
            val selectedIp: String?,
            val selectedNodeId: Int,
            val connectingToConfigured: Boolean,
            val connectingToStaticIp: Boolean,
        )

        /** Records the originals durably. Call once, before the first change. */
        @Synchronized
        fun beginSession() {
            val current = readCurrent()
            persist(current)
            snapshot = current
            logger.info("Saved preferences before scanning (city={}, protocol={}).", current.selectedCity, current.selectedProtocol)
        }

        /**
         * Points the connect path at [candidate]: selects its city, makes sure a city (not a static IP
         * or custom config) is what gets connected to, and applies its tweak. Suspends until the
         * location repository has published the new city, because the connect path reads it from there.
         */
        suspend fun apply(candidate: ScanCandidate) {
            synchronized(this) {
                check(snapshot != null) { "beginSession() was not called" }
                restoreTweaksLocked()
                prefs.isConnectingToConfigured = false
                prefs.isConnectingToStaticIp = false
                applyTweak(candidate.tweak)
            }
            locationRepository.get().setSelectedCity(candidate.cityId)
            val published =
                withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
                    locationRepository.get().selectedCity.first { it == candidate.cityId }
                }
            if (published == null) {
                logger.warn("Selected city {} was not published in time.", candidate.cityId)
            }
        }

        /** Puts the Anti-Censorship preferences back after a verification. */
        @Synchronized
        fun restoreTweaks() = restoreTweaksLocked()

        /** Puts everything back and forgets the snapshot. */
        @Synchronized
        fun endSession() {
            val saved = snapshot ?: readPersisted()
            if (saved != null) {
                restoreAll(saved)
            }
            clearPersisted()
            snapshot = null
        }

        /**
         * Undoes a scan that never finished. For app start: if the file says a scan was under way and
         * none is running now, restores what it saved. Returns whether anything was repaired.
         */
        @Synchronized
        fun repairIfInterrupted(): Boolean {
            if (ScanSession.active) return false
            val saved = readPersisted() ?: return false
            logger.warn("A scan was interrupted; restoring preferences (city={}, protocol={}).", saved.selectedCity, saved.selectedProtocol)
            restoreAll(saved)
            clearPersisted()
            snapshot = null
            return true
        }

        private fun applyTweak(tweak: ScanTweak) {
            when (tweak) {
                ScanTweak.None -> Unit
                ScanTweak.ProtocolTweaks -> prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL
                is ScanTweak.AmneziaWg -> {
                    prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL
                    prefs.selectedUnblockWgParam = tweak.presetId
                }

                ScanTweak.ExtraTlsPadding -> {
                    prefs.protocolTweaksMode = PreferencesKeyConstants.PROTOCOL_TWEAKS_MANUAL
                    prefs.extraTlsPaddingEnabled = true
                }

                is ScanTweak.TlsServerName -> prefs.tlsServerName = tweak.sni
                ScanTweak.AlternateRouting -> prefs.serverRoutingMode = PreferencesKeyConstants.SERVER_ROUTING_ALTERNATE
            }
        }

        private fun restoreTweaksLocked() {
            val saved = snapshot ?: return
            restoreTweakPrefs(saved)
        }

        // Each write only when it differs: a value that was never set stays unset, and no flow fires.
        private fun restoreTweakPrefs(saved: Snapshot) {
            if (prefs.protocolTweaksMode != saved.protocolTweaksMode) prefs.protocolTweaksMode = saved.protocolTweaksMode
            if (prefs.selectedUnblockWgParam != saved.unblockWgParam) prefs.selectedUnblockWgParam = saved.unblockWgParam
            if (prefs.extraTlsPaddingEnabled != saved.extraTlsPadding) prefs.extraTlsPaddingEnabled = saved.extraTlsPadding
            if (prefs.tlsServerName != saved.tlsServerName) prefs.tlsServerName = saved.tlsServerName
            if (prefs.serverRoutingMode != saved.serverRoutingMode) prefs.serverRoutingMode = saved.serverRoutingMode
        }

        private fun restoreAll(saved: Snapshot) {
            restoreTweakPrefs(saved)
            if (prefs.selectedProtocol != saved.selectedProtocol) prefs.selectedProtocol = saved.selectedProtocol
            if (prefs.selectedPort != saved.selectedPort) prefs.selectedPort = saved.selectedPort
            val type = runCatching { ProtocolConnectionStatus.valueOf(saved.selectedProtocolType) }.getOrNull()
            if (type != null && prefs.selectedProtocolType != type) prefs.selectedProtocolType = type
            if (prefs.selectedIp != saved.selectedIp) prefs.selectedIp = saved.selectedIp
            if (prefs.selectedNodeId != saved.selectedNodeId) prefs.selectedNodeId = saved.selectedNodeId
            if (prefs.isConnectingToConfigured != saved.connectingToConfigured) {
                prefs.isConnectingToConfigured = saved.connectingToConfigured
            }
            if (prefs.isConnectingToStaticIp != saved.connectingToStaticIp) {
                prefs.isConnectingToStaticIp = saved.connectingToStaticIp
            }
            // Through the repository, which also publishes the city the connect path reads.
            locationRepository.get().setSelectedCity(saved.selectedCity)
        }

        private fun readCurrent() =
            Snapshot(
                protocolTweaksMode = prefs.protocolTweaksMode,
                unblockWgParam = prefs.selectedUnblockWgParam,
                extraTlsPadding = prefs.extraTlsPaddingEnabled,
                tlsServerName = prefs.tlsServerName,
                serverRoutingMode = prefs.serverRoutingMode,
                selectedCity = prefs.selectedCity,
                selectedProtocol = prefs.selectedProtocol,
                selectedPort = prefs.selectedPort,
                selectedProtocolType = prefs.selectedProtocolType.name,
                selectedIp = prefs.selectedIp,
                selectedNodeId = prefs.selectedNodeId,
                connectingToConfigured = prefs.isConnectingToConfigured,
                connectingToStaticIp = prefs.isConnectingToStaticIp,
            )

        private fun persist(s: Snapshot) {
            // commit(), not apply(): this must be on disk before the first preference changes.
            store
                .edit()
                .putBoolean(K_ACTIVE, true)
                .putString(K_TWEAKS_MODE, s.protocolTweaksMode)
                .putString(K_WG_PARAM, s.unblockWgParam)
                .putBoolean(K_TLS_PADDING, s.extraTlsPadding)
                .putString(K_SNI, s.tlsServerName)
                .putString(K_ROUTING, s.serverRoutingMode)
                .putInt(K_CITY, s.selectedCity)
                .putString(K_PROTOCOL, s.selectedProtocol)
                .putString(K_PORT, s.selectedPort)
                .putString(K_PROTOCOL_TYPE, s.selectedProtocolType)
                .putString(K_IP, s.selectedIp)
                .putInt(K_NODE_ID, s.selectedNodeId)
                .putBoolean(K_CONFIGURED, s.connectingToConfigured)
                .putBoolean(K_STATIC, s.connectingToStaticIp)
                .commit()
        }

        private fun readPersisted(): Snapshot? {
            if (!store.getBoolean(K_ACTIVE, false)) return null
            return Snapshot(
                protocolTweaksMode = store.getString(K_TWEAKS_MODE, null) ?: return null,
                unblockWgParam = store.getString(K_WG_PARAM, null),
                extraTlsPadding = store.getBoolean(K_TLS_PADDING, false),
                tlsServerName = store.getString(K_SNI, "") ?: "",
                serverRoutingMode = store.getString(K_ROUTING, null) ?: return null,
                selectedCity = store.getInt(K_CITY, -1),
                selectedProtocol = store.getString(K_PROTOCOL, null) ?: return null,
                selectedPort = store.getString(K_PORT, null) ?: return null,
                selectedProtocolType = store.getString(K_PROTOCOL_TYPE, null) ?: ProtocolConnectionStatus.Disconnected.name,
                selectedIp = store.getString(K_IP, null),
                selectedNodeId = store.getInt(K_NODE_ID, 0),
                connectingToConfigured = store.getBoolean(K_CONFIGURED, false),
                connectingToStaticIp = store.getBoolean(K_STATIC, false),
            )
        }

        private fun clearPersisted() {
            store.edit().clear().commit()
        }

        private companion object {
            const val FILE = "scanner_saved_preferences"
            const val PUBLISH_TIMEOUT_MS = 2_000L
            const val K_ACTIVE = "active"
            const val K_TWEAKS_MODE = "protocol_tweaks_mode"
            const val K_WG_PARAM = "unblock_wg_param"
            const val K_TLS_PADDING = "extra_tls_padding"
            const val K_SNI = "tls_server_name"
            const val K_ROUTING = "server_routing_mode"
            const val K_CITY = "selected_city"
            const val K_PROTOCOL = "selected_protocol"
            const val K_PORT = "selected_port"
            const val K_PROTOCOL_TYPE = "selected_protocol_type"
            const val K_IP = "selected_ip"
            const val K_NODE_ID = "selected_node_id"
            const val K_CONFIGURED = "connecting_to_configured"
            const val K_STATIC = "connecting_to_static_ip"
        }
    }
