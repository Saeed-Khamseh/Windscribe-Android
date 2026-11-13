package com.windscribe.vpn.scanner

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * OpenVPN TCP: a TCP connect. Stealth and WStunnel: a TCP connect, then a TLS handshake with the
 * configured SNI.
 *
 * The handshake is the whole test. No application data is sent, so certificates are not verified:
 * a name mismatch on a node IP must not read as "unreachable".
 *
 * @param defaultSni the user's configured TLS server name, used when the key carries none. Blank
 * means no SNI, as the tunnel itself would do.
 */
class TcpTlsProber(
    private val defaultSni: () -> String = { "" },
) : EndpointProber {
    private val logger = LoggerFactory.getLogger(SCANNER_LOG)

    override fun handles(kind: ProbeKind): Boolean = kind == ProbeKind.TCP_CONNECT || kind == ProbeKind.TLS_HANDSHAKE

    override suspend fun probe(
        key: ProbeKey,
        timeoutMs: Long,
    ): ProbeResult {
        val socket = Socket()
        val startNs = System.nanoTime()
        return try {
            runCancellable(onCancel = { socket.close() }) { probeBlocking(socket, key, timeoutMs, startNs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The TLS layer can wrap the socket's timeout, so look one level down as well.
            if (e is SocketTimeoutException ||
                e.cause is SocketTimeoutException ||
                e.message?.contains("timed out", ignoreCase = true) == true
            ) {
                ProbeResult(false, null, "timeout")
            } else {
                ProbeResult(false, null, "${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (e: RuntimeException) {
            logger.debug("Probe of {} failed: {}", key, e.toString())
            ProbeResult(false, null, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun probeBlocking(
        socket: Socket,
        key: ProbeKey,
        timeoutMs: Long,
        startNs: Long,
    ): ProbeResult {
        val deadlineNs = startNs + timeoutMs * NANOS_PER_MILLI
        socket.connect(InetSocketAddress(InetAddress.getByName(key.host), key.port), timeoutMs.toInt().coerceAtLeast(1))
        if (key.kind != ProbeKind.TLS_HANDSHAKE) {
            return ProbeResult(true, elapsedMs(startNs), "tcp connect")
        }
        val remainingMs = (deadlineNs - System.nanoTime()) / NANOS_PER_MILLI
        if (remainingMs <= 0) {
            return ProbeResult(false, null, "timeout")
        }
        val tls = sslFactory.createSocket(socket, key.host, key.port, true) as SSLSocket
        tls.soTimeout = remainingMs.toInt()
        val sni = key.sni ?: defaultSni().takeIf { it.isNotBlank() }
        if (sni != null) {
            val params = tls.sslParameters
            try {
                params.serverNames = listOf(SNIHostName(sni))
                tls.sslParameters = params
            } catch (e: Throwable) {
                // An invalid name, or API < 24: carry on without it, as the tunnel would.
                logger.debug("Not setting SNI '{}': {}", sni, e.toString())
            }
        }
        tls.startHandshake()
        return ProbeResult(true, elapsedMs(startNs), "tls handshake${sni?.let { " sni=$it" } ?: ""}")
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Accepts any certificate: this is a reachability handshake that carries no data. */
        @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
        private val trustAll =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) {}

                override fun checkServerTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

        val sslFactory by lazy {
            SSLContext
                .getInstance("TLS")
                .apply { init(null, arrayOf<TrustManager>(trustAll), null) }
                .socketFactory
        }
    }
}
