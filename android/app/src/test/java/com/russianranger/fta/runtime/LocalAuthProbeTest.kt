package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalAuthProbeTest {
    private val name = "FTA0123456789ABC"
    @Test fun productionGeneratedDatabaseRoleCanRenderTheListenerConfig() {
        val user = LocalAuthProbe.databaseUser(UUID.fromString("01234567-89ab-4cde-8fab-0123456789ab"))
        assertEquals("fta_local_0123456789ab4cde", user)
        val config = LocalAuthProbe.config(File("/private/db.sock"), File("/native/client"), File("/private/Data"), File("/private/tmp"), user, "0123456789abcdef".repeat(3), 3724)
        assertTrue(config.contains("LoginDatabaseInfo = \".;/private/db.sock;$user;0123456789abcdef0123456789abcdef0123456789abcdef;acore_auth\""))
        assertTrue(config.contains("RealmServerPort = 3724\n"))
    }
    @Test fun generatedV4DatabaseRolesAlwaysIncludeTheVersionDigitAndRemainValid() {
        repeat(100) {
            val user = LocalAuthProbe.databaseUser(UUID.randomUUID())
            assertEquals('4', user["fta_local_".length + 12])
            LocalAuthProbe.config(File("/db.sock"), File("/client"), File("/Data"), File("/tmp"), user, "a".repeat(48), 3724)
        }
    }
    @Test fun originalWirePacketMatchesBuild12340Structure() {
        val bytes = LocalAuthProbe.packet(name)
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("00002e00576f57000303053430363878006e69570053556e65000000007f0000011046544130313233343536373839414243", hex)
    }
    @Test fun probeNamesCannotAddressExistingOrArbitraryAccounts() {
        listOf("ADMIN", "FTA0123456789AB", "FTA0123456789ABCD", "FTA0123456789AB'", "FTA0123456789abc").forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalAuthProbe.packet(it) }
        }
    }
    @Test fun replyMustBeExactUnknownAccountResponse() {
        LocalAuthProbe.validateReply(byteArrayOf(0, 0, 4))
        listOf(byteArrayOf(), byteArrayOf(0, 4), byteArrayOf(0, 0, 0), byteArrayOf(1, 0, 4), byteArrayOf(0, 0, 4, 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalAuthProbe.validateReply(it) }
        }
    }
    @Test fun configRetainsOriginalUpdaterPolicyAndOnlyBindsIpv4Loopback() {
        val config = LocalAuthProbe.config(File("/private/db.sock"), File("/native/client"), File("/private/Data"), File("/private/tmp"), "fta_local_test", "a".repeat(48), 3724)
        assertTrue(config.contains("BindIP = \"127.0.0.1\"")); assertTrue(config.contains("RealmServerPort = 3724"))
        assertTrue(config.contains("Updates.EnableDatabases = 1")); assertTrue(config.contains("Updates.Redundancy = 1"))
        assertTrue(config.contains("Network.UseSocketActivation = 0")); assertFalse(config.contains("0.0.0.0"))
        for (port in listOf(-1, 0, 80, 65536)) assertThrows(IllegalArgumentException::class.java) {
            LocalAuthProbe.config(File("/db"), File("/client"), File("/Data"), File("/tmp"), "fta_local_test", "a".repeat(48), port)
        }
    }
    private fun endpoint(reply: ByteArray, action: (Int) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            server.soTimeout = 3000
            val future = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val received = ByteArray(50); var n = 0
                    while (n < received.size) { val got = socket.getInputStream().read(received, n, received.size - n); check(got > 0); n += got }
                    assertArrayEquals(LocalAuthProbe.packet(name), received)
                    reply.forEach { socket.getOutputStream().write(it.toInt()); socket.getOutputStream().flush() }
                }
            }
            try { action(server.localPort); future.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }
    @Test fun actualLoopbackExchangeAcceptsFragmentedResponse() = endpoint(byteArrayOf(0, 0, 4)) { LocalAuthProbe.exchange(it, name) }
    @Test fun actualLoopbackExchangeRejectsWrongResponse() = endpoint(byteArrayOf(0, 0, 0)) { port ->
        assertThrows(IllegalArgumentException::class.java) { LocalAuthProbe.exchange(port, name) }
    }
    @Test fun actualLoopbackExchangeRejectsTruncatedResponse() = endpoint(byteArrayOf(0, 0)) { port ->
        assertThrows(IllegalStateException::class.java) { LocalAuthProbe.exchange(port, name) }
    }
    private class Driver : LocalAuthProtocol.Driver {
        val events = mutableListOf<String>(); var pass = 0; var fail = ""; var stopped = true
        override fun checkActive() { if (fail == "cancel") error("cancelled") }
        override fun start(pass: Int) { this.pass = pass; stopped = false; events += "start$pass" }
        override fun probe() { if (fail == "probe$pass") error("protocol failed"); events += "probe$pass" }
        override fun hold() { if (fail == "hold$pass") error("child died"); events += "hold$pass" }
        override fun stop(): Int { stopped = true; events += "stop$pass"; return if (fail == "stop$pass") 137 else 0 }
        override fun listenerClosed() = fail != "open$pass"
    }
    @Test fun bothListenerPassesRequireReadinessHealthCleanStopAndClosedPort() {
        val driver = Driver(); val flags = mutableMapOf<String, Boolean>()
        LocalAuthProtocol.run(driver) { k, v -> flags[k] = v }
        assertEquals(LocalAuthProtocol.fields.toSet(), flags.keys); assertTrue(flags.values.all { it })
        assertTrue(driver.events.indexOf("stop1") < driver.events.indexOf("start2")); assertTrue(driver.stopped)
    }
    @Test fun failedFirstListenerIsStoppedWithoutStartingSecond() {
        for (failure in listOf("probe1", "hold1", "stop1", "open1")) {
            val driver = Driver().apply { fail = failure }; val flags = mutableMapOf<String, Boolean>()
            assertThrows(IllegalStateException::class.java) { LocalAuthProtocol.run(driver) { k, v -> flags[k] = v } }
            assertTrue(driver.stopped); assertFalse(driver.events.contains("start2")); assertFalse(flags.containsKey("secondListenerClosed"))
        }
    }
    @Test fun failedSecondListenerCannotQualifyTheAttempt() {
        for (failure in listOf("probe2", "hold2", "stop2", "open2")) {
            val driver = Driver().apply { fail = failure }; val flags = mutableMapOf<String, Boolean>()
            assertThrows(IllegalStateException::class.java) { LocalAuthProtocol.run(driver) { k, v -> flags[k] = v } }
            assertTrue(driver.stopped); assertEquals(true, flags["firstListenerClosed"]); assertFalse(flags.containsKey("secondListenerClosed"))
        }
    }
    @Test fun cancellationBeforeStartCreatesNoChild() {
        val driver = Driver().apply { fail = "cancel" }
        assertThrows(IllegalStateException::class.java) { LocalAuthProtocol.run(driver) { _, _ -> } }
        assertTrue(driver.events.isEmpty())
    }
    @Test fun staleStartupFlagCanBeClearedOnlyForTheGuardedOfflineRealm() {
        for (before in listOf("1:1:0", "1:1:1")) {
            val queries = mutableListOf<String>()
            val changed = LocalRealmAuthState.prepare { queries += it; if (queries.size == 1) before else "1" }
            assertEquals(before.endsWith("1"), changed)
            assertEquals(listOf(LocalRealmAuthState.BEFORE, LocalRealmAuthState.PREPARE), queries)
            assertTrue(queries.last().contains("flag=(flag & ~1) | 2 WHERE id=1 AND gamebuild=12340 AND flag IN(0,1,2,3)"))
        }
    }
    @Test fun wrongBuildMissingRealmAndUnexpectedFlagsCannotTriggerWrites() {
        for (before in listOf("0:0:0", "1:0:1", "1:0:0", "2:1:1", "1:1:3", "1:1:1\nextra")) {
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) { LocalRealmAuthState.prepare { calls++; before } }
            assertEquals(1, calls)
        }
    }
    @Test fun offlineTransitionMustBeVerifiedBeforeAuthCanStart() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) { LocalRealmAuthState.prepare { if (++calls == 1) "1:1:1" else "0" } }
    }
    @Test fun evidenceRequiresTwoAuthStopsBothMetadataChecksAndSqlShutdown() {
        val root = Files.createTempDirectory("fta-local-auth-proof").toFile()
        try {
            val attempt = UUID.randomUUID().toString(); val file = File(root, "journal.properties")
            val journal = NativeInvocationJournal(file, attempt)
            val db = journal.begin("server", "database-server", "REALM_REOPEN", null)
            journal.checkpoint(db, "database_ready")
            var i = 0
            repeat(6) { val id = journal.begin("tool-${++i}", "database-client", "REALM_METADATA", 1000); journal.finish(id, "exited", 0, 1, 0) }
            repeat(2) { val id = journal.begin("tool-${++i}", "database-client", "REALM_VERIFY", 1000); journal.finish(id, "exited", 0, 1, 0) }
            val auth = (1..2).map { val id = journal.begin("tool-${++i}", "auth-server", "LOCAL_AUTH_START", 90000); journal.finish(id, "exited", 0, 1, 0); journal.terminated(id, 0); id }
            journal.checkpoint(db, "sql_shutdown_requested"); journal.finish(db, "exited", 0, 0, 0)
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt)["qualified"])
            journal.checkpoint(db, "clean_shutdown")
            assertEquals(true, LocalAuthDiagnostic.nativeEvidence(file, attempt)["qualified"])
            journal.terminated(auth.last(), 137)
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt)["qualified"])
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, UUID.randomUUID().toString())["qualified"])
        } finally { root.deleteRecursively() }
    }
}
