package com.windscribe.mobile.ui.scanner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windscribe.vpn.scanner.NetworkScanService
import com.windscribe.vpn.scanner.NetworkScanner
import com.windscribe.vpn.scanner.ScanLocation
import com.windscribe.vpn.scanner.ScanProgress
import com.windscribe.vpn.scanner.ScanRoute
import com.windscribe.vpn.scanner.ScanSettings
import com.windscribe.vpn.state.DeviceStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

abstract class ScannerViewModel : ViewModel() {
    abstract val settings: StateFlow<ScanSettings>
    abstract val locations: StateFlow<List<ScanLocation>>
    abstract val ports: StateFlow<Map<String, List<String>>>
    abstract val progress: StateFlow<ScanProgress>
    abstract val active: StateFlow<Boolean>
    abstract val network: StateFlow<DeviceStateManager.NetworkDetail?>
    abstract val loading: StateFlow<Boolean>
    abstract val error: StateFlow<String?>

    abstract fun update(transform: (ScanSettings) -> ScanSettings)

    abstract fun start(
        context: Context,
        route: ScanRoute? = null,
    )

    abstract fun stop(context: Context)

    abstract fun refresh()

    abstract fun clearError()
}

@HiltViewModel
class ScannerViewModelImpl
    @Inject
    constructor(
        private val scanner: NetworkScanner,
        device: DeviceStateManager,
    ) : ScannerViewModel() {
        override val network = device.networkDetail
        override val settings = scanner.settings
        override val locations = scanner.locations
        override val ports = scanner.ports
        override val progress = scanner.progress
        override val active = scanner.session.active
        private var pendingUpdate: Job? = null
        override val loading = MutableStateFlow(true)
        override val error = MutableStateFlow<String?>(null)

        init {
            refresh()
        }

        override fun refresh() {
            if (!active.value) loading.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    scanner.load()
                } catch (exception: Exception) {
                    error.value = exception.message
                } finally {
                    loading.value = false
                }
            }
        }

        override fun update(transform: (ScanSettings) -> ScanSettings) {
            val previous = pendingUpdate
            pendingUpdate =
                viewModelScope.launch(Dispatchers.IO) {
                    previous?.join()
                    try {
                        scanner.updateSettings(transform)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        error.value = exception.message
                    }
                }
        }

        override fun start(
            context: Context,
            route: ScanRoute?,
        ) {
            if (loading.value || active.value) return
            viewModelScope.launch {
                // Honor every selection made before Start, even if its Room write is still pending.
                pendingUpdate?.join()
                if (active.value) return@launch
                val problem = scanner.preflight(route)
                if (problem != null) {
                    error.value = problem
                    return@launch
                }
                try {
                    NetworkScanService.start(context, route)
                } catch (exception: Exception) {
                    error.value = exception.message ?: "Unable to start scanner"
                }
            }
        }

        override fun stop(context: Context) = NetworkScanService.stop(context)

        override fun clearError() {
            error.value = null
        }
    }
