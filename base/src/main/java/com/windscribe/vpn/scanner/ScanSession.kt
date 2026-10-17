package com.windscribe.vpn.scanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the VPN for the entire foreground-service lifetime, including cleanup. */
@Singleton
class ScanSession
    @Inject
    constructor() {
        private val _active = MutableStateFlow(false)
        val active = _active.asStateFlow()

        @Volatile private var connectionId: UUID? = null
        private val scanIds =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<UUID>()

        @Synchronized
        fun acquire(): Boolean {
            if (_active.value) return false
            _active.value = true
            return true
        }

        fun authorize(id: UUID) {
            scanIds.add(id)
            connectionId = id
        }

        fun allows(id: UUID): Boolean = if (id in scanIds) _active.value && connectionId == id else !_active.value

        fun release() {
            connectionId = null
            _active.value = false
        }
    }
