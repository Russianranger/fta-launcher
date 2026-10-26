package com.russianranger.fta.client

import java.util.Locale

/** Provider-independent inventory bounds and fixed diagnostic role names. No source writes. */
class ClientInventory {
    var files = 0; private set
    var directories = 0; private set
    var knownBytes = 0L; private set
    var unknownSizes = 0; private set
    var mpqFiles = 0; private set
    var executableCandidates = 0; private set
    private var pathCharacters = 0L
    private val paths = HashSet<String>()
    val presentRoles = mutableSetOf<String>()

    fun add(path: String, directory: Boolean, size: Long?): String? {
        require(path.length in 1..2048 && path.count { it == '/' } < 32)
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c == '\\' || c.code < 32 } })
        val lower = path.lowercase(Locale.ROOT)
        require(paths.size < 100000 && paths.add(lower))
        pathCharacters += path.length; require(pathCharacters <= 4 * 1024 * 1024)
        if (directory) { directories++; return null }
        files++
        if (size == null || size < 0) unknownSizes++ else {
            require(size <= 1024L * 1024 * 1024 * 1024 && knownBytes <= Long.MAX_VALUE - size)
            knownBytes += size
        }
        if (lower.startsWith("data/") && lower.endsWith(".mpq")) mpqFiles++
        val role = roles[lower] ?: if (lower.endsWith(".exe")) {
            executableCandidates++; require(executableCandidates <= MAX_AUXILIARY_EXECUTABLES)
            "auxiliary-executable-$executableCandidates"
        } else null
        if (role != null) presentRoles += role
        return role
    }

    companion object {
        const val MAX_AUXILIARY_EXECUTABLES = 32
        val roles = mapOf("wow.exe" to "wow", "d3d9.dll" to "proxy", "warcraftxl.dll" to "warcraftxl",
            "utils/warcraftxlhost.exe" to "asset-host",
            "utils/warcraftxl/backends/dxvk/d3d9.dll" to "dxvk", "warcraftxl.cfg" to "warcraftxl-config",
            "wtf/config.wtf" to "wow-config")
        fun isBinary(role: String) = role in setOf("wow", "proxy", "warcraftxl", "dxvk", "asset-host") || role.startsWith("auxiliary-executable-")
        val reportRoleLimit get() = roles.values.toSet().size + MAX_AUXILIARY_EXECUTABLES
        val reportComponentLimit get() = roles.values.count(::isBinary) + MAX_AUXILIARY_EXECUTABLES
        fun matchesWow(info: PeMetadata.Info?) = info?.architecture == "x86" && !info.dll && info.fileVersion == "3.3.5.12340"
    }
}
