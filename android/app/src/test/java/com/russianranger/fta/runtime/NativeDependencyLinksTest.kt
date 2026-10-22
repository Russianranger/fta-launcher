package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NativeDependencyLinksTest {
    private val root = Files.createTempDirectory("fta-link-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private fun native(path: String) = File(root, path).apply { mkdirs() }

    @Test fun unchangedProviderUsesFreshLinksAfterApkInstallPathChanges() {
        val oldNative = native("old-install/lib/arm64")
        val newNative = native("new-install/lib/arm64")
        val oldLibrary = File(oldNative, "libfta_crypto.so").apply { writeText("same bytes") }
        val newLibrary = File(newNative, "libfta_crypto.so").apply { writeText("same bytes") }
        val links = File(root, "links")
        val old = NativeDependencyLinks.prepare(links, "same-provider-manifest", oldNative, mapOf("libcrypto.so.3" to oldLibrary))
        val updated = NativeDependencyLinks.prepare(links, "same-provider-manifest", newNative, mapOf("libcrypto.so.3" to newLibrary))
        assertNotEquals(old, updated)
        assertEquals(oldLibrary.canonicalFile, File(old, "libcrypto.so.3").canonicalFile)
        assertEquals(newLibrary.canonicalFile, File(updated, "libcrypto.so.3").canonicalFile)
    }

    @Test fun repeatedPreparationKeepsExistingCorrectLinks() {
        val native = native("install/lib/arm64")
        val library = File(native, "libfta_crypto.so").apply { writeText("bytes") }
        val first = NativeDependencyLinks.prepare(File(root, "links"), "manifest", native, mapOf("libcrypto.so.3" to library))
        val next = NativeDependencyLinks.prepare(File(root, "links"), "manifest", native, mapOf("libcrypto.so.3" to library))
        assertEquals(first, next)
    }

    @Test fun writablePayloadCannotBeADependencyTarget() {
        val native = native("install/lib/arm64")
        val payload = File(root, "private-import.so").apply { writeText("untrusted bytes") }
        assertThrows(IllegalArgumentException::class.java) {
            NativeDependencyLinks.prepare(File(root, "links"), "manifest", native, mapOf("libcrypto.so.3" to payload))
        }
    }
}
