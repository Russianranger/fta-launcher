package com.russianranger.fta.client

/** Exact native fixture transcript; no inferred pass from a partial log or exit alone. */
object ClientIsolationProtocol {
    val checks = listOf(
        "read-open", "read-content", "seek", "private-map", "shared-read-map",
        "directory-read", "relative-read", "alias-read", "mode-protected-read", "open-write",
        "open-read-write", "open-truncate", "open-create", "relative-write", "alias-write",
        "proc-fd-write", "truncate", "fd-truncate", "fd-write", "fd-pwrite",
        "chmod", "fd-chmod", "relative-chmod", "chown", "fd-chown",
        "relative-chown", "timestamps", "fd-timestamps", "xattr", "fd-xattr",
        "unlink", "relative-unlink", "rename-out", "hard-link-out", "relative-link-out",
        "mkdir", "rmdir", "symlink-in", "directory-rename", "directory-chmod",
        "ancestor-chmod", "shared-write-map", "child-start", "child-write", "writable-create",
        "writable-write", "writable-truncate", "writable-chmod", "writable-rename", "writable-unlink"
    )
    fun passed(output: String, exit: Int): Boolean = exit == 0 && output.length <= 16384 &&
        output == checks.joinToString("") { "FTA_RO $it 0\n" } + "FTA_RO_COMPLETE 1\n"
    fun completed(output: String): List<String> = output.lineSequence().mapNotNull { line ->
        checks.firstOrNull { line == "FTA_RO $it 0" }
    }.distinct().toList()
}
