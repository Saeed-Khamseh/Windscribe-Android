package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_IKev2
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_STEALTH
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_TCP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_UDP
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WIRE_GUARD
import com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WS_TUNNEL

/** Log name shared by every scanner class, so field logs can be filtered with `scanner`. */
internal const val SCANNER_LOG = "scanner"

/**
 * An anti-censorship option applied on top of the user's current settings for one verification.
 *
 * Modelled on how `VPNProfileCreator`, `ProxyTunnelManager` and `WgConfigRepository` consume the
 * existing preferences. [None] leaves the user's settings exactly as they are.
 */
sealed class ScanTweak {
    /** Stable text form for persistence. */
    abstract val key: String

    /** Protocols this tweak changes anything for. */
    abstract val protocols: Set<String>

    fun appliesTo(protocol: String): Boolean = protocol in protocols

    /** No change to the user's settings. Always the first tweak tried. */
    data object None : ScanTweak() {
        override val key = "none"
        override val protocols = ALL_PROTOCOLS
    }

    /** Protocol tweaks switched on: `udp-stuffing` and `tcp-split-reset` for OpenVPN TCP/UDP. */
    data object ProtocolTweaks : ScanTweak() {
        override val key = "protocol_tweaks"
        override val protocols = setOf(PROTO_TCP, PROTO_UDP)
    }

    /** AmneziaWG obfuscation with the server-provided preset [presetId] (WireGuard). */
    data class AmneziaWg(
        val presetId: String,
    ) : ScanTweak() {
        override val key get() = "$AMNEZIA_PREFIX$presetId"
        override val protocols = setOf(PROTO_WIRE_GUARD)
    }

    /** Extra TLS padding (protocol tweaks on, plus the padding switch) for Stealth / WStunnel. */
    data object ExtraTlsPadding : ScanTweak() {
        override val key = "tls_padding"
        override val protocols = setOf(PROTO_STEALTH, PROTO_WS_TUNNEL)
    }

    /** A custom TLS server name (SNI) for Stealth / WStunnel. */
    data class TlsServerName(
        val sni: String,
    ) : ScanTweak() {
        override val key get() = "$SNI_PREFIX$sni"
        override val protocols = setOf(PROTO_STEALTH, PROTO_WS_TUNNEL)
    }

    /** Alternate server routing for the API calls a WireGuard connect makes (key exchange). */
    data object AlternateRouting : ScanTweak() {
        override val key = "alt_routing"
        override val protocols = setOf(PROTO_WIRE_GUARD)
    }

    companion object {
        private const val AMNEZIA_PREFIX = "amnezia:"
        private const val SNI_PREFIX = "sni:"

        val ALL_PROTOCOLS: Set<String> =
            setOf(PROTO_WS_TUNNEL, PROTO_STEALTH, PROTO_TCP, PROTO_WIRE_GUARD, PROTO_IKev2, PROTO_UDP)

        fun fromKey(key: String): ScanTweak =
            when {
                key == ProtocolTweaks.key -> ProtocolTweaks
                key == ExtraTlsPadding.key -> ExtraTlsPadding
                key == AlternateRouting.key -> AlternateRouting
                key.startsWith(AMNEZIA_PREFIX) -> AmneziaWg(key.removePrefix(AMNEZIA_PREFIX))
                key.startsWith(SNI_PREFIX) -> TlsServerName(key.removePrefix(SNI_PREFIX))
                else -> None
            }
    }
}

/** What a probe does to find out whether an endpoint answers. */
enum class ProbeKind {
    /** TCP connect only. */
    TCP_CONNECT,

    /** TCP connect, then a TLS handshake with the configured SNI. */
    TLS_HANDSHAKE,

    /** IKE_SA_INIT over UDP; any reply counts. */
    IKE,

    /** No honest unauthenticated probe exists (WireGuard, OpenVPN UDP). */
    NONE,
}

/** The endpoint a probe tests. Candidates that differ only by tweak share one key. */
data class ProbeKey(
    val kind: ProbeKind,
    val host: String,
    val port: Int,
    val sni: String? = null,
)

/**
 * One route to try: `city -> node -> protocol -> port -> tweak`.
 *
 * [protocol] is a `PreferencesKeyConstants.PROTO_*` value. [nodeIp] is the address that protocol
 * really dials on the node, which differs per protocol (see [ScanCandidateGenerator]).
 */
data class ScanCandidate(
    val cityId: Int,
    val cityName: String,
    val nodeHostname: String,
    val nodeIp: String,
    val protocol: String,
    val port: Int,
    val tweak: ScanTweak = ScanTweak.None,
) {
    val probeKind: ProbeKind
        get() =
            when (protocol) {
                PROTO_TCP -> ProbeKind.TCP_CONNECT
                PROTO_STEALTH, PROTO_WS_TUNNEL -> ProbeKind.TLS_HANDSHAKE
                PROTO_IKev2 -> ProbeKind.IKE
                else -> ProbeKind.NONE
            }

    fun probeKey(): ProbeKey =
        ProbeKey(
            kind = probeKind,
            host = nodeIp,
            port = port,
            sni = (tweak as? ScanTweak.TlsServerName)?.sni.takeIf { probeKind == ProbeKind.TLS_HANDSHAKE },
        )

    override fun toString(): String = "$cityName/$nodeHostname $protocol:$port [${tweak.key}]"
}

/** [reachable] is null when the protocol has no honest unauthenticated probe. */
data class ProbeResult(
    val reachable: Boolean?,
    val latencyMs: Long? = null,
    val detail: String = "",
) {
    companion object {
        val NOT_PROBEABLE = ProbeResult(reachable = null, detail = "no unauthenticated probe for this protocol")
    }
}

data class VerifyResult(
    val connected: Boolean,
    val connectMs: Long? = null,
    val trafficOk: Boolean = false,
    val latencyMs: Long? = null,
    val downloadKbps: Long? = null,
    val error: String? = null,
)

data class ScanResult(
    val candidate: ScanCandidate,
    val probe: ProbeResult,
    val verify: VerifyResult?,
    val score: Double,
    val timestampMs: Long,
    val networkName: String,
) {
    /** Only a verified route that carried a download counts as working. */
    val isWorking: Boolean get() = verify?.trafficOk == true

    companion object {
        const val UNKNOWN_NETWORK = "unknown"
    }
}

data class ScanLimits(
    val maxProbes: Int,
    val maxVerifications: Int,
    val timeBudgetMs: Long,
) {
    companion object {
        val QUICK = ScanLimits(maxProbes = 600, maxVerifications = 20, timeBudgetMs = 6 * 60 * 1000L)
        val CUSTOM = ScanLimits(maxProbes = 1500, maxVerifications = 30, timeBudgetMs = 10 * 60 * 1000L)
    }
}

sealed class ScanRequest {
    abstract val limits: ScanLimits

    /** Favourites then fastest cities, the most censorship-resistant protocols first. */
    data class Quick(
        override val limits: ScanLimits = ScanLimits.QUICK,
    ) : ScanRequest()

    /**
     * An explicit scope. Empty [protocols] means all, empty [ports] means the port map's ports for
     * each protocol, and [ScanTweak.None] is always tried first whatever [tweaks] holds.
     */
    data class Custom(
        val cityIds: List<Int>,
        val protocols: List<String> = emptyList(),
        val ports: List<Int> = emptyList(),
        val tweaks: List<ScanTweak> = emptyList(),
        val nodesPerCity: Int = 2,
        override val limits: ScanLimits = ScanLimits.CUSTOM,
    ) : ScanRequest()
}

sealed class ScanState {
    data object Idle : ScanState()

    data class Probing(
        val done: Int,
        val total: Int,
    ) : ScanState()

    data class Verifying(
        val index: Int,
        val total: Int,
        val current: ScanCandidate,
    ) : ScanState()

    data class Finished(
        val results: List<ScanResult>,
    ) : ScanState()

    data class Cancelled(
        val results: List<ScanResult>,
    ) : ScanState()

    data class Failed(
        val reason: String,
    ) : ScanState()
}

// ---- Generator input: plain data, so the generator stays free of Android and database types.

data class ScanNode(
    val hostname: String,
    /** IKEv2 and WStunnel. */
    val ip: String,
    /** OpenVPN UDP and TCP. */
    val ip2: String,
    /** Stealth and WireGuard. */
    val ip3: String,
)

data class ScanCity(
    val id: Int,
    val name: String,
    val nodes: List<ScanNode>,
    val isFavourite: Boolean = false,
    /** Stored ping in ms, or null when none is known. */
    val pingMs: Int? = null,
)

data class ScanInventory(
    val cities: List<ScanCity>,
    /** Ports per protocol in port map order. A protocol missing here falls back to its default port. */
    val portMap: Map<String, List<Int>>,
    /** Ids of the server-provided AmneziaWG presets. */
    val amneziaPresetIds: List<String> = emptyList(),
)
