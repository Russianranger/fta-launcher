package com.russianranger.fta.runtime

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class WorldUpdaterEnvironmentTest {
    private val root = Files.createTempDirectory("fta-world-environment").toFile()
    private val base = arrayOf("PATH=/system/bin", "HOME=${root.absolutePath}", "LD_LIBRARY_PATH=/private/apk-libraries")
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun supportedOverrideBindsOnlyTheWorldProcessToItsFreshEmptyPrivateQueue() {
        val queue = WorldUpdaterEnvironment.createQueue(root)
        assertEquals(File(root, "world-inspector-queue"), queue)
        assertTrue(queue.isAbsolute); assertTrue(queue.list()!!.isEmpty())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(queue.toPath()))
        val world = WorldUpdaterEnvironment.forRole(base, "world-server", root, queue)
        assertArrayEquals(base + "AC_WORLD_INSPECTOR_RUNTIME_QUEUE_DIR=${queue.absolutePath}", world)
        assertEquals(1, world.count { it.startsWith("AC_WORLD_INSPECTOR_RUNTIME_QUEUE_DIR=") })
        assertEquals(3, base.size)
        for (role in listOf("database-server", "database-client", "auth-server")) {
            assertArrayEquals(base, WorldUpdaterEnvironment.forRole(base, role, root, queue))
            assertArrayEquals(base, WorldUpdaterEnvironment.forRole(base, role, root, null))
        }
    }

    @Test fun existingQueueIsNeverReusedOrChanged() {
        val existing = File(root, WorldUpdaterEnvironment.QUEUE_NAME).apply { mkdir() }
        val sentinel = File(existing, "existing-job").apply { writeText("untouched") }
        assertThrows(Exception::class.java) { WorldUpdaterEnvironment.createQueue(root) }
        assertEquals("untouched", sentinel.readText())
    }

    @Test fun nonemptyOrNonprivateQueueCannotBePassedToWorld() {
        val queue = WorldUpdaterEnvironment.createQueue(root)
        val job = File(queue, "unexpected-job").apply { writeText("untouched") }
        assertThrows(Exception::class.java) { WorldUpdaterEnvironment.forRole(base, "world-server", root, queue) }
        assertEquals("untouched", job.readText()); job.delete()
        Files.setPosixFilePermissions(queue.toPath(), Files.getPosixFilePermissions(queue.toPath()) + PosixFilePermission.GROUP_READ)
        assertThrows(Exception::class.java) { WorldUpdaterEnvironment.forRole(base, "world-server", root, queue) }
    }

    @Test fun queueLinksAndPathsOutsideThisDisposableRootAreRejected() {
        val elsewhere = Files.createTempDirectory("fta-unrelated-environment").toFile()
        try {
            val unrelated = WorldUpdaterEnvironment.createQueue(elsewhere)
            assertThrows(Exception::class.java) { WorldUpdaterEnvironment.forRole(base, "world-server", root, unrelated) }
            val link = File(root, WorldUpdaterEnvironment.QUEUE_NAME)
            Files.createSymbolicLink(link.toPath(), unrelated.toPath())
            assertThrows(Exception::class.java) { WorldUpdaterEnvironment.createQueue(root) }
            assertThrows(Exception::class.java) { WorldUpdaterEnvironment.forRole(base, "world-server", root, link) }
            assertTrue(unrelated.isDirectory); Files.delete(link.toPath())
        } finally { elsewhere.deleteRecursively() }
    }

    @Test fun missingQueueUnsafePathAndInjectedBaseVariableFailClosed() {
        assertThrows(Exception::class.java) { WorldUpdaterEnvironment.forRole(base, "world-server", root, null) }
        val queue = WorldUpdaterEnvironment.createQueue(root)
        val injected = base + "AC_WORLD_INSPECTOR_RUNTIME_QUEUE_DIR=/some/unrelated/queue"
        for (role in listOf("database-server", "database-client", "auth-server", "world-server"))
            assertThrows(Exception::class.java) { WorldUpdaterEnvironment.forRole(injected, role, root, queue) }
        val unsafe = File(root, "unsafe;environment\npath").apply { mkdir() }
        assertThrows(Exception::class.java) { WorldUpdaterEnvironment.createQueue(unsafe) }
        assertFalse(File(unsafe, WorldUpdaterEnvironment.QUEUE_NAME).exists())
    }
}
