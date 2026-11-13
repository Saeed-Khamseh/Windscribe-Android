package com.windscribe.vpn.autoconnection

/**
 * A protocol and port (and optionally a node) to try first on one user connect.
 *
 * Passed to [AutoConnectionManager.connectInForeground]. It is a parameter rather than state held
 * somewhere, so it can never outlive the connect it was meant for. The normal failover carries on
 * after it if it fails. [hostname] only applies to the first attempt: a retry on another node is the
 * point of the retry.
 */
data class PreferredRoute(
    val protocol: String,
    val port: String,
    val hostname: String? = null,
) {
    fun toProtocolInformation(): ProtocolInformation =
        ProtocolInformation(
            protocol = protocol,
            port = port,
            description = "",
            type = ProtocolConnectionStatus.Disconnected,
        )
}
