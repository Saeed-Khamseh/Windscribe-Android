package com.windscribe.vpn.scanner

import android.content.Context
import com.google.gson.Gson
import com.windscribe.vpn.R
import com.windscribe.vpn.api.response.PortMapResponse
import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.autoconnection.AutoConnectionManager
import com.windscribe.vpn.localdatabase.LocalDbInterface
import com.windscribe.vpn.repository.LocationRepository
import com.windscribe.vpn.repository.PortMapRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkScanner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LocalDbInterface,
        private val portMap: PortMapRepository,
        private val preferences: PreferencesHelper,
        private val locationRepository: LocationRepository,
        private val autoConnection: AutoConnectionManager,
        private val driver: AndroidScanDriver,
        private val store: ScanStore,
        val session: ScanSession,
    ) {
        private val _settings = MutableStateFlow(ScanSettings())
        val settings = _settings.asStateFlow()
        private val _locations = MutableStateFlow<List<ScanLocation>>(emptyList())
        val locations = _locations.asStateFlow()
        private val _ports = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        val ports = _ports.asStateFlow()
        private val _progress = MutableStateFlow(ScanProgress())
        val progress = _progress.asStateFlow()
        private val mutex = Mutex()
        private var checkpoint = ScanCheckpoint()
        private var loaded = false

        suspend fun load() =
            mutex.withLock {
                if (!loaded) {
                    checkpoint = store.load() ?: ScanCheckpoint()
                    _settings.value = checkpoint.settings
                    _progress.value = ScanProgress(total = checkpoint.plan.size, results = checkpoint.results)
                    loaded = true
                }
                if (!session.active.value) refreshLocations()
            }

        private suspend fun refreshLocations() {
            val nodes =
                db
                    .getAllServers()
                    .filter { it.isConnectable }
                    .sortedBy { it.id }
                    .groupBy { it.datacenterId }
            _locations.value =
                db
                    .getAllLocationsAsync()
                    .flatMap { group ->
                        group.datacenters.filter { it.status == 1 && nodes[it.id]?.isNotEmpty() == true }.map { city ->
                            ScanLocation(
                                city.id,
                                group.location?.name.orEmpty(),
                                group.location?.countryCode.orEmpty(),
                                city.nodeName.orEmpty(),
                                city.nickName.orEmpty(),
                                nodes.getValue(city.id).first().hostname,
                            )
                        }
                    }.sortedWith(compareBy<ScanLocation> { it.country }.thenBy { it.city }.thenBy { it.nickname })
            if (_settings.value.locationIds.isEmpty() && checkpoint.plan.isEmpty()) {
                _settings.value = _settings.value.copy(locationIds = _locations.value.take(6).map { it.id })
            }
            val maps =
                portMap.getPortMap().getOrNull()?.portmap ?: context.resources.openRawResource(R.raw.port_map).use {
                    Gson().fromJson(it.bufferedReader().readText(), PortMapResponse::class.java).portmap.orEmpty()
                }
            _ports.value =
                defaultScanProtocols().associate { protocol ->
                    protocol.id to (
                        maps
                            .firstOrNull { it.protocol == protocol.id }
                            ?.ports
                            ?.filter { it.toIntOrNull() in 1..65535 }
                            ?.takeIf { it.isNotEmpty() } ?: protocol.ports
                    )
                }
        }

        suspend fun updateSettings(transform: (ScanSettings) -> ScanSettings) =
            mutex.withLock {
                val settings = transform(_settings.value)
                check(!session.active.value)
                _settings.value = settings
                checkpoint = checkpoint.copy(settings = settings)
                store.save(checkpoint)
            }

        fun preflight(): String? =
            when {
                preferences.splitTunnelToggle -> "Turn off split tunneling before scanning so probes travel through the VPN."
                !settings.value.isValid() -> "Choose at least one location, protocol, and port, and check advanced settings."
                else ->
                    runCatching {
                        driver.networkKey()
                        null
                    }.getOrElse { it.message ?: "No network connection" }
            }

        fun reportFailure(message: String) {
            _progress.value = progress.value.copy(running = false, step = ScanStep.Idle, message = message)
        }

        suspend fun scan(connect: ScanRoute? = null) {
            check(session.active.value)
            val oldCity = preferences.selectedCity
            val oldConfigured = preferences.isConnectingToConfigured
            val oldStatic = preferences.isConnectingToStaticIp
            val oldProtocol = preferences.selectedProtocol
            val oldPort = preferences.selectedPort
            var keepConnected = false
            var failure: String? = null
            try {
                autoConnection.stopForScanner()
                if (connect != null) {
                    _progress.value = progress.value.copy(running = true, route = connect, step = ScanStep.Connecting, message = null)
                    withTimeout(15_000) { driver.disconnect() }
                    check(
                        withTimeout(settings.value.connectionTimeout * 1000L) { driver.connect(connect) },
                    ) { "Unable to connect to this route" }
                    keepConnected = true
                } else {
                    preflight()?.let { error(it) }
                    refreshLocations()
                    val settings = settings.value
                    val plan = buildScanPlan(settings, locations.value)
                    check(plan.isNotEmpty()) { "Selected locations are unavailable. Choose locations again." }
                    val network = driver.networkKey()
                    val canResume = canResumeScan(checkpoint, settings, plan, network)
                    val previous = if (canResume) checkpoint.results else emptyList()
                    checkpoint = ScanCheckpoint(settings, network, plan, previous, settings, preferences.userName)
                    store.save(checkpoint)
                    val results =
                        ScanEngine(driver).run(
                            settings,
                            plan,
                            previous,
                            network,
                            checkpoint = { results ->
                                checkpoint = checkpoint.copy(results = results)
                                store.save(checkpoint)
                            },
                            update = { _progress.value = it },
                        )
                    keepConnected = settings.stopOnSuccess && results.lastOrNull()?.working == true && driver.isRouteConnected()
                }
            } catch (cancelled: CancellationException) {
                failure = "Scan stopped. Completed results are saved."
                throw cancelled
            } catch (error: Exception) {
                failure = error.message ?: "Scan failed"
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (!keepConnected) {
                            _progress.value = progress.value.copy(running = true, step = ScanStep.Stopping)
                            withTimeout(15_000) { driver.disconnect() }
                            preferences.isConnectingToConfigured = oldConfigured
                            preferences.isConnectingToStaticIp = oldStatic
                            preferences.selectedProtocol = oldProtocol
                            preferences.selectedPort = oldPort
                            locationRepository.setSelectedCity(oldCity)
                            preferences.globalUserConnectionPreference = false
                        }
                    } catch (error: Exception) {
                        failure = "Could not finish VPN cleanup: ${error.message}. Disconnect the VPN in Android settings."
                    } finally {
                        autoConnection.reset()
                        _progress.value =
                            progress.value.copy(
                                running = false,
                                route = null,
                                step = ScanStep.Idle,
                                message = failure ?: if (keepConnected) "Connected to the working route." else "Scan complete.",
                            )
                        session.release()
                    }
                }
            }
        }
    }
