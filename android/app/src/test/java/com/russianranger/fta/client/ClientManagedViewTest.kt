package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream

class ClientManagedViewTest {
    @get:Rule val temp = TemporaryFolder()
    private val content = linkedMapOf("Wow.exe" to byteArrayOf(1, 2, 3), "Utils/WarcraftXLHost.exe" to byteArrayOf(4, 5),
        "Data/a.mpq" to ByteArray(100), "wTf/Config.wtf" to "SET accountName \"private\"\r\nSET specular \"1\"\r\n".toByteArray())
    private fun source() = object : ClientSource {
        override val identity = "managed-view-synthetic"
        override fun inventory() = listOf("Data", "Utils", "wTf").map { ClientEntry(it, it, 0, 1, true) } +
            content.map { (p, b) -> ClientEntry(p, p, b.size.toLong(), 1, false) }
        override fun open(entry: ClientEntry) = ByteArrayInputStream(content.getValue(entry.path))
    }
    private fun stamp(file: File) = listOf(file.length(), file.lastModified())
    private fun setup(): Triple<File, ClientStore, ClientStore.Result> {
        val root = File(temp.newFolder(), "store"); val store = ClientStore(root)
        return Triple(root, store, store.import(source(), validate = {}))
    }
    private fun pins() = content.filterKeys { it.endsWith(".exe") }.mapValues { (_, bytes) -> bytes.size.toLong() to runtimeHex(runtimeHash().digest(bytes)) }

    @Test fun verifiedGenerationRemainsLockedThroughViewSessionAndOnlySettingsCopiesChange() {
        val (root, store, result) = setup(); val view = File(temp.newFolder(), "view")
        val evidence = mutableMapOf<String, Any>()
        val built = ClientManagedView.run(store, view, result.catalogSha256, validate = {}, pins = pins(), stamp = ::stamp,
            evidence = { k, v -> evidence[k] = v }) { original, working ->
            assertEquals(view, working)
            try { ClientStore(root).verify(validate = {}); fail() } catch (e: ClientImportFailure) { assertEquals("busy", e.reason) }
            assertArrayEquals(content.getValue("wTf/Config.wtf"), File(original, "wTf/Config.wtf").readBytes())
            assertTrue(File(working, "wTf/Config.wtf").readText().contains("SET specular \"0\""))
            assertTrue(File(working, "wTf/Config.wtf").readText().contains("SET projectedTextures \"1\""))
            assertTrue(File(working, "wTf/Config.wtf").readText().contains("SET accountName \"private\""))
            assertEquals("/immutable/Wow.exe", java.nio.file.Files.readSymbolicLink(File(working, "Wow.exe").toPath()).toString())
        }
        assertTrue(built.sourceMetadataUnchanged); assertEquals(true, evidence["clientFullRehashValidated"])
        assertEquals(true, evidence["sourceMetadataUnchanged"])
        assertEquals(result.catalogSha256, ClientStore(root).verify(validate = {}).catalogSha256)
    }
    @Test fun preflightRejectsSpaceAndIdentityBeforeFullFileReadsOrViewPublication() {
        val (root, _, result) = setup(); var reads = 0
        val store = ClientStore(root, beforeIo = { reads++ }); val view = File(temp.newFolder(), "view")
        try { ClientManagedView.run(store, view, result.catalogSha256, validate = {}, pins = pins(), stamp = ::stamp,
            available = { 0 }) { _, _ -> fail() }; fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("insufficient-space", e.reason) }
        assertEquals(0, reads); assertFalse(view.exists())
        try { ClientManagedView.run(store, view, result.catalogSha256, validate = {}, pins = mapOf("missing.exe" to (3L to "0".repeat(64))), stamp = ::stamp) { _, _ -> fail() }; fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("client-build-mismatch", e.reason) }
        assertEquals(0, reads); assertFalse(view.exists())
    }
    @Test fun sameSizeCorruptionAndWrongCatalogCannotReachTheSession() {
        val (root, store, result) = setup(); val view = File(temp.newFolder(), "view")
        try { ClientManagedView.run(store, view, "0".repeat(64), validate = {}, pins = pins(), stamp = ::stamp) { _, _ -> fail() }; fail() }
        catch (e: ClientImportFailure) { assertEquals("client-catalog-mismatch", e.reason) }
        val data = File(File(root, File(root, "active").readText()), "data")
        File(data, "Wow.exe").writeBytes(byteArrayOf(9, 9, 9))
        try { ClientManagedView.run(store, view, result.catalogSha256, validate = {}, pins = pins(), stamp = ::stamp) { _, _ -> fail() }; fail() }
        catch (e: ClientImportFailure) { assertEquals("destination-changed", e.reason) }
        assertFalse(view.exists())
    }
    @Test fun actionFailureStillChecksSourceAndReleasesLeaseAfterCallbackCleanup() {
        val (root, store, result) = setup(); val evidence = mutableMapOf<String, Any>(); var stopped = false
        try { ClientManagedView.run(store, File(temp.newFolder(), "view"), result.catalogSha256,
            validate = {}, pins = pins(), stamp = ::stamp, evidence = { k, v -> evidence[k] = v }) { _, _ ->
            try { throw RuntimeInstallFailure("cancelled") } finally { stopped = true }
        }; fail() } catch (e: RuntimeInstallFailure) { assertEquals("cancelled", e.reason) }
        assertTrue(stopped); assertEquals(true, evidence["sourceMetadataUnchanged"])
        ClientStore(root).verify(validate = {})
    }
    @Test fun sourceMutationDuringSessionCannotBeReportedSuccessful() {
        val (_, store, result) = setup(); val evidence = mutableMapOf<String, Any>()
        try { ClientManagedView.run(store, File(temp.newFolder(), "view"), result.catalogSha256,
            validate = {}, pins = pins(), stamp = ::stamp, evidence = { k, v -> evidence[k] = v }) { original, _ ->
            File(original, "Data/a.mpq").appendBytes(byteArrayOf(1))
        }; fail() } catch (e: RuntimeInstallFailure) { assertEquals("runtime-changed", e.reason) }
        assertEquals(false, evidence["sourceMetadataUnchanged"])
    }
}
