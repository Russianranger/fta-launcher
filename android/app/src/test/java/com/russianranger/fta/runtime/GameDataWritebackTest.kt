package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class GameDataWritebackTest {
    @Test fun threeGiBStreamNeverAccumulatesMoreThanSixteenMiBWithoutSync() {
        var written = 0L; var synced = 0L; var maximum = 0L; var syncs = 0
        val output = object : OutputStream() {
            override fun write(b: Int) = error("unbuffered")
            override fun write(b: ByteArray, off: Int, len: Int) { written += len; maximum = maxOf(maximum, written - synced) }
        }
        val writer = GameDataWriteback(output) { synced = written; syncs++ }
        val buffer = ByteArray(256 * 1024 - 1) // Cross interval boundaries, not just evenly aligned writes.
        val size = 3L * 1024 * 1024 * 1024 + 73
        while (written < size) writer.write(buffer, 0, minOf(buffer.size.toLong(), size - written).toInt())
        writer.finish()
        assertEquals(size, synced); assertEquals(16L * 1024 * 1024, maximum); assertEquals(193, syncs)
    }
    @Test fun chunkSplittingPreservesEveryByteAndRequestedSlice() {
        val source = ByteArray(GameDataWriteback.MAX_PENDING_BYTES + 137) { (it * 73).toByte() }
        val output = ByteArrayOutputStream(); val syncAt = mutableListOf<Int>()
        val writer = GameDataWriteback(output) { syncAt += output.size() }
        writer.write(source, 7, source.size - 14); writer.finish()
        assertArrayEquals(source.copyOfRange(7, source.size - 7), output.toByteArray())
        assertEquals(listOf(GameDataWriteback.MAX_PENDING_BYTES, source.size - 14), syncAt)
    }
    @Test fun failedSyncStopsFurtherWritesAndCannotBeMistakenForCompletion() {
        var written = 0
        val output = object : OutputStream() {
            override fun write(b: Int) { written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        val writer = GameDataWriteback(output) { throw IOException("storage failure") }
        val block = ByteArray(256 * 1024)
        assertThrows(IOException::class.java) { repeat(65) { writer.write(block, 0, block.size) } }
        assertEquals(GameDataWriteback.MAX_PENDING_BYTES, written)
        assertThrows(IllegalStateException::class.java) { writer.write(block, 0, 1) }
        assertThrows(IllegalStateException::class.java) { writer.finish() }
    }
    @Test fun finishFailureAndEmptyFileStillRequireSuccessfulStorageSync() {
        var syncs = 0
        GameDataWriteback(ByteArrayOutputStream()) { syncs++ }.finish()
        assertEquals(1, syncs)
        val writer = GameDataWriteback(ByteArrayOutputStream()) { throw IOException() }
        writer.write(byteArrayOf(1), 0, 1)
        assertThrows(IOException::class.java) { writer.finish() }
        assertThrows(IllegalStateException::class.java) { writer.finish() }
    }
    @Test fun cancellationAtSyncDoesNotPermitAnotherChunk() {
        var written = 0
        val output = object : OutputStream() {
            override fun write(b: Int) { written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        val writer = GameDataWriteback(output) { throw IllegalStateException("cancelled") }
        val block = ByteArray(256 * 1024)
        assertThrows(IllegalStateException::class.java) { repeat(65) { writer.write(block, 0, block.size) } }
        assertEquals(GameDataWriteback.MAX_PENDING_BYTES, written)
    }
}
