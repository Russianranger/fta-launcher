package com.russianranger.fta.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AuthDiagnosticConfigTest {
    private fun render(socket: String = "/private/run/db.sock", password: String = "a".repeat(48), user: String = "fta_auth_diag") =
        AuthDiagnosticConfig.render(File(socket), File("/immutable/libclient.so"), File("/private/auth/Data"), File("/private/auth/tmp"), user, password)

    @Test fun preservesOriginalUpdaterAndSocketOnlyDatabaseConfig() {
        val config = render()
        assertTrue(config.startsWith("[authserver]\n"))
        assertTrue(config.contains("LoginDatabaseInfo = \".;/private/run/db.sock;fta_auth_diag;"))
        assertTrue(config.contains("Updates.EnableDatabases = 1\n"))
        assertTrue(config.contains("Updates.AutoSetup = 1\n"))
        assertTrue(config.contains("Updates.Redundancy = 1\n"))
        assertTrue(config.contains("Updates.ArchivedRedundancy = 0\n"))
        assertTrue(config.contains("Updates.AllowRehash = 1\n"))
        assertTrue(config.contains("Updates.CleanDeadRefMaxCount = 3\n"))
        assertTrue(config.contains("RealmServerPort = -1\n"))
        assertTrue(config.contains("BindIP = \"127.0.0.1\""))
    }
    @Test fun credentialsCannotInjectAdditionalConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { render(password = "a".repeat(48) + "\nRealmServerPort=3724") }
    }
    @Test fun databaseRoleNamesRejectDelimitersUnicodeWhitespaceAndOversizeInputs() {
        for (user in listOf("", "0fta", "a".repeat(33), "fta;root", "fta'--", "fta\"", "fta\nuser=root", "fta\r", "fta\u0000", "fta user", "fta-user", "fta@localhost", "ftaé", "FTA")) {
            assertThrows(IllegalArgumentException::class.java) { render(user = user) }
        }
    }
    @Test fun databaseRoleNamesAcceptSafeAsciiDigitsWithinTheExistingLengthBound() {
        for (user in listOf("a", "_", "fta_auth_diag", "fta_local_0123456789abcdef", "a" + "9".repeat(31))) {
            assertTrue(render(user = user).contains(";$user;"))
        }
    }
    @Test fun pathsCannotAddDatabaseConnectionTokens() {
        assertThrows(IllegalArgumentException::class.java) { render(socket = "/private/run/db.sock;other-user") }
        assertThrows(IllegalArgumentException::class.java) { render(socket = "relative.sock") }
    }
}
