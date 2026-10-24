package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorldUpdaterConfigTest {
    @Test fun retainedWorldUsesGeneratedRoleRandomLoopbackPortAndNeverAutoSetsUpSchemas() {
        val role = LocalAuthProbe.databaseUser(java.util.UUID.fromString("f1234567-89ab-cdef-0123-456789abcdef"))
        val config = WorldUpdaterConfig.render(File("/socket"), File("/client"), File("/source"), File("/assets"),
            File("/temp"), File("/log"), role, "a".repeat(48), 43210, true)
        assertTrue(config.contains("WorldServerPort = 43210"))
        assertTrue(config.contains("Updates.AutoSetup = 0"))
        assertTrue(config.contains("MaxCoreStuckTime = 60"))
        assertTrue(config.contains("DataDir = \"/assets\""))
        for (port in listOf(0, 1023, 65536)) assertThrows(Exception::class.java) {
            WorldUpdaterConfig.render(File("/s"), File("/c"), File("/o"), File("/d"), File("/t"), File("/l"), role, "a".repeat(48), port, true)
        }
    }
    private fun render(socket: File = File("/private/run/db.sock"), password: String = "a".repeat(48)) =
        WorldUpdaterConfig.render(socket, File("/apk/libfta_mysql.so"), File("/private/Data"), File("/private/empty-data"),
            File("/private/temp"), File("/private/world-updater.log"), "fta_world_diag", password)

    @Test fun originalNormalModeKeepsAllThreeUpdatersAndSynchronousOrderedErrorLogs() {
        val config = render()
        assertTrue(config.contains("Updates.EnableDatabases = 7")); assertTrue(config.contains("Updates.Redundancy = 1"))
        assertTrue(config.contains("Updates.AllowRehash = 1")); assertTrue(config.contains("Updates.CleanDeadRefMaxCount = 3"))
        assertTrue(config.contains("Log.Async.Enable = 0")); assertTrue(config.contains("Appender.Gate = 2,4,6"))
        assertTrue(config.contains("Cluster.Enabled = 0")); assertTrue(config.contains("Ra.Enable = 0")); assertTrue(config.contains("SOAP.Enabled = 0"))
        assertTrue(config.contains("BindIP = \"127.0.0.1\"")); assertFalse(config.contains("dry-run", true))
        assertEquals(3, config.lines().count { it.contains("DatabaseInfo = \".;/private/run/db.sock;") })
    }

    @Test fun privateConfigRejectsPathAndCredentialInjection() {
        for (path in listOf("relative.sock", "/private/a;DROP", "/private/a\nLogger.root=0", "/private/\"socket"))
            assertThrows(Exception::class.java) { render(File(path)) }
        assertThrows(Exception::class.java) { render(password = "secret\"\n") }
    }

    @Test fun supportedPriorityAndAffinityKeysKeepOrdinaryAndroidScheduling() {
        val settings = render().lineSequence().map { it.trim() }.toList()
        // The native CONFIG_HIGH_PRIORITY constant is ProcessPriority. A similarly
        // named HighPriority option is ignored and would retain the privileged default.
        assertEquals(1, settings.count { it == "ProcessPriority = 0" })
        assertEquals(1, settings.count { it == "UseProcessors = 0" })
        assertFalse(settings.any { it.startsWith("HighPriority") })
    }
}
