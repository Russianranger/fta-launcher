package com.russianranger.fta.runtime

import com.russianranger.fta.ArchiveSpec
import com.russianranger.fta.ArchiveStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VerifiedInputReaderTest {
    private val root = Files.createTempDirectory("fta-readonly-input").toFile()
    private val bytes = ByteArray(700_000) { (it % 251).toByte() }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val spec = ArchiveSpec("database", "Fixture", hash(bytes), bytes.size.toLong())
    private fun install(): File {
        val receipt = ArchiveStore(root, listOf(spec)).import(spec, ByteArrayInputStream(bytes)) { _, _ -> }
        return File(root, "database/${receipt.generation}/archive.zip")
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun readOnlyResolutionPreservesOngoingUiStagingAndOldGenerations() {
        val archive = install()
        val partial = File(root, "database/.partial-ui-in-progress").apply { mkdir(); File(this, "pending").writeText("copying") }
        val old = File(root, "database/g-00000000-0000-0000-0000-000000000000").apply { mkdir() }
        assertEquals(archive, VerifiedInputReader.resolve(root, spec))
        assertTrue(File(partial, "pending").isFile)
        assertTrue(old.isDirectory)
    }

    @Test fun sameSizeCorruptionCannotBeResolvedFromReceiptAlone() {
        val archive = install()
        archive.writeBytes(bytes.copyOf().apply { this[123] = (this[123] + 1).toByte() })
        assertThrows(IOException::class.java) { VerifiedInputReader.resolve(root, spec) }
        assertTrue(archive.exists())
        assertTrue(File(root, "database/active").exists())
    }

    @Test fun missingArchiveAndChangedMetadataFailWithoutDeletingAnything() {
        val archive = install()
        val receipt = File(archive.parentFile, "receipt.properties")
        receipt.writeText("kind=database\nsha256=${"0".repeat(64)}\nbytes=${bytes.size}\n")
        assertThrows(IOException::class.java) { VerifiedInputReader.resolve(root, spec) }
        assertTrue(receipt.exists())
        archive.delete()
        assertThrows(IOException::class.java) { VerifiedInputReader.resolve(root, spec) }
    }

    @Test fun traversalPointerAndSymlinkGenerationCannotEscapePrivateRoot() {
        install()
        File(root, "database/active").writeText("../../outside")
        assertThrows(IOException::class.java) { VerifiedInputReader.resolve(root, spec) }
        val outside = File(root, "outside").apply { mkdir() }
        val name = "g-11111111-1111-1111-1111-111111111111"
        Files.createSymbolicLink(File(root, "database/$name").toPath(), outside.toPath())
        File(root, "database/active").writeText(name)
        assertThrows(IOException::class.java) { VerifiedInputReader.resolve(root, spec) }
        assertTrue(outside.isDirectory)
    }
}
