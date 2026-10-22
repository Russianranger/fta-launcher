package com.russianranger.fta.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuntimeBundleStoreTest {
    private val root = Files.createTempDirectory("fta-runtime-test").toFile()
    private fun elf(machine: Int = 183): ByteArray = ByteArray(128).apply {
        this[0] = 0x7f; this[1] = 0x45; this[2] = 0x4c; this[3] = 0x46
        this[4] = 2; this[5] = 1; this[6] = 1
        this[16] = 3; this[18] = machine.toByte(); this[19] = (machine shr 8).toByte()
        this[52] = 64
    }
    private val payload get() = linkedMapOf(
        "native/libfta_mariadbd.so" to elf(),
        "native/libfta_mariadb_client.so" to elf(),
        "data/provider/bootstrap.sql" to "SELECT 1;".toByteArray()
    )
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun manifest(files: Map<String, ByteArray> = payload): String {
        val entries = files.entries.joinToString(",") { (path, bytes) ->
            val role = when (path) {
                "native/libfta_mariadbd.so" -> "database-server"
                "native/libfta_mariadb_client.so" -> "database-client"
                else -> "data"
            }
            """{"path":"$path","sha256":"${digest(bytes)}","size":${bytes.size},"role":"$role"}"""
        }
        val bootstrap = files["data/provider/bootstrap.sql"]!!
        return """{"formatVersion":1,"abi":"arm64-v8a","providerVersion":"12.3.2-test","capabilities":["database"],"files":[$entries],"bootstrap":{"path":"data/provider/bootstrap.sql","sha256":"${digest(bootstrap)}","size":${bootstrap.size}}}"""
    }
    private fun bundle(files: Map<String, ByteArray> = payload, json: String = manifest(files)): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (linkedMapOf(RuntimeManifest.FILE_NAME to json.toByteArray()) + files).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun importAndRestartValidateActualFiles() {
        val store = RuntimeBundleStore(root)
        var count = 0L
        val installed = store.import(ByteArrayInputStream(bundle())) { count = it }
        assertEquals(payload.values.sumOf { it.size.toLong() }, count)
        assertEquals(setOf("database"), installed.manifest.capabilities)
        assertEquals("data/provider/bootstrap.sql", installed.manifest.bootstrap!!.path)
        assertEquals(installed, RuntimeBundleStore(root).active())
        assertArrayEquals(payload.getValue("native/libfta_mariadbd.so"),
            File(installed.directory, "native/libfta_mariadbd.so").readBytes())
        assertFalse(File(installed.directory, "native/libfta_mariadbd.so").canExecute())
    }

    @Test fun wrongDigestDoesNotReplacePreviousGeneration() {
        val store = RuntimeBundleStore(root)
        val previous = store.import(ByteArrayInputStream(bundle()))
        val files = payload.apply { this["data/provider/bootstrap.sql"] = "SELECT 2;".toByteArray() }
        assertThrows(Exception::class.java) { store.import(ByteArrayInputStream(bundle(files, manifest()))) }
        assertEquals(previous, store.active())
        assertEquals(previous, RuntimeBundleStore(root).active())
    }

    @Test fun sameSizeCorruptionIsRejectedOnRestart() {
        val receipt = RuntimeBundleStore(root).import(ByteArrayInputStream(bundle()))
        File(receipt.directory, "data/provider/bootstrap.sql").writeText("SELECT 2;")
        assertNull(RuntimeBundleStore(root).active())
    }

    @Test fun orphanStagingAndUnpublishedGenerationsAreRemoved() {
        val previous = RuntimeBundleStore(root).import(ByteArrayInputStream(bundle()))
        File(root, ".partial-crash").mkdir()
        File(root, "g-00000000-0000-0000-0000-000000000000").mkdir()
        assertEquals(previous, RuntimeBundleStore(root).active())
        assertEquals(setOf(previous.generation, "active"), root.list()!!.toSet())
    }

    @Test fun traversalBackslashAndAbsolutePathsAreRejected() {
        for (path in listOf("../escape", "/escape", "data/../../escape", "data\\escape", "C:/escape", "data//escape")) {
            val files = payload.apply { this[path] = byteArrayOf(1) }
            assertThrows(Exception::class.java) { RuntimeBundleStore(root).import(ByteArrayInputStream(bundle(files, manifest()))) }
            assertNull(RuntimeBundleStore(root).active())
        }
        assertFalse(File(root.parentFile, "escape").exists())
    }

    @Test fun undeclaredAndMissingPayloadEntriesAreRejected() {
        val extra = payload.apply { this["data/extra.sql"] = byteArrayOf(1) }
        assertThrows(Exception::class.java) { RuntimeBundleStore(root).import(ByteArrayInputStream(bundle(extra, manifest()))) }
        val missing = payload.apply { remove("native/libfta_mariadb_client.so") }
        assertThrows(Exception::class.java) { RuntimeBundleStore(root).import(ByteArrayInputStream(bundle(missing, manifest()))) }
    }

    @Test fun wrongArchitectureCannotBeImportedEvenWithMatchingHash() {
        val files = payload.apply { this["native/libfta_mariadbd.so"] = elf(62) }
        assertThrows(Exception::class.java) { RuntimeBundleStore(root).import(ByteArrayInputStream(bundle(files))) }
    }

    @Test fun duplicateKeysUnknownFieldsAndCapabilitiesAreRejected() {
        val input = manifest()
        val variants = listOf(
            input.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            input.replace("\"formatVersion\":1", "\"formatVersion\":1,\"executeAnything\":1"),
            input.replace("[\"database\"]", "[\"database\",\"unknown\"]"),
            input.replace("[\"database\"]", "[\"database\",\"database\"]"),
            input.replace("\"arm64-v8a\"", "\"x86_64\"")
        )
        variants.forEach { assertThrows(IllegalArgumentException::class.java) { RuntimeManifest.parse(it) } }
    }

    @Test fun manifestLimitsAndBootstrapMismatchFailClosed() {
        val input = manifest()
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeManifest.parse(input.replace("\"size\":128", "\"size\":536870913"))
        }
        val finalHash = input.lastIndexOf("\"sha256\":\"") + "\"sha256\":\"".length
        val changed = input.substring(0, finalHash) + "0".repeat(64) + input.substring(finalHash + 64)
        assertThrows(IllegalArgumentException::class.java) { RuntimeManifest.parse(changed) }
    }

    @Test fun zipSymlinkMetadataIsRejectedBeforeExtraction() {
        val archive = bundle()
        // First central entry is the manifest. Mark its Unix file type as a symlink.
        val central = (0..archive.size - 46).first { offset ->
            archive[offset] == 0x50.toByte() && archive[offset + 1] == 0x4b.toByte() &&
                archive[offset + 2] == 0x01.toByte() && archive[offset + 3] == 0x02.toByte()
        }
        archive[central + 40] = 0xff.toByte()
        archive[central + 41] = 0xa1.toByte()
        assertThrows(Exception::class.java) { RuntimeBundleStore(root).import(ByteArrayInputStream(archive)) }
        assertNull(RuntimeBundleStore(root).active())
    }

    @Test fun caseInsensitiveDuplicateZipNamesAreRejected() {
        val files = payload.apply { this["native/LIBFTA_MARIADBD.SO"] = elf() }
        assertThrows(Exception::class.java) { RuntimeBundleStore(root).import(ByteArrayInputStream(bundle(files, manifest()))) }
    }

    @Test fun providerBuilderMetadataIsValidatedWithoutGrantingAuthority() {
        val json = manifest().replace("\"providerVersion\":", "\"providerId\":\"termux-mariadb\",\"qualification\":{\"androidExecuted\":false,\"databaseSeedCompatible\":false,\"publicRedistributionQualified\":false},\"providerVersion\":")
            .replace("\"role\":\"database-server\"}", "\"role\":\"database-server\",\"needed\":[],\"sonames\":[],\"interpreter\":\"/system/bin/linker64\",\"minimumLoadAlignment\":4096}")
            .replace("\"role\":\"database-client\"}", "\"role\":\"database-client\",\"needed\":[],\"sonames\":[],\"interpreter\":null,\"minimumLoadAlignment\":16384}")
        val parsed = RuntimeManifest.parse(json)
        assertEquals("termux-mariadb", parsed.providerId)
        assertEquals("/system/bin/linker64", parsed.files.first().interpreter)
        assertEquals(4096L, parsed.files.first().minimumLoadAlignment)
        assertNull(parsed.files[1].interpreter)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeManifest.parse(json.replace("\"androidExecuted\":false", "\"androidExecuted\":\"false\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeManifest.parse(json.replace("/system/bin/linker64", "/lib64/ld-linux-aarch64.so.1"))
        }
    }

    @Test fun optionalBootstrapMayBeExplicitlyNull() {
        val json = manifest()
        val start = json.indexOf(",\"bootstrap\":")
        assertNull(RuntimeManifest.parse(json.substring(0, start) + ",\"bootstrap\":null}").bootstrap)
    }

    @Test fun invalidPointerCannotResolveOutsidePrivateDirectory() {
        File(root, "active").writeText("../../outside")
        assertNull(RuntimeBundleStore(root).active())
    }
}
