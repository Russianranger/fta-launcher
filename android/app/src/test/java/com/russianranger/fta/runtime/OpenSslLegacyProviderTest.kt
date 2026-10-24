package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OpenSslLegacyProviderTest {
    private val root = Files.createTempDirectory("fta-crypto-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private val bytes = ByteArray(64).apply {
        this[0] = 0x7f; this[1] = 0x45; this[2] = 0x4c; this[3] = 0x46
        this[4] = 2; this[5] = 1; this[6] = 1; this[16] = 3; this[18] = 183.toByte(); this[52] = 64
    }
    private fun pin(data: ByteArray = bytes) = RuntimeFile("native/${OpenSslLegacyProvider.APK_NAME}",
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) },
        data.size.toLong(), "library")
    private fun native(name: String = "install") = File(root, name).apply {
        mkdirs(); File(this, OpenSslLegacyProvider.APK_NAME).writeBytes(bytes)
    }
    private fun prepare(native: File = native()) = OpenSslLegacyProvider.preparePinned(File(root, "links"), native, pin())

    @Test fun sameInstallReusesExactLinkAndUpdateNeverRepointsOldDirectory() {
        val old = native("old"); val newer = native("new")
        val first = prepare(old)
        assertEquals(first, prepare(old))
        val next = prepare(newer)
        assertNotEquals(first, next)
        assertEquals(File(old, OpenSslLegacyProvider.APK_NAME), File(first, "legacy.so").canonicalFile)
        assertEquals(File(newer, OpenSslLegacyProvider.APK_NAME), File(next, "legacy.so").canonicalFile)
    }

    @Test fun missingChangedTruncatedAndWrongArchitectureProvidersFailBeforeCreatingLinks() {
        for (replacement in listOf(null, bytes + byteArrayOf(1), bytes.copyOf().apply { this[40] = 1 },
            bytes.copyOf().apply { this[18] = 62 })) {
            val directory = native()
            val file = File(directory, OpenSslLegacyProvider.APK_NAME)
            if (replacement == null) file.delete() else file.writeBytes(replacement)
            val expected = if (replacement != null && replacement[18] == 62.toByte()) pin(replacement) else pin()
            assertThrows(Exception::class.java) {
                OpenSslLegacyProvider.preparePinned(File(root, "links"), directory, expected)
            }
            assertFalse(File(root, "links").exists())
        }
    }

    @Test fun apkSymlinkAndLinkedDirectoryAreRejected() {
        val directory = native(); val file = File(directory, OpenSslLegacyProvider.APK_NAME)
        val outside = File(root, "outside").apply { writeBytes(bytes) }
        file.delete(); Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertThrows(Exception::class.java) { prepare(directory) }
        file.delete(); file.writeBytes(bytes)
        val elsewhere = File(root, "elsewhere").apply { mkdir() }
        Files.createSymbolicLink(File(root, "links").toPath(), elsewhere.toPath())
        assertThrows(Exception::class.java) { prepare(directory) }
    }

    @Test fun existingWrongLinkOrWritableRegularProviderIsNeverReplaced() {
        val directory = native(); val prepared = prepare(directory); val link = File(prepared, "legacy.so")
        link.delete(); link.writeBytes(bytes)
        assertThrows(Exception::class.java) { prepare(directory) }
        assertFalse(Files.isSymbolicLink(link.toPath()))
        link.delete(); Files.createSymbolicLink(link.toPath(), File(root, "missing").toPath())
        assertThrows(Exception::class.java) { prepare(directory) }
        assertEquals(File(root, "missing").toPath(), Files.readSymbolicLink(link.toPath()))
    }

    @Test fun onlyWorldReceivesProviderEnvironment() {
        val directory = prepare(); val base = arrayOf("PATH=/system/bin", "HOME=/private")
        for (role in listOf("database-server", "database-client", "auth-server")) {
            assertArrayEquals(base, OpenSslLegacyProvider.forRole(base, role, directory))
        }
        assertArrayEquals(base + arrayOf("OPENSSL_MODULES=${directory.absolutePath}", "OPENSSL_CONF=/dev/null"),
            OpenSslLegacyProvider.forRole(base, "world-server", directory))
        assertEquals(2, base.size)
    }

    @Test fun unpreparedWorldUnknownRoleAndInheritedCryptoOverridesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OpenSslLegacyProvider.forRole(emptyArray(), "world-server", null) }
        assertThrows(IllegalArgumentException::class.java) { OpenSslLegacyProvider.forRole(emptyArray(), "other", null) }
        for (key in listOf("OPENSSL_MODULES", "OPENSSL_CONF")) {
            assertThrows(IllegalArgumentException::class.java) { OpenSslLegacyProvider.forRole(arrayOf("$key=/elsewhere"), "database-client", null) }
        }
    }

    @Test fun exactRetainedCryptoIdentityRequiredBeforeModulePreparation() {
        val crypto = RuntimeFile("native/${OpenSslLegacyProvider.CRYPTO_NAME}", OpenSslLegacyProvider.CRYPTO_SHA256,
            5_205_472, "library", logicalName = "libcrypto.so.3")
        for (files in listOf(emptyList(), listOf(crypto, crypto), listOf(crypto.copy(sha256 = "0".repeat(64))),
            listOf(crypto.copy(size = 1)), listOf(crypto.copy(role = "data")), listOf(crypto.copy(path = "native/other.so")))) {
            assertThrows(IllegalArgumentException::class.java) { OpenSslLegacyProvider.prepare(File(root, "links"), native(), files) }
            assertFalse(File(root, "links").exists())
        }
    }
}
