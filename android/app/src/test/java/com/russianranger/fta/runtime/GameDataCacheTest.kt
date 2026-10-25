package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class GameDataCacheTest {
    @Test fun rangeUsesLongOffsetsAndBoundedRequestsBeyondThreeGiB() {
        var next = 0L; var largest = 0L
        val cache = GameDataCache { _, at, size -> assertEquals(next, at); next += size; largest = maxOf(largest, size); 0 }
        val range = cache.file(File("unused")); val total = 3L * 1024 * 1024 * 1024 + 73
        var consumed = 0L
        while (consumed < total) { consumed = minOf(total, consumed + 262143); range.consumed(consumed) }
        range.consumed(total, true); range.consumed(total, true)
        assertEquals(total, next); assertEquals(GameDataCache.INTERVAL, largest)
        assertEquals(193L, cache.stats().accepted); assertEquals(total, cache.stats().bytes)
    }
    @Test fun emptyOrIncompleteRangesNeverAccidentallyAdviseToEndOfFile() {
        val calls = mutableListOf<Pair<Long, Long>>()
        val range = GameDataCache { _, at, size -> calls += at to size; 0 }.file(File("unused"))
        range.consumed(0, true); range.consumed(19)
        assertTrue(calls.isEmpty()); range.consumed(19, true)
        assertEquals(listOf(0L to 19L), calls)
        assertThrows(IllegalArgumentException::class.java) { range.consumed(18) }
    }
    @Test fun rejectedAdviceIsCountedWithoutChangingStreamBytesOrClosingSourceEarly() {
        val bytes = ByteArray(70003) { (it * 13).toByte() }; var closed = false
        val original = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        val cache = GameDataCache(); val stream = cache.input(original) { at, length ->
            assertEquals(0L, at); assertEquals(bytes.size.toLong(), length); 29
        }
        assertArrayEquals(bytes, stream.readBytes()); assertFalse(closed)
        assertEquals(GameDataCache.Stats(1, 0, 1, bytes.size.toLong()), cache.stats())
        stream.close(); assertTrue(closed)
    }
    @Test fun shortZeroAndSingleByteReadsAreCountedExactlyOnce() {
        val source = object : InputStream() {
            var n = 0
            override fun read() = if (n++ < 3) n else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (n == 0) { n++; return 0 }; return super.read(b, off, len) }
        }
        val cache = GameDataCache(); var advised = 0L
        val input = cache.input(source) { _, size -> advised += size; 0 }
        assertEquals(0, input.read(ByteArray(3))); assertEquals(2, input.read()); assertEquals(3, input.read())
        assertEquals(-1, input.read()); assertEquals(-1, input.read()); assertEquals(2L, advised)
    }
    @Test fun invalidStatsAndRangesDoNotBecomeEvidence() {
        assertThrows(IllegalArgumentException::class.java) { GameDataCache.Stats(1, 1, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { GameDataCache.Stats(1, 0, 1, -1) }
        val cache = GameDataCache { _, _, _ -> error("must not call") }
        cache.release(File("unused"), 0, 0)
        assertThrows(IllegalArgumentException::class.java) { cache.release(File("unused"), Long.MAX_VALUE, 1) }
    }
    @Test fun clientCanRequestOneMiBAdviceWithoutChangingLegacyDefault() {
        val sizes = mutableListOf<Long>()
        val data = ByteArray(2 * 1024 * 1024 + 7)
        val cache = GameDataCache()
        cache.input(java.io.ByteArrayInputStream(data), 1024L * 1024) { _, size -> sizes += size; 0 }.use {
            val buffer = ByteArray(256 * 1024)
            while (it.read(buffer) >= 0) { }
        }
        assertEquals(listOf(1024L * 1024, 1024L * 1024, 7L), sizes)
        assertEquals(16L * 1024 * 1024, GameDataCache.INTERVAL)
    }

}
