package com.windscribe.vpn.scanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide "a scan is in progress" flag.
 *
 * While it is set, a failed connect attempt belongs to the scan and to nobody else: the hook points
 * in the connection code read [active] to stay out of the way (no failover, no failure dialogs, no
 * reconnects, no settling of the service state). Only [NetworkScanner] sets it.
 */
object ScanSession {
    private val _active = MutableStateFlow(false)

    /** For observers; hook points read [active]. */
    val activeFlow: StateFlow<Boolean> = _active.asStateFlow()

    val active: Boolean
        get() = _active.value

    internal fun begin() {
        _active.value = true
    }

    internal fun end() {
        _active.value = false
    }
}
