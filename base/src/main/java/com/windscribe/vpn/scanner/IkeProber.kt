package com.windscribe.vpn.scanner

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * IKEv2: sends an IKE_SA_INIT request to the key's UDP port, then to [natTraversalPort] with the
 * non-ESP marker, and counts any reply as reachable. A responder that dislikes the proposal still
 * answers (NO_PROPOSAL_CHOSEN, INVALID_KE_PAYLOAD), which is all this needs to know.
 *
 * The time is split between the two ports. A port-unreachable ICMP is an answer too, and a negative
 * one.
 *
 * @param natTraversalPort null skips the second attempt.
 */
class IkeProber(
    private val natTraversalPort: Int? = NAT_T_PORT,
    private val random: SecureRandom = SecureRandom(),
) : EndpointProber {
    private val logger = LoggerFactory.getLogger(SCANNER_LOG)

    override fun handles(kind: ProbeKind): Boolean = kind == ProbeKind.IKE

    override suspend fun probe(
        key: ProbeKey,
        timeoutMs: Long,
    ): ProbeResult {
        val attempts = listOfNotNull(key.port to false, natTraversalPort?.let { it to true })
        val perAttemptMs = (timeoutMs / attempts.size).coerceAtLeast(1)
        var detail = "no reply"
        for ((port, natT) in attempts) {
            val startNs = System.nanoTime()
            val socket = DatagramSocket()
            try {
                val replied =
                    runCancellable(onCancel = { socket.close() }) {
                        exchange(socket, key.host, port, natT, perAttemptMs)
                    }
                if (replied) {
                    return ProbeResult(true, (System.nanoTime() - startNs) / 1_000_000L, "ike reply on udp/$port")
                }
                detail = "timeout"
            } catch (e: CancellationException) {
                throw e
            } catch (e: PortUnreachableException) {
                detail = "port unreachable udp/$port"
            } catch (e: SocketTimeoutException) {
                detail = "timeout"
            } catch (e: IOException) {
                detail = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { socket.close() }
            }
        }
        logger.debug("No IKE reply from {}: {}", key, detail)
        return ProbeResult(false, null, detail)
    }

    private fun exchange(
        socket: DatagramSocket,
        host: String,
        port: Int,
        natT: Boolean,
        timeoutMs: Long,
    ): Boolean {
        socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
        socket.connect(InetSocketAddress(InetAddress.getByName(host), port))
        val request = buildIkeSaInit(natT)
        socket.send(DatagramPacket(request, request.size))
        val buffer = ByteArray(2048)
        val reply = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(reply)
            reply.length > 0
        } catch (e: SocketTimeoutException) {
            false
        }
    }

    /** A well-formed IKE_SA_INIT request: SA (AES-CBC-256, HMAC-SHA2-256, MODP-2048), KE, Nonce. */
    internal fun buildIkeSaInit(natT: Boolean): ByteArray {
        val keData = ByteArray(KE_LENGTH).also { random.nextBytes(it) }
        // Below the group prime, so the responder does not reject the value out of hand.
        keData[0] = 0
        val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
        val spi = ByteArray(8).also { random.nextBytes(it) }

        val sa = ByteArrayOutputStream()
        DataOutputStream(sa).apply {
            // Payload header: next = KE, not critical, length.
            writeByte(PAYLOAD_KE)
            writeByte(0)
            writeShort(SA_LENGTH)
            // Proposal: last, reserved, length, number 1, IKE, no SPI, 4 transforms.
            writeByte(0)
            writeByte(0)
            writeShort(PROPOSAL_LENGTH)
            writeByte(1)
            writeByte(PROTOCOL_IKE)
            writeByte(0)
            writeByte(4)
            // ENCR AES-CBC with a 256-bit key attribute.
            writeByte(TRANSFORM_MORE)
            writeByte(0)
            writeShort(12)
            writeByte(1)
            writeByte(0)
            writeShort(12)
            writeShort(0x800E)
            writeShort(256)
            // PRF HMAC-SHA2-256.
            transform(TRANSFORM_MORE, 2, 5)
            // INTEG HMAC-SHA2-256-128.
            transform(TRANSFORM_MORE, 3, 12)
            // DH group 14 (MODP-2048), last transform.
            transform(0, 4, DH_GROUP)
        }

        val ke = ByteArrayOutputStream()
        DataOutputStream(ke).apply {
            writeByte(PAYLOAD_NONCE)
            writeByte(0)
            writeShort(KE_PAYLOAD_LENGTH)
            writeShort(DH_GROUP)
            writeShort(0)
            write(keData)
        }

        val noncePayload = ByteArrayOutputStream()
        DataOutputStream(noncePayload).apply {
            writeByte(0)
            writeByte(0)
            writeShort(NONCE_PAYLOAD_LENGTH)
            write(nonce)
        }

        val total = HEADER_LENGTH + sa.size() + ke.size() + noncePayload.size()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            if (natT) write(NON_ESP_MARKER)
            write(spi)
            write(ByteArray(8))
            writeByte(PAYLOAD_SA)
            writeByte(IKE_VERSION)
            writeByte(EXCHANGE_IKE_SA_INIT)
            writeByte(FLAG_INITIATOR)
            writeInt(0)
            writeInt(total)
            write(sa.toByteArray())
            write(ke.toByteArray())
            write(noncePayload.toByteArray())
        }
        return out.toByteArray()
    }

    private fun DataOutputStream.transform(
        more: Int,
        type: Int,
        id: Int,
    ) {
        writeByte(more)
        writeByte(0)
        writeShort(8)
        writeByte(type)
        writeByte(0)
        writeShort(id)
    }

    companion object {
        const val NAT_T_PORT = 4500
        private const val HEADER_LENGTH = 28
        private const val PROPOSAL_LENGTH = 44
        private const val SA_LENGTH = 48
        private const val KE_LENGTH = 256
        private const val KE_PAYLOAD_LENGTH = 8 + KE_LENGTH
        private const val NONCE_LENGTH = 32
        private const val NONCE_PAYLOAD_LENGTH = 4 + NONCE_LENGTH
        private const val DH_GROUP = 14
        private const val PAYLOAD_SA = 33
        private const val PAYLOAD_KE = 34
        private const val PAYLOAD_NONCE = 40
        private const val PROTOCOL_IKE = 1
        private const val TRANSFORM_MORE = 3
        private const val IKE_VERSION = 0x20
        private const val EXCHANGE_IKE_SA_INIT = 34
        private const val FLAG_INITIATOR = 0x08
        private val NON_ESP_MARKER = ByteArray(4)
    }
}
