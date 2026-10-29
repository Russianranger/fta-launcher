package com.russianranger.fta.client

/** Fixed categories only. Exception messages, filenames and stack traces stay private. */
object ClientFailureEvidence {
    val clientPhases = setOf("prefix-preparation", "client-preflight", "client-verify", "client-identity", "view-build", "client-session", "client-postconditions")
    val phases = setOf("preparing", "archive-verify", "probe-ownership", "probe-wine", "probe-postconditions",
        "runtime-lease-acquire", "runtime-pre-verify", "runtime-pre-verified", "verified-action", "runtime-post-verify",
        "runtime-post-verified", "runtime-lease-release", "runtime-verified", "retained-receipt", "retained-files",
        "retained-complete", "runtime-bind-cleanup", "result-commit", "report-write", "notification-update") + clientPhases
    val categories = setOf("missing", "access-denied", "filesystem-loop", "filesystem", "io", "closed-channel",
        "invalid-argument", "invalid-state", "json", "errno", "validation", "other")
    class AtPhase(val phase: String, cause: Exception) : Exception(cause)
    fun category(error: Throwable): String {
        var current = error
        repeat(8) {
            val next = current.cause
            if ((current is AtPhase || current is java.io.UncheckedIOException || current is java.nio.file.DirectoryIteratorException) && next != null)
                current = next
        }
        return when (current) {
            is java.nio.file.NoSuchFileException, is java.io.FileNotFoundException -> "missing"
            is java.nio.file.AccessDeniedException, is SecurityException -> "access-denied"
            is java.nio.file.FileSystemLoopException -> "filesystem-loop"
            is java.nio.file.ClosedFileSystemException, is java.nio.channels.ClosedChannelException -> "closed-channel"
            is java.nio.file.FileSystemException -> "filesystem"
            is java.io.IOException -> "io"
            is RuntimeInstallFailure, is ClientImportFailure -> "validation"
            is IllegalArgumentException -> "invalid-argument"
            is IllegalStateException -> "invalid-state"
            else -> when (current.javaClass.name) { "org.json.JSONException" -> "json"; "android.system.ErrnoException" -> "errno"; else -> "other" }
        }
    }
}
