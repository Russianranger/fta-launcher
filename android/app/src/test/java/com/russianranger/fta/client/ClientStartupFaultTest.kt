package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientStartupFaultTest {
    private fun example(): Map<String, Long> = ClientStartupFault.bounds.mapValues { 0L } + mapOf(
        "pidCorrelated" to 1L, "accessViolationCount" to 1L, "exceptionCode" to 0xc0000005L,
        "exceptionAddress" to 0x401234L, "accessType" to 1L, "accessAddress" to 0x100000L,
        "accessDetailsKnown" to 1L, "moduleId" to 1L, "moduleBase" to 0x400000L,
        "moduleSize" to 0x20000L, "moduleRva" to 0x1234L, "loadedClientModules" to 1L,
        "exitCode" to 0xc0000005L, "exitObserved" to 1L, "exitElapsedMs" to 1234L, "exceptionMatchesExit" to 1L)
    private fun text(values: Map<String, Long>) = values.entries.joinToString("\n") { "FTA_CLIENT_FAULT ${it.key} ${it.value}" } + "\n"

    @Test fun onlyCompleteTypedBoundedFramesAreExportable() {
        val good = example(); assertEquals(good, ClientStartupFault.parse("PRIVATE ignored\n" + text(good)))
        assertTrue(ClientStartupFault.locationCandidateAvailable(good))
        for (key in good.keys) {
            assertTrue(key, ClientStartupFault.validate(good - key).isEmpty())
            assertTrue(key, ClientStartupFault.validate(good + (key to "0")).isEmpty())
            assertTrue(key, ClientStartupFault.validate(good + (key to -1L)).isEmpty())
            assertTrue(key, ClientStartupFault.validate(good + (key to ClientStartupFault.bounds.getValue(key) + 1)).isEmpty())
        }
        assertTrue(ClientStartupFault.parse(text(good) + "FTA_CLIENT_FAULT private 1\n").isEmpty())
        assertTrue(ClientStartupFault.parse(text(good) + "FTA_CLIENT_FAULT moduleId 1\n").isEmpty())
    }
    @Test fun regionAndModuleObservationsMustAgree() {
        for ((key, bad) in listOf("exceptionAddress" to 0x500000L, "moduleRva" to 1L, "moduleSize" to 0L,
            "loadedClientModules" to 0L, "moduleId" to 0L, "accessType" to 2L, "exceptionCode" to 1L,
            "pidCorrelated" to 0L, "exitObserved" to 0L, "exceptionMatchesExit" to 0L))
            assertTrue(key, ClientStartupFault.validate(example() + (key to bad)).isEmpty())
    }
    @Test fun incompleteTraceOrHandledExceptionDoesNotProvideALocationCandidate() {
        for ((key, value) in listOf("traceLinesDropped" to 1L, "traceStateOverflow" to 1L)) {
            val observation = example() + (key to value)
            assertFalse(ClientStartupFault.validate(observation).isEmpty())
            assertFalse(ClientStartupFault.locationCandidateAvailable(observation))
        }
        val handled = example() + mapOf("exitCode" to 0L, "exceptionMatchesExit" to 0L)
        assertFalse(ClientStartupFault.validate(handled).isEmpty()); assertFalse(ClientStartupFault.locationCandidateAvailable(handled))
    }
    @Test fun noExceptionAndUnknownModuleAreValidLimitedObservations() {
        val none = ClientStartupFault.bounds.mapValues { 0L } + mapOf("pidCorrelated" to 1L, "accessType" to 9L)
        assertEquals(none, ClientStartupFault.validate(none)); assertFalse(ClientStartupFault.locationCandidateAvailable(none))
        val unknown = example() + mapOf("moduleId" to 0L, "moduleBase" to 0L, "moduleSize" to 0L, "moduleRva" to 0L)
        assertEquals(unknown, ClientStartupFault.validate(unknown)); assertFalse(ClientStartupFault.locationCandidateAvailable(unknown))
        assertTrue(ClientStartupFault.validate(unknown + ("moduleRva" to 1L)).isEmpty())
    }

    @Test fun hostFramesKeep64BitAddressesAndCannotBeUsedAsClientFrames() {
        val host = example() + mapOf("moduleBase" to 0x140000000L, "exceptionAddress" to 0x140001234L)
        val encoded = text(host).replace("FTA_CLIENT_FAULT", "FTA_HOST_FAULT")
        assertEquals(host, ClientStartupFault.parse(encoded, host = true))
        assertTrue(ClientStartupFault.locationCandidateAvailable(host, host = true))
        assertTrue(ClientStartupFault.parse(encoded).isEmpty())
        assertTrue(ClientStartupFault.validate(host).isEmpty())
        for ((key, value) in listOf("moduleId" to 2L, "loadedClientModules" to 3L, "moduleRva" to 42L,
            "moduleBase" to 0x800000000000L))
            assertTrue(ClientStartupFault.validate(host + (key to value), host = true).isEmpty())
    }
    @Test fun errorCategoriesAreBoundedUniqueAndSeparateForBothProcesses() {
        val text = "PRIVATE ignored\nFTA_CLIENT_RUNTIME_ERRORS 9\nFTA_HOST_RUNTIME_ERRORS 2\n"
        assertEquals(9L, ClientStartupFault.runtimeErrorMask(text))
        assertEquals(2L, ClientStartupFault.runtimeErrorMask(text, host = true))
        for (value in listOf("64", "-1", "private", "1 2", "1.0"))
            assertNull(ClientStartupFault.runtimeErrorMask("FTA_CLIENT_RUNTIME_ERRORS $value\n"))
        assertNull(ClientStartupFault.runtimeErrorMask(text + "FTA_CLIENT_RUNTIME_ERRORS 1\n"))
    }
}
