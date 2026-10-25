package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorldSessionExecutableTest {
    private val root = Files.createTempDirectory("fta-world-session-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private val world = RuntimeFile("native/libfta_worldserver.so", WorldSessionExecutable.BASE_WORLD, 62661136, "world-server")
    private val lock = RuntimeFile(WorldUpdaterMetadata.PATH, WorldSessionExecutable.UPDATER, 1, "data")
    private val build = WorldBuildMetadata("android-arm64-world-v13", WorldSessionExecutable.BASE_TREE,
        "907a0a0bfde4f86b0471f798d93d2a74fdc50ece398cbb31c0ba22ddbc8e81bb", WorldSessionExecutable.BASE_WORLD, WorldSessionExecutable.UPDATER)
    private fun manifest() = RuntimeManifest(1, "arm64-v8a", "12.3.2", setOf("database", "authserver", "worldserver"),
        listOf(world, lock), worldBuild = build)

    @Test fun retainedIdentityIsRequiredWithoutMigration() {
        val good = manifest()
        WorldSessionExecutable.requireBaseline(WorldSessionExecutable.BASE_MANIFEST, good)
        assertThrows(IllegalArgumentException::class.java) { WorldSessionExecutable.requireBaseline("0".repeat(64), good) }
        for (bad in listOf(good.copy(worldBuild = null), good.copy(worldBuild = build.copy(portProfile = "android-arm64-world-v14")),
            good.copy(worldBuild = build.copy(sourceTreeSha256 = "0".repeat(64))),
            good.copy(files = listOf(world.copy(sha256 = "0".repeat(64)), lock)),
            good.copy(files = listOf(world, lock.copy(sha256 = "0".repeat(64)))))) {
            assertThrows(IllegalArgumentException::class.java) { WorldSessionExecutable.requireBaseline(WorldSessionExecutable.BASE_MANIFEST, bad) }
        }
    }
    @Test fun updaterDatabaseAndAuthContinueUsingTheirOriginalExecutables() {
        val original = listOf("world-server", "database-server", "database-client", "auth-server").associateWith { File(root, it) }
        val repaired = File(root, WorldSessionExecutable.NAME)
        for (role in original.keys) {
            assertEquals(original[role], WorldSessionExecutable.select(role, original, false, repaired))
            assertEquals(if (role == "world-server") repaired else original[role], WorldSessionExecutable.select(role, original, true, repaired))
        }
        assertThrows(IllegalArgumentException::class.java) { WorldSessionExecutable.select("world-server", original, true, null) }
        assertThrows(IllegalArgumentException::class.java) { WorldSessionExecutable.select("world-server", original, true, File(root, "other.so")) }
        assertThrows(IllegalArgumentException::class.java) { WorldSessionExecutable.select("unknown", original, true, repaired) }
    }
    @Test fun missingChangedOrLinkedRepairCannotBeLaunched() {
        val runtime = PackagedRuntime(manifest(), root, emptyMap(), root, WorldSessionExecutable.BASE_MANIFEST)
        assertThrows(Exception::class.java) { WorldSessionExecutable.prepare(runtime) }
        val binary = File(root, WorldSessionExecutable.NAME).apply { writeText("changed") }
        assertThrows(Exception::class.java) { WorldSessionExecutable.prepare(runtime) }
        binary.delete()
        val elsewhere = File(root, "elsewhere").apply { writeText("changed") }
        Files.createSymbolicLink(binary.toPath(), elsewhere.toPath())
        assertThrows(Exception::class.java) { WorldSessionExecutable.prepare(runtime) }
    }
    @Test fun earlierOrDifferentExecutableCannotQualifyCurrentSession() {
        assertTrue(WorldSessionExecutable.receiptMatches(WorldSessionExecutable.SHA256, WorldSessionExecutable.PROFILE))
        assertFalse(WorldSessionExecutable.receiptMatches(WorldSessionExecutable.BASE_WORLD, WorldSessionExecutable.PROFILE))
        assertFalse(WorldSessionExecutable.receiptMatches(WorldSessionExecutable.SHA256, "android-arm64-world-v13"))
        assertFalse(WorldSessionExecutable.receiptMatches("", ""))
    }
}
