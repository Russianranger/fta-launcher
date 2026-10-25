package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientExitEvidenceTest {
    private val attempt = "778b7221-db89-459a-8dc8-8f01417320dc"
    @Test fun exactClientTokenPidAndTimeAreRequired() {
        assertTrue(ClientExitEvidence.matches(attempt, 123, 100, 200, 123, 150, ClientExitEvidence.token(attempt)))
        assertFalse(ClientExitEvidence.matches(attempt, 123, 100, 200, 124, 150, ClientExitEvidence.token(attempt)))
        assertFalse(ClientExitEvidence.matches(attempt, 123, 100, 200, 123, 99, ClientExitEvidence.token(attempt)))
        assertFalse(ClientExitEvidence.matches(attempt, 123, 100, 200, 123, 201, ClientExitEvidence.token(attempt)))
    }
    @Test fun olderRuntimeTokenCannotBeReusedAsClientProof() {
        assertFalse(ClientExitEvidence.matches(attempt, 123, 100, 200, 123, 150, "fta-game-data:$attempt"))
        assertFalse(ClientExitEvidence.matches(attempt, 123, 100, 200, 123, 150, null))
    }
    @Test fun code36MissingOwnerCannotBeRetroactivelyMatched() {
        assertFalse(ClientExitEvidence.matches(attempt, 0, 100, 200, 123, 150, null))
        assertFalse(ClientExitEvidence.matches("invalid", 123, 100, 200, 123, 150, "fta-client:invalid"))
    }
}
