package com.russianranger.fta

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class ArchiveStoreTest {
    private val root = Files.createTempDirectory("fta-input-test").toFile()
    private val bytes = ByteArray(700_000) { (it % 251).toByte() }
    private val spec = ArchiveSpec("test", "Test archive", sha(bytes), bytes.size.toLong())
    private fun store() = ArchiveStore(root, listOf(spec))
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun verifiedCopySurvivesRecreation() {
        val receipt = store().import(spec, ByteArrayInputStream(bytes)) { _, _ -> }
        assertEquals(receipt, store().receipt(spec))
        assertArrayEquals(bytes, File(root, "test/${receipt.generation}/archive.zip").readBytes())
    }

    @Test fun mismatchCannotReplaceVerifiedGeneration() {
        val store = store()
        val previous = store.import(spec, ByteArrayInputStream(bytes)) { _, _ -> }
        val damaged = bytes.copyOf().apply { this[100] = (this[100] + 1).toByte() }
        assertThrows(IOException::class.java) { store.import(spec, ByteArrayInputStream(damaged)) { _, _ -> } }
        assertEquals(previous, store.receipt(spec))
        assertEquals(1, File(root, "test").listFiles()!!.count { it.isDirectory })
    }

    @Test fun truncatedAndOversizedInputsNeverReceiveReceipt() {
        val store = store()
        for (invalid in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0))) {
            assertThrows(IOException::class.java) { store.import(spec, ByteArrayInputStream(invalid)) { _, _ -> } }
            assertNull(store.receipt(spec))
        }
    }

    @Test fun readFailureRetainsPreviousAndCleansStaging() {
        val store = store()
        val previous = store.import(spec, ByteArrayInputStream(bytes)) { _, _ -> }
        val broken = object : InputStream() { override fun read(): Int = throw IOException("provider disconnected") }
        assertThrows(IOException::class.java) { store.import(spec, broken) { _, _ -> } }
        assertEquals(previous, store.receipt(spec))
        assertFalse(File(root, "test").listFiles()!!.any { it.name.startsWith(".") })
    }

    @Test fun restartCleansInterruptedCopyAndUnpublishedGeneration() {
        val receipt = store().import(spec, ByteArrayInputStream(bytes)) { _, _ -> }
        File(root, "test/.partial-crash").apply { mkdir(); File(this, "archive.zip").writeText("partial") }
        File(root, "test/g-00000000-0000-0000-0000-000000000000").mkdir()
        assertEquals(receipt, store().receipt(spec))
        assertEquals(setOf("active", receipt.generation), File(root, "test").list()!!.toSet())
    }

    @Test fun invalidPointerCannotEscapePrivateStorage() {
        store()
        File(root, "test/active").writeText("../../outside")
        assertNull(store().receipt(spec))
    }

    @Test fun missingArchiveDoesNotCountAsVerified() {
        val receipt = store().import(spec, ByteArrayInputStream(bytes)) { _, _ -> }
        File(root, "test/${receipt.generation}/archive.zip").delete()
        assertNull(store().receipt(spec))
    }

    @Test fun corruptArchiveIsNotTrustedAfterRestart() {
        val receipt = store().import(spec, ByteArrayInputStream(bytes)) { _, _ -> }
        val corrupt = bytes.copyOf().apply { this[123] = (this[123] + 1).toByte() }
        File(root, "test/${receipt.generation}/archive.zip").writeBytes(corrupt)
        assertNull(store().receipt(spec))
    }

    @Test fun importUsesBoundedReads() {
        var largestRequest = 0
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRequest = maxOf(largestRequest, length)
                return super.read(buffer, offset, length)
            }
        }
        store().import(spec, input) { _, _ -> }
        assertTrue(largestRequest <= 256 * 1024)
    }
}
