package com.windscribe.vpn.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Against real sockets on loopback: an open port, a closed port, and a peer that never speaks. */
class TcpTlsProberTest {
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val accepted = CopyOnWriteArrayList<Socket>()

    @Before
    fun setUp() {
        servers.clear()
        accepted.clear()
    }

    @After
    fun tearDown() {
        accepted.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    /** Accepts connections and does whatever [onAccept] says with them (default: hold them open, silent). */
    private fun server(onAccept: (Socket) -> Unit = {}): ServerSocket {
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        servers.add(server)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepted.add(socket)
                runCatching { onAccept(socket) }
            }
        }
        return server
    }

    private fun key(
        kind: ProbeKind,
        port: Int,
        sni: String? = null,
    ) = ProbeKey(kind, "127.0.0.1", port, sni)

    @Test
    fun `an open port is reachable`() =
        runBlocking {
            val server = server()

            val result = TcpTlsProber().probe(key(ProbeKind.TCP_CONNECT, server.localPort), 2_000)

            assertEquals(true, result.reachable)
            assertNotNull(result.latencyMs)
            assertTrue(result.latencyMs!! < 2_000)
        }

    @Test
    fun `a closed port is not reachable`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }

            val result = TcpTlsProber().probe(key(ProbeKind.TCP_CONNECT, port), 2_000)

            assertEquals(false, result.reachable)
            assertTrue(result.detail, result.detail.contains("Connect", ignoreCase = true))
        }

    @Test
    fun `a TLS probe times out against a peer that accepts and never answers`() =
        runBlocking {
            val server = server()
            val started = System.nanoTime()

            val result = TcpTlsProber().probe(key(ProbeKind.TLS_HANDSHAKE, server.localPort), 400)

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(false, result.reachable)
            assertEquals("timeout", result.detail)
            assertTrue("took ${elapsedMs}ms", elapsedMs in 300..2_000)
        }

    @Test
    fun `a TLS probe fails fast when the peer hangs up`() =
        runBlocking {
            val server = server { it.close() }

            val result = TcpTlsProber().probe(key(ProbeKind.TLS_HANDSHAKE, server.localPort), 2_000)

            assertEquals(false, result.reachable)
            assertFalse(result.detail == "timeout")
        }

    @Test
    fun `a TLS probe fails against a peer that answers with something else`() =
        runBlocking {
            val server =
                server { socket ->
                    socket.getInputStream().read()
                    socket.getOutputStream().write("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
                    socket.getOutputStream().flush()
                }

            val result = TcpTlsProber().probe(key(ProbeKind.TLS_HANDSHAKE, server.localPort, sni = "front.example"), 2_000)

            assertEquals(false, result.reachable)
        }

    @Test
    fun `a TLS probe against a closed port is not reachable`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }

            val result = TcpTlsProber(defaultSni = { "front.example" }).probe(key(ProbeKind.TLS_HANDSHAKE, port), 2_000)

            assertEquals(false, result.reachable)
        }

    @Test
    fun `the prober handles TCP and TLS only`() {
        val prober = TcpTlsProber()

        assertTrue(prober.handles(ProbeKind.TCP_CONNECT))
        assertTrue(prober.handles(ProbeKind.TLS_HANDSHAKE))
        assertFalse(prober.handles(ProbeKind.IKE))
        assertFalse(prober.handles(ProbeKind.NONE))
    }

    @Test
    fun `cancelling aborts a blocked handshake promptly`() =
        runBlocking {
            val server = server()
            val prober = TcpTlsProber()

            val job = launch(Dispatchers.Default) { prober.probe(key(ProbeKind.TLS_HANDSHAKE, server.localPort), 30_000) }
            delay(300)
            val started = System.nanoTime()
            withTimeout(5_000) {
                job.cancel()
                job.join()
            }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(job.isCancelled)
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 1_000)
        }
}
