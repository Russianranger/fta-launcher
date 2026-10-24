package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class LocalWorldProbeTest {
    private fun packet() = ByteArray(44).also {
        it[1] = 42; it[2] = 0xec.toByte(); it[3] = 1; it[4] = 1
        for (index in 8..43) it[index] = index.toByte()
    }

    @Test fun originalHeaderAndOpaqueSeedBytesAreAccepted() {
        LocalWorldProbe.validateChallenge(packet())
        LocalWorldProbe.validateChallenge(packet().also { for (i in 8..43) it[i] = 0 })
        LocalWorldProbe.validateChallenge(packet().also { for (i in 8..43) it[i] = 0xff.toByte() })
    }

    @Test fun wrongLengthOpcodeAndFixedWordAreRejected() {
        for (index in 0..7) {
            val bad = packet(); bad[index] = (bad[index].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { LocalWorldProbe.validateChallenge(bad) }
        }
        for (size in listOf(0, 4, 8, 43, 45, 1000)) {
            assertThrows(IllegalArgumentException::class.java) { LocalWorldProbe.validateChallenge(packet().copyOf(size)) }
        }
    }

    @Test fun allFragmentSizesAreReadWithoutSendingAnything() {
        for (fragment in 1..44) {
            val bytes = packet(); var consumed = 0
            LocalWorldProbe.receive { output, offset, length ->
                val count = minOf(fragment, length)
                bytes.copyInto(output, offset, consumed, consumed + count)
                consumed += count; count
            }
            assertEquals(44, consumed)
        }
    }

    @Test fun eofOrZeroReadAtEveryBoundaryFailsClosed() {
        for (boundary in 0..43) for (end in listOf(-1, 0)) {
            val bytes = packet(); var consumed = 0
            assertThrows(IllegalStateException::class.java) {
                LocalWorldProbe.receive { output, offset, _ ->
                    if (consumed == boundary) end else { output[offset] = bytes[consumed++]; 1 }
                }
            }
        }
    }

    @Test fun oversizedReaderAndInvalidPortsReject() {
        assertThrows(IllegalStateException::class.java) { LocalWorldProbe.receive { _, _, length -> length + 1 } }
        for (port in listOf(-1, 0, 1023, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { LocalWorldProbe.exchange(port) }
        }
    }
}
