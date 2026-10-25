package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.file.Files

class ClientStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Source : ClientSource {
        override var identity = "synthetic-private-tree"
        var timestamp = 1L
        val contents = linkedMapOf("Wow.exe" to byteArrayOf(1, 2), "d3d9.dll" to byteArrayOf(3),
            "WarcraftXL.dll" to byteArrayOf(4), "WarcraftXL.cfg" to "private settings".toByteArray(),
            "Data/a.mpq" to ByteArray(43) { it.toByte() }, "empty-file" to byteArrayOf())
        var opens = 0
        var provide: ((ClientEntry) -> InputStream)? = null
        override fun inventory() = listOf(ClientEntry("Data", "Data", 0, timestamp, true), ClientEntry("Empty", "Empty", 0, timestamp, true)) +
            contents.map { (name, data) -> ClientEntry(name, name, data.size.toLong(), timestamp, false) }
        override fun open(entry: ClientEntry): InputStream { opens++; return provide?.invoke(entry) ?: ByteArrayInputStream(contents.getValue(entry.path)) }
    }
    private fun store(root: File, free: () -> Long = { Long.MAX_VALUE }) = ClientStore(root, availableBytes = free, chunkBytes = 8)
    private fun rejected(reason: String, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (error: ClientImportFailure) { assertEquals(reason, error.reason) }
    }
    private fun pending(root: File) = File(root, File(root, "pending").readText())
    private fun active(root: File) = File(root, File(root, "active").readText())
    private fun pause(root: File, source: Source) {
        rejected("cancelled") { store(root).import(source, progress = { if (it.bytes >= 8) throw ClientImportFailure("cancelled") }, validate = {}) }
        assertFalse(File(root, "active").exists())
    }
    @Test fun copiesExactFilesAndEmptyDirectoriesAndReopens() {
        val root = File(temporary.root, "store"); val source = Source(); val before = source.contents.mapValues { it.value.clone() }
        val result = store(root).import(source, validate = {})
        assertEquals(source.contents.size, result.files); assertEquals(source.contents.values.sumOf { it.size.toLong() }, result.bytes)
        for ((name, bytes) in source.contents) { assertArrayEquals(bytes, File(active(root), "data/$name").readBytes()); assertArrayEquals(before.getValue(name), bytes) }
        assertTrue(File(active(root), "data/Empty").isDirectory)
        assertEquals(result.catalogSha256, store(root).verify(validate = {}).catalogSha256)
    }
    @Test fun resumesVerifiedChunksWithoutRewritingThem() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source)
        val result = store(root).import(source, validate = {})
        assertEquals(8L, result.resumedBytes); assertEquals(result.catalogSha256, store(root).verify(validate = {}).catalogSha256)
    }
    @Test fun partialUncommittedChunkIsDiscardedAndRecopied() {
        val root = File(temporary.root, "store"); val source = Source()
        source.provide = { entry -> object : ByteArrayInputStream(source.contents.getValue(entry.path)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (entry.path == "Data/a.mpq" && pos >= 10) throw IOException("Synthetic interruption")
                return super.read(buffer, offset, if (entry.path == "Data/a.mpq") minOf(length, 10 - pos) else length)
            }
        } }
        try { store(root).import(source, validate = {}); fail() } catch (_: IOException) { }
        assertEquals(10L, File(pending(root), "data/Data/a.mpq").length())
        source.provide = null
        assertEquals(8L, store(root).import(source, validate = {}).resumedBytes)
        store(root).verify(validate = {})
    }
    @Test fun tornFinalCheckpointRecordIsIgnored() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source)
        File(pending(root), "chunks/0.bin").appendBytes(ByteArray(13))
        assertEquals(8L, store(root).import(source, validate = {}).resumedBytes)
    }
    @Test fun corruptCommittedCheckpointIsRejected() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source)
        RandomAccessFile(File(pending(root), "chunks/0.bin"), "rw").use { it.writeByte(0) }
        rejected("invalid-checkpoint") { store(root).import(source, validate = {}) }
        assertFalse(File(root, "active").exists())
    }
    @Test fun alteredSourcePrefixCannotResume() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source)
        source.contents.getValue("Data/a.mpq")[0] = 99
        rejected("source-changed") { store(root).import(source, validate = {}) }
    }
    @Test fun alteredDestinationPrefixCannotResume() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source)
        RandomAccessFile(File(pending(root), "data/Data/a.mpq"), "rw").use { it.writeByte(99) }
        rejected("destination-changed") { store(root).import(source, validate = {}) }
    }
    @Test fun changedSourceMetadataCannotReuseCheckpoint() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source); source.timestamp++
        rejected("source-changed") { store(root).import(source, validate = {}) }
    }
    @Test fun differentSourceGrantCannotReuseCheckpoint() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source); source.identity = "other-tree"
        rejected("source-changed") { store(root).import(source, validate = {}) }
    }
    @Test fun insufficientSpaceStopsBeforeOpeningSourceData() {
        val root = File(temporary.root, "store"); val source = Source()
        rejected("insufficient-space") { store(root) { ClientStore.RESERVE }.import(source, validate = {}) }
        assertEquals(0, source.opens); assertFalse(File(root, "active").exists())
    }
    @Test fun spaceLossPreservesAResumableCheckpoint() {
        val root = File(temporary.root, "store"); val source = Source(); var free = Long.MAX_VALUE
        rejected("insufficient-space") { store(root) { free }.import(source, progress = { if (it.bytes >= 8) free = 0 }, validate = {}) }
        assertEquals(8L, store(root).import(source, validate = {}).resumedBytes)
    }
    @Test fun grownFileIsRejected() {
        val root = File(temporary.root, "store"); val source = Source()
        source.provide = { ByteArrayInputStream(source.contents.getValue(it.path) + byteArrayOf(99)) }
        rejected("source-changed") { store(root).import(source, validate = {}) }
    }
    @Test fun shortenedFileIsRejected() {
        val root = File(temporary.root, "store"); val source = Source(); source.provide = { ByteArrayInputStream(byteArrayOf()) }
        rejected("source-changed") { store(root).import(source, validate = {}) }
    }
    @Test fun finalSourceInventoryMustStillMatch() {
        val root = File(temporary.root, "store"); val source = Source()
        rejected("source-changed") { store(root).import(source, progress = { if (it.stage == "validate") source.timestamp++ }, validate = {}) }
        assertFalse(File(root, "active").exists())
    }
    @Test fun candidateValidationFailureDoesNotPublish() {
        val root = File(temporary.root, "store"); val source = Source()
        rejected("client-build-mismatch") { store(root).import(source, validate = { throw ClientImportFailure("client-build-mismatch") }) }
        assertFalse(File(root, "active").exists())
    }
    @Test fun previousPublishedGenerationSurvivesFailedReplacement() {
        val root = File(temporary.root, "store"); val source = Source(); val result = store(root).import(source, validate = {})
        val pointer = File(root, "active").readText(); source.identity = "new-tree"
        rejected("cancelled") { store(root).import(source, progress = { if (it.bytes > 0) throw ClientImportFailure("cancelled") }, validate = {}) }
        assertEquals(pointer, File(root, "active").readText()); assertEquals(result.catalogSha256, store(root).verify(validate = {}).catalogSha256)
    }
    @Test fun duplicateImportVerifiesInsteadOfMakingAnotherCopy() {
        val root = File(temporary.root, "store"); val source = Source(); store(root).import(source, validate = {})
        val pointer = File(root, "active").readText(); source.opens = 0
        store(root).import(source, validate = {})
        assertEquals(0, source.opens); assertEquals(pointer, File(root, "active").readText())
    }
    @Test fun concurrentOperationCannotAcquireStore() {
        val root = File(temporary.root, "store"); val source = Source()
        store(root).import(source, progress = { rejected("busy") { store(root).verify(validate = {}) } }, validate = {})
    }
    @Test fun retainedRehashFindsChangedFile() {
        val root = File(temporary.root, "store"); val source = Source(); store(root).import(source, validate = {})
        File(active(root), "data/WarcraftXL.cfg").writeText("different")
        rejected("destination-changed") { store(root).verify(validate = {}) }
    }
    @Test fun retainedRehashRejectsUnexpectedFile() {
        val root = File(temporary.root, "store"); val source = Source(); store(root).import(source, validate = {})
        File(active(root), "data/unexpected").writeText("new")
        rejected("destination-changed") { store(root).verify(validate = {}) }
    }
    @Test fun retainedRehashDoesNotRecreateMissingDirectory() {
        val root = File(temporary.root, "store"); val source = Source(); store(root).import(source, validate = {})
        val data = File(active(root), "data/Data"); data.deleteRecursively()
        rejected("destination-changed") { store(root).verify(validate = {}) }; assertFalse(data.exists())
    }
    @Test fun symlinkedDestinationCannotEscapeStore() {
        val root = File(temporary.root, "store"); val source = Source(); pause(root, source)
        val directory = File(pending(root), "data/Data"); directory.deleteRecursively()
        val outside = temporary.newFolder("outside"); Files.createSymbolicLink(directory.toPath(), outside.toPath())
        rejected("unsafe-destination") { store(root).import(source, validate = {}) }; assertEquals(0, outside.listFiles()!!.size)
    }
    @Test fun unsafePathAndMissingParentRejected() {
        val source = Source()
        rejected("invalid-path") { ClientPlan(source.identity, source.inventory() + ClientEntry("../escape", "unique", 0, 0, false)) }
        rejected("invalid-parent") { ClientPlan(source.identity, source.inventory() + ClientEntry("absent/child", "unique", 0, 0, false)) }
    }
    @Test fun duplicateSourceIdentityRejected() {
        val source = Source()
        rejected("duplicate-identity") { ClientPlan(source.identity, source.inventory() + ClientEntry("alias", "Wow.exe", 0, 0, false)) }
    }
    @Test fun unknownSizesRejectedBeforeCopy() {
        val source = Source(); rejected("unknown-size") { ClientPlan(source.identity, source.inventory().map { if (it.path == "Wow.exe") it.copy(bytes = -1) else it }) }
    }
    @Test fun requiredClientShapeCheckedBeforeCopy() {
        val source = Source(); rejected("client-files-missing") { ClientPlan(source.identity, source.inventory().filterNot { it.path == "Wow.exe" }) }
    }
    @Test fun retainedRehashRequiresOriginalEmptyDirectories() {
        val root = File(temporary.root, "store"); store(root).import(Source(), validate = {})
        File(active(root), "data/Empty").delete()
        rejected("destination-changed") { store(root).verify(validate = {}) }
    }
    @Test fun sourceReadsAreBoundedTo256KiB() {
        val root = File(temporary.root, "store"); val source = Source(); var largest = 0
        source.contents["Data/a.mpq"] = ByteArray(1024 * 1024 + 1)
        source.provide = { entry -> object : ByteArrayInputStream(source.contents.getValue(entry.path)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largest = maxOf(largest, length); assertTrue(length <= 256 * 1024); return super.read(buffer, offset, length)
            }
        } }
        ClientStore(root, availableBytes = { Long.MAX_VALUE }).import(source, validate = {})
        assertEquals(256 * 1024, largest)
    }
    @Test fun prepublicationSyncFailureLeavesCandidateResumable() {
        val root = File(temporary.root, "store"); val source = Source()
        val broken = ClientStore(root, availableBytes = { Long.MAX_VALUE }, chunkBytes = 8, syncDirectory = {
            if (it.name.startsWith("g-") && File(it, "catalog.bin").exists()) throw IOException("Synthetic sync failure")
        })
        try { broken.import(source, validate = {}); fail() } catch (_: IOException) { }
        assertFalse(File(root, "active").exists())
        val result = store(root).import(source, validate = {})
        assertEquals(result.bytes, result.resumedBytes)
    }
    @Test fun interruptedFinalReceiptCanBeRecoveredByRetainedRehash() {
        val root = File(temporary.root, "store"); val source = Source()
        val broken = ClientStore(root, availableBytes = { Long.MAX_VALUE }, chunkBytes = 8, syncDirectory = {
            if (it == root && File(root, "active").exists()) throw IOException("Synthetic interruption after publication")
        })
        try { broken.import(source, validate = {}); fail() } catch (_: IOException) { }
        assertTrue(File(root, "active").exists()); store(root).verify(validate = {})
    }
    @Test fun privatePlanRoundTripsWithoutLosingSourceIdentity() {
        val source = Source(); val plan = ClientPlan(source.identity, source.inventory())
        assertArrayEquals(plan.encoded, ClientPlan.decode(plan.encoded).encoded)
        rejected("invalid-checkpoint") { ClientPlan.decode(plan.encoded + byteArrayOf(0)) }
    }
    @Test fun unexpectedCandidateFileCannotBePublished() {
        val root = File(temporary.root, "store"); val source = Source()
        rejected("destination-changed") { store(root).import(source, progress = {
            if (it.stage == "validate") File(pending(root), "data/unexpected").writeText("unverified")
        }, validate = {}) }
        assertFalse(File(root, "active").exists())
    }
    @Test fun smallSyncAndCacheWindowsKeepOriginalCheckpointFormat() {
        val root = File(temporary.root, "store"); val source = Source()
        source.contents["Data/a.mpq"] = ByteArray(17 * 1024 * 1024 + 3) { it.toByte() }
        var previous = 0L; var largest = 0L; var charged = 0L
        val windows = mutableListOf<Long>()
        val result = ClientStore(root, availableBytes = { Long.MAX_VALUE }, beforeIo = { charged += it },
            syncOutput = { out ->
                val position = out.filePointer
                if (position < previous) previous = 0
                largest = maxOf(largest, position - previous); previous = position; out.fd.sync()
            }, releaseCache = { _, _, bytes -> windows += bytes }).import(source, validate = {})
        assertTrue(largest <= ClientStore.IO_WINDOW); assertTrue(windows.all { it <= ClientStore.IO_WINDOW })
        assertEquals(result.bytes * 3, charged)
        assertEquals("16777216", File(pending(root), "chunk-size").readText())
        assertEquals(128L, File(pending(root), "chunks/0.bin").length())
    }
    @Test fun memoryPauseFailureRetainsVerifiedPrefixAndResumes() {
        val root = File(temporary.root, "store"); val source = Source(); var io = 0L
        rejected("memory-pressure") {
            ClientStore(root, availableBytes = { Long.MAX_VALUE }, chunkBytes = 8, beforeIo = {
                io += it; if (io > 28) throw ClientImportFailure("memory-pressure")
            }).import(source, validate = {})
        }
        assertFalse(File(root, "active").exists())
        assertEquals(8L, store(root).import(source, validate = {}).resumedBytes)
        store(root).verify(validate = {})
    }
    @Test fun syncFailureCannotCommitItsChunk() {
        val root = File(temporary.root, "store"); val source = Source()
        try {
            ClientStore(root, availableBytes = { Long.MAX_VALUE }, chunkBytes = 8,
                syncOutput = { throw IOException("synthetic sync failure") }).import(source, validate = {})
            fail()
        } catch (_: IOException) { }
        assertFalse(File(root, "active").exists()); assertEquals(0L, File(pending(root), "chunks/0.bin").length())
        assertEquals(0L, store(root).import(source, validate = {}).resumedBytes)
    }
    @Test fun retainedVerificationIsGovernedAndCanStopWithoutChangingPublishedData() {
        val root = File(temporary.root, "store"); val source = Source(); val result = store(root).import(source, validate = {})
        val pointer = File(root, "active").readText()
        rejected("memory-pressure") {
            ClientStore(root, beforeIo = { throw ClientImportFailure("memory-pressure") }).verify(validate = {})
        }
        assertEquals(pointer, File(root, "active").readText())
        assertEquals(result.catalogSha256, store(root).verify(validate = {}).catalogSha256)
    }

}
