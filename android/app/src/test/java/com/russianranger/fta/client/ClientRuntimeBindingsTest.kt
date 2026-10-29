package com.russianranger.fta.client

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions

class ClientRuntimeBindingsTest {
    @get:Rule val temp = TemporaryFolder()
    private val directories = mutableListOf<File>()
    private fun plan(name: String = "present"): RuntimeArchivePlan {
        val sha = "0".repeat(64)
        val text = "FTA_RUNTIME_CATALOG\t1\t1\t$sha\nd\t$name\t493\t0\t512\t-\t-\nEND\t1024\t$sha\n"
        return RuntimeArchivePlan.read(text.byteInputStream(), runtimeHex(runtimeHash().digest(text.toByteArray())))
    }
    private fun stat(file: File): ClientRuntimeBindings.Identity {
        fun value(name: String) = (Files.getAttribute(file.toPath(), "unix:$name", NOFOLLOW_LINKS) as Number)
        return ClientRuntimeBindings.Identity(value("mode").toInt(), value("uid").toInt(), value("nlink").toLong())
    }
    private fun zero(root: File, name: String): File = File(root, name).apply {
        mkdir(); directories.add(this); Files.setPosixFilePermissions(toPath(), emptySet())
    }
    @After fun restoreFixturePermissions() {
        for (file in directories) if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwx------"))
    }
    private fun recover(root: File, observed: (Int) -> Unit = {}) = ClientRuntimeBindings.recover(root, plan(), stat(root).uid,
        ::stat, { Files.delete(it.toPath()) }, removed = observed)
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected strict refusal") } catch (e: RuntimeInstallFailure) { assertEquals("runtime-bind-recovery-refused", e.reason) }
    }
    @Test fun onlyExactEmptyPermissionZeroTargetsAreRemovedAndRepeatIsReadOnly() {
        val root = temp.newFolder(); File(root, "present").mkdir(); zero(root, "immutable"); zero(root, "view")
        val counts = mutableListOf<Int>()
        assertEquals(2, recover(root) { counts.add(it) }); assertEquals(listOf(1, 2), counts)
        assertEquals(setOf("present"), root.list()!!.toSet()); assertEquals(0, recover(root))
    }
    @Test fun regularFilesLinksWrongModeOwnerAndLinkCountsAreRefusedBeforeRemoval() {
        for (kind in listOf("file", "link", "mode", "owner", "links")) {
            val root = temp.newFolder(); zero(root, "immutable")
            val target = File(root, "view")
            when (kind) {
                "file" -> target.writeText("keep")
                "link" -> Files.createSymbolicLink(target.toPath(), File(root, "immutable").toPath())
                "mode" -> target.mkdir()
                else -> zero(root, "view")
            }
            refused { ClientRuntimeBindings.recover(root, plan(), stat(root).uid, { file ->
                val s = stat(file)
                if (file.name != "view") s else when (kind) {
                    "owner" -> s.copy(uid = s.uid + 1); "links" -> s.copy(links = 3); else -> s
                }
            }, { fail("Nothing may be removed") }) }
            assertTrue(Files.exists(File(root, "immutable").toPath(), NOFOLLOW_LINKS))
        }
    }
    @Test fun nonemptyDirectoryFailsWithoutRemovingOrChangingItsContents() {
        val root = temp.newFolder(); val path = File(root, "immutable").apply { mkdir() }
        File(path, "preserve").writeText("retained")
        directories.add(path); Files.setPosixFilePermissions(path.toPath(), emptySet())
        try { recover(root); fail() } catch (_: java.nio.file.DirectoryNotEmptyException) { }
        assertEquals(0x4000, stat(path).mode)
        Files.setPosixFilePermissions(path.toPath(), PosixFilePermissions.fromString("rwx------"))
        assertEquals("retained", File(path, "preserve").readText())
    }
    @Test fun cataloguedTargetsAndUnrelatedEntriesAreNeverRemoved() {
        val root = temp.newFolder(); val path = zero(root, "immutable"); zero(root, "other")
        refused { ClientRuntimeBindings.recover(root, plan("immutable"), stat(root).uid, ::stat, { fail() }) }
        assertTrue(path.exists()); assertEquals(1, recover(root)); assertTrue(File(root, "other").exists())
    }
    @Test fun cancellationDoesNotRemovePlaceholders() {
        val root = temp.newFolder(); val path = zero(root, "immutable")
        try { ClientRuntimeBindings.recover(root, plan(), stat(root).uid, ::stat, { fail() },
            active = { throw RuntimeInstallFailure("cancelled") }); fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("cancelled", e.reason) }
        assertTrue(path.exists())
    }
}
