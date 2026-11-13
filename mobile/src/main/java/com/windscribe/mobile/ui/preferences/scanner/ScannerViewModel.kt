package com.windscribe.mobile.ui.preferences.scanner

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.scanner.ConnectOutcome
import com.windscribe.vpn.scanner.NetworkScanner
import com.windscribe.vpn.scanner.ScanCandidateGenerator
import com.windscribe.vpn.scanner.ScanEstimate
import com.windscribe.vpn.scanner.ScanEstimator
import com.windscribe.vpn.scanner.ScanInventory
import com.windscribe.vpn.scanner.ScanInventoryLoader
import com.windscribe.vpn.scanner.ScanRequest
import com.windscribe.vpn.scanner.ScanResult
import com.windscribe.vpn.scanner.ScanResultStore
import com.windscribe.vpn.scanner.ScanRouteConnector
import com.windscribe.vpn.scanner.ScanService
import com.windscribe.vpn.scanner.ScanState
import com.windscribe.vpn.scanner.ScanTweak
import com.windscribe.vpn.scanner.isRunning
import com.windscribe.vpn.scanner.isTerminal
import com.windscribe.vpn.state.DeviceStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.inject.Inject

/** Which of the three screens the scan is on. */
enum class ScannerPhase { Setup, Running, Results }

sealed interface ScannerBanner {
    data class Failed(
        val reason: String,
    ) : ScannerBanner

    data object Stopped : ScannerBanner
}

/**
 * Everything the scanner screen shows apart from the custom picker. Built from
 * [NetworkScanner.state], so it is right after the screen or the process is recreated.
 */
data class ScannerUiState(
    val phase: ScannerPhase = ScannerPhase.Setup,
    /** Probing or Verifying while [phase] is Running. */
    val progress: ScanState? = null,
    val stopping: Boolean = false,
    /** Running: verified so far. Results: the final list. */
    val found: List<ScanResult> = emptyList(),
    /** Results only: the scan was stopped before it finished. */
    val resultsStopped: Boolean = false,
    /** Setup only: what earlier scans found on this network. */
    val saved: List<ScanResult> = emptyList(),
    val banner: ScannerBanner? = null,
)

data class ScannerCity(
    val id: Int,
    val name: String,
    val nodes: Int,
    val isFavourite: Boolean,
)

enum class CustomTweak { ProtocolTweaks, ExtraTlsPadding, AmneziaWg, AlternateRouting }

data class CustomScanState(
    val loading: Boolean = true,
    val cities: List<ScannerCity> = emptyList(),
    val query: String = "",
    val selectedCityIds: Set<Int> = emptySet(),
    /** Empty means all. */
    val protocols: Set<String> = emptySet(),
    val tweaks: Set<CustomTweak> = emptySet(),
    val amneziaAvailable: Boolean = false,
    val tlsServerName: String = "",
    val nodesPerCity: Int = DEFAULT_NODES_PER_CITY,
    val estimate: ScanEstimate? = null,
) {
    val visibleCities: List<ScannerCity>
        get() = if (query.isBlank()) cities else cities.filter { it.name.contains(query.trim(), ignoreCase = true) }

    companion object {
        const val DEFAULT_NODES_PER_CITY = 2
        val NODES_PER_CITY_CHOICES = listOf(1, 2, 3, 5)
    }
}

sealed interface ScannerEvent {
    /** A route was handed to the connect path: leave for the home screen, where it shows. */
    data object GoHome : ScannerEvent

    data class Message(
        @StringRes val resId: Int,
    ) : ScannerEvent
}

abstract class ScannerViewModel : ViewModel() {
    abstract val state: StateFlow<ScannerUiState>
    abstract val custom: StateFlow<CustomScanState>
    abstract val events: Flow<ScannerEvent>

    abstract fun startQuickScan()

    abstract fun startCustomScan()

    abstract fun stopScan()

    /** Leaves a finished scan's results for the setup view. */
    abstract fun scanAgain()

    abstract fun onRouteClicked(result: ScanResult)

    abstract fun onQueryChanged(query: String)

    abstract fun onCityToggled(cityId: Int)

    abstract fun onClearCities()

    abstract fun onProtocolToggled(protocol: String)

    abstract fun onTweakToggled(tweak: CustomTweak)

    abstract fun onTlsServerNameChanged(value: String)

    abstract fun onNodesPerCityChanged(nodes: Int)
}

@HiltViewModel
class ScannerViewModelImpl
    @Inject
    constructor(
        private val app: Windscribe,
        private val scanner: NetworkScanner,
        private val store: ScanResultStore,
        private val inventoryLoader: ScanInventoryLoader,
        private val connector: ScanRouteConnector,
        private val deviceStateManager: DeviceStateManager,
    ) : ScannerViewModel() {
        private val logger = LoggerFactory.getLogger("scanner")

        private val saved = MutableStateFlow<List<ScanResult>>(emptyList())

        // A scan that ended before this screen opened is not "just finished": opening the screen with
        // no scan running shows what is saved for this network, with its age.
        private val dismissed = MutableStateFlow<ScanState?>(scanner.state.value.takeIf { it.isTerminal() })
        private val stopRequested = MutableStateFlow(false)
        private val eventChannel = Channel<ScannerEvent>(Channel.BUFFERED)
        override val events: Flow<ScannerEvent> = eventChannel.receiveAsFlow()

        override val state: StateFlow<ScannerUiState> =
            combine(scanner.state, scanner.found, saved, dismissed, stopRequested) { scan, found, saved, dismissed, stopRequested ->
                toUiState(scan, found, saved, dismissed, stopRequested)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                toUiState(scanner.state.value, scanner.found.value, emptyList(), dismissed.value, false),
            )

        private val _custom = MutableStateFlow(CustomScanState())
        override val custom: StateFlow<CustomScanState> = _custom

        private var inventory: ScanInventory? = null
        private var estimateJob: Job? = null
        private var connecting = false

        init {
            refreshSaved()
            loadInventory()
            viewModelScope.launch {
                scanner.state.collect { scan ->
                    // The engine saves before it publishes the end, so the store is current here.
                    if (scan.isTerminal()) refreshSaved()
                }
            }
        }

        private fun currentNetwork(): String = deviceStateManager.getCurrentNetworkName() ?: ScanResult.UNKNOWN_NETWORK

        private fun toUiState(
            scan: ScanState,
            found: List<ScanResult>,
            saved: List<ScanResult>,
            dismissed: ScanState?,
            stopRequested: Boolean,
        ): ScannerUiState {
            val fresh = scan !== dismissed
            return when {
                scan.isRunning() -> ScannerUiState(ScannerPhase.Running, progress = scan, stopping = stopRequested, found = found)
                fresh && scan is ScanState.Finished && onThisNetwork(scan.results) ->
                    ScannerUiState(ScannerPhase.Results, found = scan.results)

                fresh && scan is ScanState.Cancelled && scan.results.isNotEmpty() && onThisNetwork(scan.results) ->
                    ScannerUiState(ScannerPhase.Results, found = scan.results, resultsStopped = true)

                else ->
                    ScannerUiState(
                        phase = ScannerPhase.Setup,
                        saved = saved,
                        banner =
                            when {
                                !fresh -> null
                                scan is ScanState.Failed -> ScannerBanner.Failed(scan.reason)
                                scan is ScanState.Cancelled -> ScannerBanner.Stopped
                                else -> null
                            },
                    )
            }
        }

        /** Results of a scan on another network say nothing about this one. */
        private fun onThisNetwork(results: List<ScanResult>): Boolean =
            results.firstOrNull()?.networkName.let {
                it == null ||
                    it == currentNetwork()
            }

        private fun refreshSaved() {
            viewModelScope.launch(Dispatchers.IO) {
                saved.value = runCatching { store.load(currentNetwork()) }.getOrDefault(emptyList())
            }
        }

        private fun loadInventory() {
            viewModelScope.launch(Dispatchers.IO) {
                val loaded =
                    runCatching { inventoryLoader.load() }
                        .onFailure {
                            logger.error(
                                "Could not load the scan inventory.",
                                it,
                            )
                        }.getOrNull()
                inventory = loaded
                _custom.update {
                    it.copy(
                        loading = false,
                        cities =
                            loaded
                                ?.cities
                                .orEmpty()
                                .sortedWith(
                                    compareByDescending<com.windscribe.vpn.scanner.ScanCity> { c ->
                                        c.isFavourite
                                    }.thenBy { c -> c.name.lowercase() },
                                ).map { c -> ScannerCity(c.id, c.name, c.nodes.size, c.isFavourite) },
                        amneziaAvailable = loaded?.amneziaPresetIds?.isNotEmpty() == true,
                    )
                }
            }
        }

        override fun startQuickScan() = start(ScanRequest.Quick())

        override fun startCustomScan() {
            val request = buildRequest() ?: return
            start(request)
        }

        private fun start(request: ScanRequest) {
            stopRequested.value = false
            if (!ScanService.start(app, request)) {
                eventChannel.trySend(ScannerEvent.Message(com.windscribe.vpn.R.string.scan_start_failed))
            }
        }

        override fun stopScan() {
            stopRequested.value = true
            ScanService.stop(app)
        }

        override fun scanAgain() {
            dismissed.value = scanner.state.value
        }

        override fun onRouteClicked(result: ScanResult) {
            if (connecting) return
            connecting = true
            viewModelScope.launch {
                try {
                    when (connector.connect(result)) {
                        ConnectOutcome.Started -> eventChannel.send(ScannerEvent.GoHome)
                        ConnectOutcome.ScanRunning ->
                            eventChannel.send(
                                ScannerEvent.Message(com.windscribe.vpn.R.string.scan_connect_scan_running),
                            )
                        ConnectOutcome.NoNetwork ->
                            eventChannel.send(
                                ScannerEvent.Message(com.windscribe.vpn.R.string.scan_connect_no_network),
                            )
                        ConnectOutcome.AccountBlocked ->
                            eventChannel.send(
                                ScannerEvent.Message(com.windscribe.vpn.R.string.scan_connect_account),
                            )
                        ConnectOutcome.LocationUnavailable ->
                            eventChannel.send(
                                ScannerEvent.Message(com.windscribe.vpn.R.string.scan_connect_unavailable),
                            )
                    }
                } finally {
                    connecting = false
                }
            }
        }

        override fun onQueryChanged(query: String) = _custom.update { it.copy(query = query) }

        override fun onCityToggled(cityId: Int) = changeSelection { it.copy(selectedCityIds = it.selectedCityIds.toggled(cityId)) }

        override fun onClearCities() = changeSelection { it.copy(selectedCityIds = emptySet()) }

        override fun onProtocolToggled(protocol: String) = changeSelection { it.copy(protocols = it.protocols.toggled(protocol)) }

        override fun onTweakToggled(tweak: CustomTweak) = changeSelection { it.copy(tweaks = it.tweaks.toggled(tweak)) }

        override fun onTlsServerNameChanged(value: String) = changeSelection { it.copy(tlsServerName = value) }

        override fun onNodesPerCityChanged(nodes: Int) = changeSelection { it.copy(nodesPerCity = nodes) }

        private fun changeSelection(change: (CustomScanState) -> CustomScanState) {
            _custom.update(change)
            recomputeEstimate()
        }

        /** Off the main thread and debounced: the generator walks every city, node, protocol and port. */
        private fun recomputeEstimate() {
            estimateJob?.cancel()
            estimateJob =
                viewModelScope.launch {
                    delay(ESTIMATE_DEBOUNCE_MS)
                    val request = buildRequest()
                    val loaded = inventory
                    val estimate =
                        if (request == null ||
                            loaded == null
                        ) {
                            null
                        } else {
                            withContext(Dispatchers.Default) { ScanEstimator.estimate(loaded, request) }
                        }
                    _custom.update { it.copy(estimate = estimate) }
                }
        }

        /** Null while no city is picked. */
        private fun buildRequest(): ScanRequest.Custom? {
            val current = _custom.value
            if (current.selectedCityIds.isEmpty()) return null
            val tweaks =
                buildList {
                    if (CustomTweak.ProtocolTweaks in current.tweaks) add(ScanTweak.ProtocolTweaks)
                    if (CustomTweak.ExtraTlsPadding in current.tweaks) add(ScanTweak.ExtraTlsPadding)
                    if (CustomTweak.AlternateRouting in current.tweaks) add(ScanTweak.AlternateRouting)
                    if (CustomTweak.AmneziaWg in current.tweaks) {
                        inventory?.amneziaPresetIds.orEmpty().forEach { add(ScanTweak.AmneziaWg(it)) }
                    }
                    current.tlsServerName
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { add(ScanTweak.TlsServerName(it)) }
                }
            return ScanRequest.Custom(
                // In the picker's order, so favourites come first.
                cityIds = current.cities.map { it.id }.filter { it in current.selectedCityIds },
                protocols = ScanCandidateGenerator.PROTOCOL_ORDER.filter { it in current.protocols },
                tweaks = tweaks,
                nodesPerCity = current.nodesPerCity,
            )
        }

        private companion object {
            const val ESTIMATE_DEBOUNCE_MS = 150L
        }
    }

private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item
