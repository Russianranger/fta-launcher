package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.NoSuchFileException

class ClientFailureEvidenceTest {
    @Test fun wrappedWalkAndReadFailuresRetainOnlyFixedCategory() {
        assertEquals("access-denied", ClientFailureEvidence.category(UncheckedIOException(AccessDeniedException("/private/path"))))
        assertEquals("missing", ClientFailureEvidence.category(DirectoryIteratorException(NoSuchFileException("/private/path"))))
        assertEquals("io", ClientFailureEvidence.category(ClientFailureEvidence.AtPhase("report-write", IOException("secret"))))
        assertEquals("invalid-argument", ClientFailureEvidence.category(IllegalArgumentException("private")))
        assertEquals("validation", ClientFailureEvidence.category(RuntimeInstallFailure("runtime-changed")))
        assertEquals("other", ClientFailureEvidence.category(Exception("secret")))
    }
}
