package com.windscribe.vpn.scanner

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class IkeProberTest {
    private val sockets = CopyOnWriteArrayList<DatagramSocket>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
    }

    private fun responder(reply: Boolean): Pair<DatagramSocket, CopyOnWriteArrayList<ByteArray>> {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        sockets.add(socket)
        val received = CopyOnWriteArrayList<ByteArray>()
        thread(isDaemon = true) {
            val buffer = ByteArray(2048)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { return@thread }
                received.add(buffer.copyOf(packet.length))
                if (reply) {
                    val answer = byteArrayOf(0x00, 0x01, 0x02, 0x03)
                    socket.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
                }
            }
        }
        return socket to received
    }

    private fun key(port: Int) = ProbeKey(ProbeKind.IKE, "127.0.0.1", port)

    @Test
    fun `any reply counts as reachable`() =
        runBlocking {
            val (server, received) = responder(reply = true)

            val result = IkeProber(natTraversalPort = null).probe(key(server.localPort), 2_000)

            assertEquals(true, result.reachable)
            assertEquals(1, received.size)
        }

    @Test
    fun `silence is not reachable`() =
        runBlocking {
            val (server, _) = responder(reply = false)
            val started = System.nanoTime()

            val result = IkeProber(natTraversalPort = null).probe(key(server.localPort), 400)

            assertEquals(false, result.reachable)
            assertEquals("timeout", result.detail)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }

    @Test
    fun `a closed UDP port is not reachable`() =
        runBlocking {
            val port = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

            val result = IkeProber(natTraversalPort = null).probe(key(port), 1_000)

            assertEquals(false, result.reachable)
        }

    @Test
    fun `the NAT-T port is tried with the non-ESP marker when the first port is silent`() =
        runBlocking {
            val (silent, _) = responder(reply = false)
            val (natT, received) = responder(reply = true)

            val result = IkeProber(natTraversalPort = natT.localPort).probe(key(silent.localPort), 1_000)

            assertEquals(true, result.reachable)
            val packet = received.single()
            assertEquals(4, packet.take(4).count { it == 0.toByte() })
            // After the marker, a normal IKE header.
            assertEquals(34, packet[4 + 18].toInt())
        }

    @Test
    fun `the request is a well formed IKE_SA_INIT`() {
        val packet = IkeProber().buildIkeSaInit(natT = false)
        val buffer = ByteBuffer.wrap(packet)

        assertEquals(376, packet.size)
        // Responder SPI zero, next payload SA, IKEv2, IKE_SA_INIT, initiator flag, message id 0.
        assertTrue(packet.slice(8..15).all { it == 0.toByte() })
        assertEquals(33, packet[16].toInt())
        assertEquals(0x20, packet[17].toInt())
        assertEquals(34, packet[18].toInt())
        assertEquals(0x08, packet[19].toInt())
        assertEquals(0, buffer.getInt(20))
        assertEquals(packet.size, buffer.getInt(24))
        // SA payload chains to KE (34), KE to Nonce (40), Nonce ends the chain.
        assertEquals(34, packet[28].toInt())
        assertEquals(40, packet[28 + 48].toInt())
        assertEquals(0, packet[28 + 48 + 264].toInt())
        assertEquals(376, 28 + 48 + 264 + 36)
    }

    @Test
    fun `two requests differ`() {
        val prober = IkeProber()

        assertFalse(prober.buildIkeSaInit(false).contentEquals(prober.buildIkeSaInit(false)))
    }

    @Test
    fun `the prober handles IKE only`() {
        val prober = IkeProber()

        assertTrue(prober.handles(ProbeKind.IKE))
        assertFalse(prober.handles(ProbeKind.TCP_CONNECT))
    }
}
