package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream

class ClientRuntimeStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private class Fixture(size: Int = 31) {
        val payload = ByteArray(size) { (it * 17).toByte() }
        val raw: ByteArray
        val compressed: ByteArray
        val text: String
        val plan: RuntimeArchivePlan
        init {
            val output = ByteArrayOutputStream(); val rows = mutableListOf<String>()
            fun entry(kind: String, name: String, mode: Int = 493, data: ByteArray = byteArrayOf(), link: String = "-") {
                output.write(ByteArray(512) { 7 }); val offset = output.size()
                rows.add("$kind\t$name\t$mode\t${data.size}\t$offset\t${if (kind == "f") sha(data) else "-"}\t$link")
                output.write(data); output.write(ByteArray((512 - data.size % 512) % 512))
            }
            entry("s", "bin", link = "/usr/bin") // A guest-absolute link comes before its real directory.
            entry("d", "usr"); entry("d", "usr/bin")
            entry("f", "usr/bin/probe", data = payload)
            entry("f", "empty", mode = 420)
            entry("h", "hard", link = "usr/bin/probe")
            output.write(ByteArray(1024)); raw = output.toByteArray()
            val zip = ByteArrayOutputStream(); GZIPOutputStream(zip).use { it.write(raw) }; compressed = zip.toByteArray()
            text = "FTA_RUNTIME_CATALOG\t1\t${compressed.size}\t${sha(compressed)}\n" + rows.joinToString("\n") +
                "\nEND\t${raw.size}\t${sha(raw)}\n"
            plan = parse(text)
        }
        companion object {
            fun sha(bytes: ByteArray) = runtimeHex(runtimeHash().digest(bytes))
            fun parse(text: String) = RuntimeArchivePlan.read(text.byteInputStream(), sha(text.toByteArray()))
        }
    }
    private fun store(root: File, f: Fixture, io: (Int) -> Unit = {}, cache: (File, Long, Long) -> Unit = { _, _, _ -> }) =
        ClientRuntimeStore(root, f.plan, available = { Long.MAX_VALUE }, beforeIo = io, releaseCache = cache)
    private fun failure(reason: String, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (e: RuntimeInstallFailure) { assertEquals(reason, e.reason) }
    }
    private fun installed(f: Fixture = Fixture()): Pair<File, ClientRuntimeStore> {
        val root = File(temp.newFolder(), "runtime"); val store = store(root, f)
        store.install({ f.compressed.inputStream() }); return root to store
    }
    @Test fun exactBytesModesEmptyFileAndLinksSurviveReopen() {
        val f = Fixture(); val (root, store) = installed(f)
        store.withVerified { data ->
            assertArrayEquals(f.payload, File(data, "usr/bin/probe").readBytes())
            assertEquals(0, File(data, "empty").length())
            assertEquals("/usr/bin", Files.readSymbolicLink(File(data, "bin").toPath()).toString())
            assertTrue(Files.isSameFile(File(data, "hard").toPath(), File(data, "usr/bin/probe").toPath()))
        }
        assertFalse(File(root, "staging").exists())
        assertEquals(File(root, "ready"), store.install({ error("Must reuse and rehash") }))
    }
    @Test fun interruptedExtractionPublishesNothingAndReusesOnlyAuthenticatedArchive() {
        val f = Fixture(2 * ClientRuntimeStore.WINDOW + 9); val root = File(temp.root, "runtime"); val store = store(root, f)
        failure("cancelled") { store.install({ f.compressed.inputStream() }, progress = {
            if (it.stage == "extract" && it.bytes >= ClientRuntimeStore.WINDOW) throw RuntimeInstallFailure("cancelled")
        }) }
        assertFalse(File(root, "ready").exists())
        store.install({ error("Verified compressed archive must be reused") })
        store.withVerified { assertArrayEquals(f.payload, File(it, "usr/bin/probe").readBytes()) }
    }
    @Test fun incompleteDownloadIsReplacedOnlyOnExplicitInstall() {
        val f = Fixture(); val root = File(temp.root, "runtime"); val store = store(root, f)
        failure("archive-changed") { store.install({ f.compressed.copyOf(12).inputStream() }) }
        assertFalse(File(root, "ready").exists()); assertFalse(File(root, "archive.gz").exists())
        store.install({ f.compressed.inputStream() })
    }
    @Test fun wrongCompressedSourceAndTrailingBytesCannotPublish() {
        val f = Fixture()
        val bad = f.compressed.clone(); bad[bad.lastIndex] = (bad.last() + 1).toByte()
        for (bytes in listOf(bad, f.compressed + byteArrayOf(1))) {
            val root = File(temp.newFolder(), "runtime")
            try { store(root, f).install({ bytes.inputStream() }); fail() } catch (_: RuntimeInstallFailure) { }
            assertFalse(File(root, "ready").exists())
        }
    }
    @Test fun cacheCorruptionFailsBeforeExtractionOrSourceOpen() {
        val f = Fixture(); val root = File(temp.root, "runtime"); root.mkdir()
        File(root, "archive.gz").writeBytes(f.compressed.copyOf(12))
        failure("runtime-changed") { store(root, f).install({ error("No network after corrupt cache") }) }
        assertFalse(File(root, "staging").exists())
    }
    @Test fun alteredRuntimeFailsBeforeLeasedCallback() {
        val f = Fixture(); val (root, store) = installed(f)
        File(root, "ready/usr/bin/probe").writeBytes(ByteArray(f.payload.size))
        failure("runtime-changed") { store.withVerified { fail("Must not execute altered runtime") } }
    }
    @Test fun extraMissingAndModeChangedRuntimeAreRejected() {
        for (change in listOf<(File) -> Unit>(
            { File(it, "unexpected").writeText("x") },
            { File(it, "empty").delete() },
            { Files.setPosixFilePermissions(File(it, "usr/bin/probe").toPath(), setOf(PosixFilePermission.OWNER_READ)) }
        )) {
            val (root, store) = installed(); change(File(root, "ready"))
            failure("runtime-changed") { store.withVerified { fail() } }
        }
    }
    @Test fun replacedLinkOrHardlinkCannotPassByteEqualityAlone() {
        for (hard in listOf(true, false)) {
            val f = Fixture(); val (root, store) = installed(f)
            val path = File(root, "ready/${if (hard) "hard" else "bin"}").toPath(); Files.delete(path)
            if (hard) Files.write(path, f.payload) else Files.createSymbolicLink(path, File("/outside").toPath())
            failure("runtime-changed") { store.withVerified { fail() } }
        }
    }
    @Test fun cleanupNeverFollowsGuestAbsoluteOrOutsideLinks() {
        val f = Fixture(); val root = File(temp.root, "runtime"); root.mkdir()
        val outside = temp.newFolder(); File(outside, "keep").writeText("retained")
        val stage = File(root, "staging"); stage.mkdir()
        Files.createSymbolicLink(File(stage, "outside").toPath(), outside.toPath())
        store(root, f).install({ f.compressed.inputStream() })
        assertEquals("retained", File(outside, "keep").readText())
    }
    @Test fun storeRootAndStagingSymlinkAreRejectedWithoutFollowing() {
        val f = Fixture(); val outside = temp.newFolder()
        val link = File(temp.root, "link"); Files.createSymbolicLink(link.toPath(), outside.toPath())
        failure("unsafe-runtime-store") { store(link, f).install({ error("No source") }) }
        val root = temp.newFolder(); Files.createSymbolicLink(File(root, "staging").toPath(), outside.toPath())
        failure("unsafe-runtime-store") { store(root, f).install({ error("No source") }) }
    }
    @Test fun verifiedCallbackHoldsLeaseAgainstAnotherStoreInstance() {
        val f = Fixture(); val (root, first) = installed(f); val second = store(root, f)
        first.withVerified { failure("busy") { second.install({ error("No source") }) } }
        second.withVerified { assertTrue(it.isDirectory) }
    }
    @Test fun verificationMilestonesIncludeLeaseReleaseAndNeverCompleteAfterRehashFailure() {
        val f = Fixture(); val (_, store) = installed(f); val phases = mutableListOf<String>()
        store.withVerified(reverifyAfter = true, milestone = { phases.add(it) }) { }
        assertEquals(listOf("runtime-lease-acquire", "runtime-pre-verify", "runtime-pre-verified", "verified-action",
            "runtime-post-verify", "runtime-post-verified", "runtime-lease-release", "runtime-verified"), phases)
        phases.clear()
        failure("runtime-changed") { store.withVerified(reverifyAfter = true, milestone = { phases.add(it) }) {
            File(it, "usr/bin/probe").writeBytes(ByteArray(f.payload.size))
        } }
        assertEquals("runtime-post-verify", phases.last()); assertFalse("runtime-verified" in phases)
    }
    @Test fun optionalPostRunRehashRejectsNativeRuntimeMutations() {
        val f = Fixture(); val (_, store) = installed(f)
        failure("runtime-changed") { store.withVerified(reverifyAfter = true) { data ->
            File(data, "usr/bin/probe").writeBytes(ByteArray(f.payload.size))
        } }
    }
    @Test fun preVerificationRecoveryHoldsLeaseAndCannotBypassTheFullRehash() {
        val f = Fixture(); val (root, first) = installed(f); val second = store(root, f)
        failure("runtime-changed") {
            first.withVerified(beforeVerify = { ready ->
                failure("busy") { second.withVerified { fail() } }
                File(ready, "usr/bin/probe").writeBytes(ByteArray(f.payload.size))
            }) { fail("Changed runtime must not reach the verified callback") }
        }
    }
    @Test fun insufficientSpaceFailsBeforeSourceOpen() {
        val f = Fixture(); val root = File(temp.root, "runtime")
        failure("insufficient-space") { ClientRuntimeStore(root, f.plan, available = { ClientRuntimeStore.RESERVE })
            .install({ error("No source") }) }
    }
    @Test fun writesReadbacksAndCacheAdviceStayBoundedAndReadbackDetectsCorruption() {
        val f = Fixture(2 * ClientRuntimeStore.WINDOW + 19); val root = File(temp.root, "runtime")
        var io = 0L; var windows = 0
        val store = store(root, f, { assertTrue(it in 1..ClientRuntimeStore.BUFFER); io += it }, { file, offset, bytes ->
            assertTrue(bytes in 1..(ClientRuntimeStore.WINDOW + ClientRuntimeStore.BUFFER).toLong())
            if (file.name == "probe") { windows++; assertTrue(offset >= 0) }
        })
        store.install({ f.compressed.inputStream() })
        assertTrue(windows >= 6); assertTrue(io >= f.payload.size * 3L)
        val changed = File(temp.root, "changed"); var corrupt = true
        failure("runtime-changed") { store(changed, f, cache = { file, _, _ ->
            if (file.name == "probe" && corrupt) { corrupt = false; RandomAccessFile(file, "rw").use { it.writeByte(99) } }
        }).install({ f.compressed.inputStream() }) }
        assertFalse(File(changed, "ready").exists())
    }
    @Test fun cancellationDuringFullRehashKeepsPublicationAbsent() {
        val f = Fixture(); val root = File(temp.root, "runtime")
        failure("cancelled") { store(root, f).install({ f.compressed.inputStream() }, progress = {
            if (it.stage == "verify-runtime") throw RuntimeInstallFailure("cancelled")
        }) }
        assertFalse(File(root, "ready").exists())
    }
    @Test fun catalogIdentityCheckedBeforeParsing() {
        failure("invalid-catalog") { RuntimeArchivePlan.read(Fixture().text.byteInputStream(), "0".repeat(64)) }
    }
    @Test fun catalogRejectsTraversalLinkParentsOverlapsAndUnsupportedModes() {
        val text = Fixture().text
        val changes = listOf(
            text.replace("\tusr/bin/probe\t", "\t../escape\t"),
            text.replace("\tusr/bin/probe\t", "\tbin/probe\t"),
            text.replace("\t493\t", "\t2541\t"),
            text.replace("\t1024\t", "\t512\t"),
            text.replace("\t/usr/bin\n", "\t../../outside\n"),
            text.replace("h\thard", "s\thard").replace("\tusr/bin/probe\n", "\t../../escape\n"),
            text.replace("h\thard", "h\thard").replace("\tusr/bin/probe\n", "\tbin\n"),
            text.replace("\nEND", "\nf\tempty\t420\t0\t4096\t${Fixture.sha(byteArrayOf())}\t-\nEND")
        )
        for (changed in changes) failure("invalid-catalog") { Fixture.parse(changed) }
    }
    @Test fun declaredExpandedHashAndPayloadOffsetsCannotWeakenIntegrity() {
        val f = Fixture()
        val bad = Fixture.parse(f.text.replace(Fixture.sha(f.raw), "0".repeat(64)))
        val root = File(temp.root, "runtime")
        failure("archive-changed") { ClientRuntimeStore(root, bad, available = { Long.MAX_VALUE }).install({ f.compressed.inputStream() }) }
        assertFalse(File(root, "ready").exists())
    }
}
