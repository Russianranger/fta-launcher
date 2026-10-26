package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class WarcraftXlHostHeaderTest {
    private fun write(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
    private fun header() = ByteArray(64).also {
        write(it, 0, 0x4c585857L); write(it, 4, 4); write(it, 24, 2); write(it, 28, 1)
    }
    @Test fun acceptsEveryOriginalChannelCount() {
        for (count in 2..16) assertEquals(WarcraftXlHostHeader.Status.ACCEPTED,
            WarcraftXlHostHeader.inspect(header().also { write(it, 24, count.toLong()) }))
    }
    @Test fun rejectsEveryTruncatedSnapshotAndExtraBytes() {
        for (size in 0..63) assertEquals(WarcraftXlHostHeader.Status.INVALID_LENGTH,
            WarcraftXlHostHeader.inspect(header().copyOf(size)))
        assertEquals(WarcraftXlHostHeader.Status.INVALID_LENGTH, WarcraftXlHostHeader.inspect(header().copyOf(65)))
    }
    @Test fun rejectsWrongMagicIncludingReversedByteOrder() {
        for (magic in listOf(0L, 0x5758584cL, 0x4c585856L)) assertEquals(WarcraftXlHostHeader.Status.MAGIC_MISMATCH,
            WarcraftXlHostHeader.inspect(header().also { write(it, 0, magic) }))
    }
    @Test fun rejectsOlderNewerAndUnsignedHighProtocols() {
        for (version in listOf(0L, 3L, 5L, 0x80000004L, 0xffffffffL)) assertEquals(WarcraftXlHostHeader.Status.PROTOCOL_MISMATCH,
            WarcraftXlHostHeader.inspect(header().also { write(it, 4, version) }))
    }
    @Test fun channelBoundsUseUnsignedDwords() {
        for (count in listOf(0L, 1L, 17L, 0x80000002L, 0xffffffffL)) assertEquals(WarcraftXlHostHeader.Status.INVALID_CHANNEL_COUNT,
            WarcraftXlHostHeader.inspect(header().also { write(it, 24, count) }))
    }
    @Test fun zeroHostProcessIsRejected() {
        assertEquals(WarcraftXlHostHeader.Status.MISSING_HOST_PROCESS,
            WarcraftXlHostHeader.inspect(header().also { write(it, 28, 0) }))
    }
    @Test fun highBitProcessWordRetainsOriginalNonzeroSemantics() {
        assertEquals(WarcraftXlHostHeader.Status.ACCEPTED,
            WarcraftXlHostHeader.inspect(header().also { write(it, 28, 0xffffffffL) }))
    }
    @Test fun rejectionPrecedenceMatchesOriginal() {
        val bytes = ByteArray(64)
        assertEquals(4, WarcraftXlHostHeader.inspect(bytes).originalCode)
        write(bytes, 0, 0x4c585857L); assertEquals(5, WarcraftXlHostHeader.inspect(bytes).originalCode)
        write(bytes, 4, 4); assertEquals(6, WarcraftXlHostHeader.inspect(bytes).originalCode)
        write(bytes, 24, 2); assertEquals(7, WarcraftXlHostHeader.inspect(bytes).originalCode)
    }
    @Test fun uncheckedHeaderWordsAreNotInventedCompatibilityRules() {
        val bytes = header()
        for (i in bytes.indices) if (i !in 0..7 && i !in 24..31) bytes[i] = 0xff.toByte()
        assertEquals(WarcraftXlHostHeader.Status.ACCEPTED, WarcraftXlHostHeader.inspect(bytes))
    }
    @Test fun inspectionDoesNotModifySnapshot() {
        val bytes = header(); val before = bytes.clone()
        WarcraftXlHostHeader.inspect(bytes); assertArrayEquals(before, bytes)
    }
}
