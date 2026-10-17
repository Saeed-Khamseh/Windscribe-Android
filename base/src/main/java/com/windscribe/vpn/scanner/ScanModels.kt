package com.windscribe.vpn.scanner

import androidx.annotation.Keep

@Keep
data class ScanProtocol(
    val id: String,
    val name: String,
    val ports: List<String>,
    val enabled: Boolean = true,
)

@Keep
data class ScanSettings(
    val locationIds: List<Int> = emptyList(),
    val protocols: List<ScanProtocol> = defaultScanProtocols(),
    val stopOnSuccess: Boolean = false,
    val resume: Boolean = true,
    val pingTarget: String = "1.1.1.1",
    val pingCount: Int = 5,
    val connectionTimeout: Int = 20,
    val settleDelay: Int = 2,
) {
    fun isValid(): Boolean =
        locationIds.isNotEmpty() &&
            protocols.any { it.enabled && it.ports.isNotEmpty() } &&
            protocols.all { p -> p.ports.all { it.toIntOrNull() in 1..65535 } } &&
            pingCount in 1..20 &&
            connectionTimeout in 3..300 &&
            settleDelay in 0..60 &&
            pingTarget.length in 1..253 &&
            pingTarget.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9.:-]*"))
}

fun defaultScanProtocols(): List<ScanProtocol> =
    listOf(
        ScanProtocol("wg", "WireGuard", listOf("443")),
        ScanProtocol("tcp", "OpenVPN TCP", listOf("443")),
        ScanProtocol("stunnel", "Stealth", listOf("443")),
        ScanProtocol("wstunnel", "WStunnel", listOf("443")),
        ScanProtocol("udp", "OpenVPN UDP", listOf("443"), false),
        ScanProtocol("ikev2", "IKEv2", listOf("500"), false),
    )

@Keep
data class ScanLocation(
    val id: Int,
    val country: String,
    val countryCode: String,
    val city: String,
    val nickname: String,
    val hostname: String,
) {
    val label: String get() = "$country · $city"
}

@Keep
data class ScanRoute(
    val location: ScanLocation,
    val protocol: String,
    val protocolName: String,
    val port: String,
) {
    val key: String get() = "${location.id}:${location.hostname}:$protocol:$port"
}

@Keep
data class ScanResult(
    val route: ScanRoute,
    val samples: List<Int> = emptyList(),
    val failure: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val working: Boolean get() = failure == null && samples.any { it >= 0 }
    val averagePing: Int? get() =
        samples
            .filter { it >= 0 }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()
    val packetLoss: Int get() = if (samples.isEmpty()) 100 else samples.count { it < 0 } * 100 / samples.size
}

enum class ScanStep { Idle, Disconnecting, Connecting, Settling, Measuring, Saving, Stopping }

data class ScanProgress(
    val running: Boolean = false,
    val route: ScanRoute? = null,
    val step: ScanStep = ScanStep.Idle,
    val total: Int = 0,
    val results: List<ScanResult> = emptyList(),
    val message: String? = null,
) {
    val best: ScanResult? get() = rankedResults(results).firstOrNull()
    val completed: Int get() = results.size
}

fun rankedResults(results: List<ScanResult>): List<ScanResult> =
    results.filter { it.working }.sortedWith(compareBy<ScanResult> { it.packetLoss }.thenBy { it.averagePing })

fun buildScanPlan(
    settings: ScanSettings,
    locations: List<ScanLocation>,
): List<ScanRoute> =
    settings.protocols
        .filter { it.enabled }
        .flatMap { protocol ->
            locations.filter { it.id in settings.locationIds }.flatMap { location ->
                protocol.ports.distinct().map { port -> ScanRoute(location, protocol.id, protocol.name, port) }
            }
        }.distinctBy { it.key }

fun scanCsv(results: List<ScanResult>): String {
    fun quote(text: String): String {
        // Spreadsheet applications interpret formula prefixes even inside CSV quotes.
        val safe = if (text.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$text" else text
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    return "Country,City,Server,Protocol,Port,Status,Ping ms,Packet loss %,Time\r\n" +
        results.joinToString("\r\n") {
            listOf(
                it.route.location.country,
                it.route.location.city,
                it.route.location.hostname,
                it.route.protocolName,
                it.route.port,
                it.failure ?: "Working",
                it.averagePing?.toString() ?: "",
                it.packetLoss.toString(),
                it.timestamp.toString(),
            ).joinToString(",", transform = ::quote)
        }
}
