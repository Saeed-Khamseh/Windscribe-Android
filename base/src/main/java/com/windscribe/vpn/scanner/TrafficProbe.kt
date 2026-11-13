package com.windscribe.vpn.scanner

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** What the tunnel carried: connect timings to a public host, and one small download. */
data class TrafficResult(
    val trafficOk: Boolean,
    /** Median TCP connect time in ms, or null when none connected. */
    val latencyMs: Long? = null,
    val downloadKbps: Long? = null,
    val error: String? = null,
)

/** Measures traffic through whatever tunnel is up. Plain `java.net`: a measurement, not an API call. */
interface TrafficProbe {
    suspend fun measure(): TrafficResult
}

/**
 * Three TCP connect timings to an anycast host, then one ~256 KB HTTPS download capped at
 * [downloadCapMs]. [TrafficResult.trafficOk] requires the whole download to arrive: a tunnel that
 * handshakes but drops bulk data must not read as working.
 */
class JavaNetTrafficProbe(
    private val connectHost: String = "1.1.1.1",
    private val connectPort: Int = 443,
    private val downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES",
    private val expectedBytes: Long = DOWNLOAD_BYTES,
    private val connectTimeoutMs: Int = 3_000,
    private val downloadCapMs: Long = 10_000L,
) : TrafficProbe {
    private val logger = LoggerFactory.getLogger(SCANNER_LOG)

    override suspend fun measure(): TrafficResult {
        val timings = ArrayList<Long>()
        repeat(CONNECT_SAMPLES) {
            connectOnce()?.let { timings.add(it) }
        }
        val latency = timings.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        if (latency == null) {
            return TrafficResult(false, null, null, "no TCP connect to $connectHost:$connectPort through the tunnel")
        }
        return download(latency)
    }

    private suspend fun connectOnce(): Long? {
        val socket = Socket()
        val startNs = System.nanoTime()
        return try {
            runCancellable(onCancel = { socket.close() }) {
                socket.connect(InetSocketAddress(InetAddress.getByName(connectHost), connectPort), connectTimeoutMs)
                (System.nanoTime() - startNs) / 1_000_000L
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Traffic connect to {}:{} failed: {}", connectHost, connectPort, e.toString())
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun download(latency: Long): TrafficResult {
        val connection = URL(downloadUrl).openConnection() as HttpURLConnection
        val startNs = System.nanoTime()
        return try {
            runCancellable(onCancel = { connection.disconnect() }) {
                connection.connectTimeout = downloadCapMs.toInt()
                connection.readTimeout = downloadCapMs.toInt()
                connection.useCaches = false
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return@runCancellable TrafficResult(false, latency, null, "download answered HTTP $code")
                }
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if ((System.nanoTime() - startNs) / 1_000_000L > downloadCapMs) {
                            return@runCancellable TrafficResult(false, latency, null, "download exceeded ${downloadCapMs}ms")
                        }
                    }
                }
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1)
                if (total < expectedBytes) {
                    TrafficResult(false, latency, null, "download cut short at $total of $expectedBytes bytes")
                } else {
                    TrafficResult(true, latency, total * 8 / elapsedMs, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            TrafficResult(false, latency, null, "download failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        const val DOWNLOAD_BYTES = 262_144L
        private const val CONNECT_SAMPLES = 3
    }
}
