package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorldUpdaterConfigTest {
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
}
