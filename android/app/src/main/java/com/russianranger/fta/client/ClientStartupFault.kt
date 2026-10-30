package com.russianranger.fta.client

/** Numeric Wine trace observations. A matching last exception is not proof of the fatal cause. */
object ClientStartupFault {
    val bounds = linkedMapOf("pidCorrelated" to 1L, "traceLinesDropped" to 1_000_000L, "traceStateOverflow" to 1L,
        "accessViolationCount" to 1_000_000L, "exceptionCode" to 0xffffffffL, "exceptionFlags" to 0xffffffffL,
        "exceptionAddress" to 0x7fffffffffffL, "accessType" to 9L, "accessAddress" to 0x7fffffffffffL, "accessDetailsKnown" to 1L,
        "moduleId" to 7L, "moduleBase" to 0xffffffffL, "moduleSize" to 268435456L, "moduleRva" to 268435455L,
        "loadedClientModules" to 7L, "exitCode" to 0xffffffffL, "exitObserved" to 1L, "exitElapsedMs" to 145000L,
        "exceptionMatchesExit" to 1L)
    val modules = listOf("unknown", "Wow.exe", "WarcraftXL.dll", "root-d3d9.dll", "ntdll-x86", "kernelbase-x86", "kernel32-x86", "wined3d-x86")

    val hostModules = listOf("unknown", "WarcraftXLHost.exe", "unknown", "unknown", "ntdll-x64", "kernelbase-x64", "kernel32-x64", "ucrtbase-x64")
    fun bounds(host: Boolean) = if (host) bounds + ("moduleBase" to 0x7fffffffffffL) else bounds

    fun runtimeErrorMask(text: String, host: Boolean = false): Long? {
        runtimeRequire(text.length <= 65536, "probe-failed")
        val prefix = if (host) "FTA_HOST_RUNTIME_ERRORS " else "FTA_CLIENT_RUNTIME_ERRORS "
        val lines = text.lineSequence().filter { it.startsWith(prefix) }.toList()
        return lines.singleOrNull()?.removePrefix(prefix)?.takeIf { it.matches(Regex("[0-9]{1,2}")) }
            ?.toLongOrNull()?.takeIf { it in 0..63 }
    }

    fun parse(text: String, host: Boolean = false): Map<String, Long> {
        runtimeRequire(text.length <= 65536, "probe-failed")
        val result = linkedMapOf<String, Long>()
        val prefix = if (host) "FTA_HOST_FAULT " else "FTA_CLIENT_FAULT "
        for (line in text.lineSequence().filter { it.startsWith(prefix) }) {
            val f = line.split(' ')
            if (f.size != 3 || f[1] !in bounds || f[1] in result || !f[2].matches(Regex("[0-9]{1,15}"))) return emptyMap()
            result[f[1]] = f[2].toLongOrNull() ?: return emptyMap()
        }
        return validate(result, host)
    }

    fun validate(raw: Map<String, Any?>, host: Boolean = false): Map<String, Long> {
        val bounds = bounds(host)
        if (raw.keys != bounds.keys) return emptyMap()
        val n = linkedMapOf<String, Long>()
        for ((key, limit) in bounds) {
            val value = raw[key]
            if (value !is Int && value !is Long) return emptyMap()
            val number = (value as Number).toLong()
            if (number !in 0..limit) return emptyMap()
            n[key] = number
        }
        fun v(key: String) = n.getValue(key)
        if (v("accessViolationCount") == 0L) {
            if (listOf("exceptionCode", "exceptionFlags", "exceptionAddress", "moduleId", "accessDetailsKnown").any { v(it) != 0L }) return emptyMap()
        } else if (v("exceptionCode") != 0xc0000005L || v("pidCorrelated") != 1L) return emptyMap()
        if (v("accessDetailsKnown") == 0L) {
            if (v("accessType") != 9L || v("accessAddress") != 0L) return emptyMap()
        } else if (v("accessType") !in setOf(0L, 1L, 8L)) return emptyMap()
        if (v("moduleId") == 0L) {
            if (listOf("moduleBase", "moduleSize", "moduleRva").any { v(it) != 0L }) return emptyMap()
        } else {
            if (v("moduleSize") < 4096 || v("moduleBase") + v("moduleSize") > (if (host) 0x800000000000L else 0x100000000L) ||
                v("exceptionAddress") !in v("moduleBase") until v("moduleBase") + v("moduleSize") ||
                v("moduleRva") != v("exceptionAddress") - v("moduleBase")) return emptyMap()
            if (v("moduleId") <= 3 && (v("loadedClientModules") and (1L shl (v("moduleId").toInt() - 1))) == 0L) return emptyMap()
        }
        if (host && (v("moduleId") in 2L..3L || v("loadedClientModules") > 1L)) return emptyMap()
        if (v("exitObserved") == 0L && (v("exitCode") != 0L || v("exitElapsedMs") != 0L)) return emptyMap()
        if (v("pidCorrelated") == 0L && (v("exitObserved") != 0L || v("loadedClientModules") != 0L)) return emptyMap()
        val matches = v("pidCorrelated") == 1L && v("accessViolationCount") > 0 && v("exitObserved") == 1L && v("exitCode") == v("exceptionCode")
        if (v("exceptionMatchesExit") != if (matches) 1L else 0L) return emptyMap()
        return n
    }

    fun locationCandidateAvailable(values: Map<String, Long>, host: Boolean = false) = validate(values, host).let {
        it["exceptionMatchesExit"] == 1L && it["moduleId"] != 0L && it["traceLinesDropped"] == 0L && it["traceStateOverflow"] == 0L
    }
}
