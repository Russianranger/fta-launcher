package com.russianranger.fta.client

/** Original Linux launcher DLL selection semantics; this does not launch Wine. */
object ClientWineOverrides {
    const val MAX_CHARACTERS = 16 * 1024
    private fun trimAscii(value: String) = value.trim { it == ' ' || it in '\t'..'\r' }
    private fun isProxy(name: String) = name.equals("d3d9", ignoreCase = true) ||
        name.equals("d3d9.dll", ignoreCase = true)

    /** Keep unrelated module clauses, then prefer the original app-local D3D9 proxy. */
    fun nativeProxy(inherited: String): String {
        require(inherited.length <= MAX_CHARACTERS && '\u0000' !in inherited) { "invalid-wine-overrides" }
        val retained = inherited.split(';').mapNotNull { raw ->
            val clause = trimAscii(raw)
            if (clause.isEmpty()) return@mapNotNull null
            val equals = clause.indexOf('=')
            if (equals < 0) return@mapNotNull clause
            val modules = clause.substring(0, equals).split(',').map(::trimAscii)
                .filter { it.isNotEmpty() && !isProxy(it) }
            if (modules.isEmpty()) null else modules.joinToString(",") + clause.substring(equals)
        }
        return (retained + "d3d9=n,b").joinToString(";")
    }
}
