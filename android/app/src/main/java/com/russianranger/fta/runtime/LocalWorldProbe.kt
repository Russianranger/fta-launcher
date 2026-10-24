package com.russianranger.fta.runtime

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Pinned original build-12340 server challenge, before authentication/encryption.
 * Receives only: no account, auth-session packet, login or gameplay evidence.
 * Used by the guarded retained-world session after asset verification.
 */
object LocalWorldProbe {
    private const val PACKET_BYTES = 44
    private const val TIMEOUT_MS = 2_000
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun validateChallenge(packet: ByteArray) {
        require(packet.size == PACKET_BYTES) { "Incomplete original world challenge" }
        // ServerPktHeader: network-order length (payload + opcode), little-endian opcode.
        require(packet[0] == 0.toByte() && packet[1] == 42.toByte() &&
            packet[2] == 0xec.toByte() && packet[3] == 1.toByte() &&
            packet[4] == 1.toByte() && packet.sliceArray(5..7).all { it == 0.toByte() }) {
            "Unexpected original world challenge"
        }
        // Remaining 4 + 32 bytes are opaque random seeds, including valid zero bytes.
    }

    internal fun receive(read: (ByteArray, Int, Int) -> Int) {
        val packet = ByteArray(PACKET_BYTES)
        var offset = 0
        while (offset < packet.size) {
            val remaining = packet.size - offset
            val count = read(packet, offset, remaining)
            check(count in 1..remaining) { "Incomplete original world challenge" }
            offset += count
        }
        validateChallenge(packet)
    }

    fun exchange(port: Int) {
        require(port in 1024..65535)
        // One deadline covers both connection and every fragment. A slow peer
        // cannot extend this probe indefinitely by delivering one byte at a time.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS.toLong())
        fun remainingMs(): Int {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "World challenge deadline exceeded" }
            return ((remaining + 999_999) / 1_000_000).toInt().coerceIn(1, TIMEOUT_MS)
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, port), remainingMs())
            val input = socket.getInputStream()
            receive { buffer, offset, length ->
                socket.soTimeout = remainingMs()
                input.read(buffer, offset, length).also { remainingMs() }
            }
        }
    }
}
