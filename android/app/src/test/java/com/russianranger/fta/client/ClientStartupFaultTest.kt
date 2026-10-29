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
}
