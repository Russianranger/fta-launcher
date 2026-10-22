package com.russianranger.fta.runtime

/**
 * Native tools can echo SQL, paths and credentials. This allowlist returns summaries,
 * never raw lines. It is for presentation only; retain native exit codes separately.
 */
object DiagnosticSanitizer {
    const val MAX_INPUT_BYTES = 64 * 1024
    const val MAX_SUMMARIES = 20
    private const val OMITTED = "Other native diagnostic output was omitted."
    private val databaseError = Regex(
        "(?<![A-Za-z0-9_])ERROR[ \\t]+([0-9]{1,5})(?![0-9])" +
            "(?:[ \\t]*\\([A-Z0-9]{5}\\))?(?:[ \\t]+at[ \\t]+line[ \\t]+([0-9]{1,10})(?![0-9]))?",
        RegexOption.IGNORE_CASE
    )
    private val missingLibrary = Regex(
        "\\blibrary[ \\t]+([\"'])([A-Za-z0-9_.+-]{1,120})\\1[ \\t]+not[ \\t]+found\\b",
        RegexOption.IGNORE_CASE
    )
    private val missingSymbol = Regex(
        "\\b(?:cannot[ \\t]+locate[ \\t]+symbol|undefined[ \\t]+symbol:?)[ \\t]+" +
            "([\"'])([A-Za-z0-9_\u0024@.]{1,160})\\1",
        RegexOption.IGNORE_CASE
    )
    private val denied = Regex("\\bpermission[ \\t]+denied\\b", RegexOption.IGNORE_CASE)
    private val messageFile = Regex("\\b(?:can't|cannot)[ \\t]+find[ \\t]+messagefile\\b", RegexOption.IGNORE_CASE)
    private val unknownOption = Regex("\\bunknown[ \\t]+(?:server[ \\t]+)?(?:option|variable)\\b", RegexOption.IGNORE_CASE)
    private val libraryFailure = Regex("\\blibrary[ \\t]+[^\\r\\n]{0,1024}?[ \\t]+not[ \\t]+found\\b", RegexOption.IGNORE_CASE)
    private val symbolFailure = Regex("\\b(?:cannot[ \\t]+locate[ \\t]+symbol|undefined[ \\t]+symbol)\\b", RegexOption.IGNORE_CASE)
    private val credentialMarker = Regex("password|passwd|secret|token|credential|authorization|bearer", RegexOption.IGNORE_CASE)
    private val longHex = Regex("[A-Fa-f0-9]{24,}")
    private val accessKey = Regex("(?:AKIA|ASIA)[A-Z0-9]{16}")

    fun sanitize(output: String): List<String> = summarize(output)

    fun summarize(output: String): List<String> {
        val bounded = utf8Prefix(output)
        if (bounded.isBlank()) return emptyList()
        val summaries = linkedSetOf<String>()
        var omitted = bounded.length < output.length
        for (line in bounded.lineSequence()) {
            if (summaries.size >= MAX_SUMMARIES) break
            if (line.isBlank()) continue
            var recognized = false
            databaseError.findAll(line).forEach { match ->
                val code = match.groupValues[1].toInt()
                val sourceLine = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()
                add(summaries, if (sourceLine == null) "MariaDB error $code." else "MariaDB error $code at line $sourceLine.")
                recognized = true
            }
            missingLibrary.findAll(line).forEach { match ->
                val name = match.groupValues[2]
                add(summaries, if (safeIdentifier(name)) "Android loader is missing library $name."
                    else "Android loader could not find a required library.")
                recognized = true
            }
            missingSymbol.findAll(line).forEach { match ->
                val name = match.groupValues[2]
                add(summaries, if (safeIdentifier(name)) "Android loader is missing symbol $name."
                    else "Android loader could not resolve a required symbol.")
                recognized = true
            }
            if (denied.containsMatchIn(line)) {
                add(summaries, "Native execution or file access was denied.")
                recognized = true
            }
            if (messageFile.containsMatchIn(line)) {
                add(summaries, "MariaDB could not find its message file.")
                recognized = true
            }
            if (unknownOption.containsMatchIn(line)) {
                add(summaries, "A native server option or variable is unsupported.")
                recognized = true
            }
            if (!recognized && libraryFailure.containsMatchIn(line)) {
                add(summaries, "Android loader could not find a required library.")
                recognized = true
            }
            if (!recognized && symbolFailure.containsMatchIn(line)) {
                add(summaries, "Android loader could not resolve a required symbol.")
                recognized = true
            }
            if (!recognized) omitted = true
        }
        if (omitted) add(summaries, OMITTED)
        if (summaries.isEmpty()) summaries.add(OMITTED)
        return summaries.toList()
    }

    private fun add(summaries: MutableSet<String>, value: String) {
        if (summaries.size < MAX_SUMMARIES) summaries.add(value)
    }

    private fun safeIdentifier(value: String): Boolean =
        !credentialMarker.containsMatchIn(value) && !longHex.containsMatchIn(value) &&
            !accessKey.containsMatchIn(value) && !value.startsWith("eyJ")

    /** Count a bounded prefix without first encoding an arbitrarily large untrusted string. */
    private fun utf8Prefix(value: String): String {
        var cursor = 0
        var bytes = 0
        while (cursor < value.length && bytes < MAX_INPUT_BYTES) {
            val char = value[cursor]
            val pair = char.isHighSurrogate() && value.getOrNull(cursor + 1)?.isLowSurrogate() == true
            val width = when {
                pair -> 4
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                char.isSurrogate() -> 1 // UTF-8's replacement for an unpaired UTF-16 surrogate.
                else -> 3
            }
            if (bytes + width > MAX_INPUT_BYTES) break
            bytes += width
            cursor += if (pair) 2 else 1
        }
        return value.substring(0, cursor)
    }
}
