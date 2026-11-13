package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_IKev2
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_STEALTH
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_UDP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WIRE_GUARD
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WS_TUNNEL

/**
 * Turns an inventory and a request into the ordered list of routes to try.
 *
 * Pure: no Android, no I/O. The order is the priority order, because the probe limit truncates the
 * tail and verification picks from the head. It sorts by protocol, then tweak, then port, then city,
 * then node, so a truncated list still covers every city and node for the best protocols.
 */
object ScanCandidateGenerator {
    /** By censorship resistance: the first is the most likely to survive a filtered network. */
    val PROTOCOL_ORDER: List<String> =
        listOf(PROTO_WS_TUNNEL, PROTO_STEALTH, PROTO_TCP, PROTO_WIRE_GUARD, PROTO_IKev2, PROTO_UDP)

    const val QUICK_CITIES = 8
    const val QUICK_NODES_PER_CITY = 2
    private const val PREFERRED_PORT = 443

    fun generate(
        inventory: ScanInventory,
        request: ScanRequest,
    ): List<ScanCandidate> {
        val cities = selectCities(inventory, request)
        val nodesPerCity =
            when (request) {
                is ScanRequest.Quick -> QUICK_NODES_PER_CITY
                is ScanRequest.Custom -> request.nodesPerCity.coerceAtLeast(1)
            }
        val protocols =
            when (request) {
                is ScanRequest.Quick -> PROTOCOL_ORDER
                is ScanRequest.Custom ->
                    PROTOCOL_ORDER.filter { request.protocols.isEmpty() || it in request.protocols }
            }
        val maxProbes = request.limits.maxProbes
        val probeKeys = HashSet<ProbeKey>()
        val out = ArrayList<ScanCandidate>()
        for (protocol in protocols) {
            val ports = portsFor(protocol, inventory, request)
            for (tweak in tweaksFor(protocol, inventory, request)) {
                for (port in ports) {
                    for (city in cities) {
                        for (node in city.nodes.take(nodesPerCity)) {
                            val ip = ipFor(protocol, node)
                            if (ip.isBlank()) continue
                            val candidate =
                                ScanCandidate(
                                    cityId = city.id,
                                    cityName = city.name,
                                    nodeHostname = node.hostname,
                                    nodeIp = ip,
                                    protocol = protocol,
                                    port = port,
                                    tweak = tweak,
                                )
                            if (candidate.probeKind != ProbeKind.NONE) {
                                val key = candidate.probeKey()
                                // A tweak variant of an endpoint already in the list costs no probe.
                                if (key !in probeKeys) {
                                    if (probeKeys.size >= maxProbes) continue
                                    probeKeys.add(key)
                                }
                            }
                            out.add(candidate)
                        }
                    }
                }
            }
        }
        return out
    }

    /** The address [protocol] really dials on [node], matching what `VPNProfileCreator` uses. */
    fun ipFor(
        protocol: String,
        node: ScanNode,
    ): String =
        when (protocol) {
            PROTO_IKev2, PROTO_WS_TUNNEL -> node.ip
            PROTO_TCP, PROTO_UDP -> node.ip2
            PROTO_STEALTH, PROTO_WIRE_GUARD -> node.ip3
            else -> ""
        }

    private fun selectCities(
        inventory: ScanInventory,
        request: ScanRequest,
    ): List<ScanCity> {
        val usable = inventory.cities.filter { it.nodes.isNotEmpty() }
        return when (request) {
            is ScanRequest.Quick ->
                usable
                    .sortedWith(
                        compareByDescending<ScanCity> { it.isFavourite }
                            .thenBy { it.pingMs ?: Int.MAX_VALUE },
                    ).take(QUICK_CITIES)

            is ScanRequest.Custom -> {
                val byId = usable.associateBy { it.id }
                request.cityIds.distinct().mapNotNull { byId[it] }
            }
        }
    }

    /** 443 first where offered, then the port map's order. */
    private fun portsFor(
        protocol: String,
        inventory: ScanInventory,
        request: ScanRequest,
    ): List<Int> {
        val available = inventory.portMap[protocol]?.takeIf { it.isNotEmpty() } ?: listOf(defaultPort(protocol))
        val chosen =
            when (request) {
                is ScanRequest.Quick -> available
                is ScanRequest.Custom ->
                    if (request.ports.isEmpty()) available else available.filter { it in request.ports }
            }
        return chosen.distinct().sortedBy { if (it == PREFERRED_PORT) 0 else 1 }
    }

    private fun defaultPort(protocol: String): Int =
        when (protocol) {
            PROTO_IKev2 -> PreferencesKeyConstants.DEFAULT_IKEV2_PORT
            PROTO_UDP -> PreferencesKeyConstants.DEFAULT_UDP_LEGACY_PORT
            PROTO_TCP -> PreferencesKeyConstants.DEFAULT_TCP_LEGACY_PORT
            PROTO_STEALTH -> PreferencesKeyConstants.DEFAULT_STEALTH_LEGACY_PORT
            PROTO_WS_TUNNEL -> PreferencesKeyConstants.DEFAULT_WS_TUNNEL_LEGACY_PORT
            else -> PreferencesKeyConstants.DEFAULT_WIRE_GUARD_PORT
        }.toInt()

    /** [ScanTweak.None] first, always. */
    private fun tweaksFor(
        protocol: String,
        inventory: ScanInventory,
        request: ScanRequest,
    ): List<ScanTweak> {
        val extra: List<ScanTweak> =
            when (request) {
                is ScanRequest.Quick ->
                    when (protocol) {
                        PROTO_WS_TUNNEL, PROTO_STEALTH -> listOf(ScanTweak.ExtraTlsPadding)
                        PROTO_TCP -> listOf(ScanTweak.ProtocolTweaks)
                        PROTO_WIRE_GUARD -> inventory.amneziaPresetIds.map { ScanTweak.AmneziaWg(it) }
                        else -> emptyList()
                    }

                is ScanRequest.Custom -> request.tweaks.filter { it != ScanTweak.None && it.appliesTo(protocol) }
            }
        return (listOf(ScanTweak.None) + extra).distinct()
    }
}
