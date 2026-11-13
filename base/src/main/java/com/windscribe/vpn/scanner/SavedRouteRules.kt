package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants

/** A saved scan result and how many times in a row it failed as the first attempt of a connect. */
data class SavedRoute(
    val result: ScanResult,
    val consecutiveFailures: Int = 0,
)

/** The route chosen for a connect. [cityMatch] means it is in the city being connected, so its node may be pinned. */
data class RoutePick(
    val route: SavedRoute,
    val cityMatch: Boolean,
)

/** Pure rules for which saved route a normal connect tries first. */
object SavedRouteChooser {
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    const val DEMOTE_AFTER_FAILURES = 2

    private fun key(c: ScanCandidate) = listOf(c.cityId, c.nodeHostname, c.protocol, c.port, c.tweak.key).joinToString("|")

    /**
     * Usable routes, best first. Only the newest result of each route counts: if the latest scan
     * failed a route, an older success does not bring it back. Then: connected and carried traffic,
     * recent enough, not demoted.
     */
    fun usable(
        routes: List<SavedRoute>,
        nowMs: Long,
    ): List<SavedRoute> =
        routes
            .groupBy { key(it.result.candidate) }
            .values
            .map { group -> group.maxBy { it.result.timestampMs } }
            .filter {
                val verify = it.result.verify
                verify != null &&
                    verify.connected &&
                    verify.trafficOk &&
                    nowMs - it.result.timestampMs <= MAX_AGE_MS &&
                    it.consecutiveFailures < DEMOTE_AFTER_FAILURES
            }.sortedByDescending { it.result.score }

    /** The best route in [cityId] if there is one, else the best overall (protocol, port and tweak only). */
    fun choose(
        routes: List<SavedRoute>,
        cityId: Int,
        nowMs: Long,
    ): RoutePick? {
        val ok = usable(routes, nowMs)
        ok.firstOrNull { it.result.candidate.cityId == cityId }?.let { return RoutePick(it, cityMatch = true) }
        return ok.firstOrNull()?.let { RoutePick(it, cityMatch = false) }
    }

    /** A success resets the count; a failure adds one. */
    fun nextFailures(
        current: Int,
        success: Boolean,
    ): Int = if (success) 0 else current + 1
}

/** Pure: why a connect must not use a saved route, or null when it may. */
object SavedRouteGate {
    data class Inputs(
        val enabled: Boolean,
        val scanActive: Boolean,
        val networkName: String?,
        val connectionMode: String?,
        val perNetworkPreferred: Boolean,
        val connectingToStaticIp: Boolean,
        val connectingToConfig: Boolean,
        val decoyTraffic: Boolean,
    )

    fun skipReason(i: Inputs): String? =
        when {
            !i.enabled -> "the switch is off"
            i.scanActive -> "a scan is running"
            i.networkName.isNullOrBlank() || i.networkName == ScanResult.UNKNOWN_NETWORK -> "the network name is unknown"
            i.connectingToConfig -> "connecting to a custom config"
            i.connectingToStaticIp -> "connecting to a static IP"
            i.connectionMode != PreferencesKeyConstants.CONNECTION_MODE_AUTO -> "manual protocol mode"
            i.perNetworkPreferred -> "this network has a preferred protocol"
            i.decoyTraffic -> "decoy traffic is on"
            else -> null
        }
}
