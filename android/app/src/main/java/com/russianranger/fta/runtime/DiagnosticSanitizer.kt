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

    private data class CannedRule(val pattern: Regex, val summary: String)
    private fun canned(pattern: String, summary: String) = CannedRule(Regex(pattern, RegexOption.IGNORE_CASE), summary)

    // Match original AzerothCore auth/config/updater diagnostics. Dynamic names,
    // paths, queries, hashes and connection strings never become output fields.
    private val originalDiagnostics = listOf(
        canned("\\bUnknown[ \\t]+character[ \\t]+set\\b",
            "MariaDB could not load a required character set."),
        canned("Config::LoadFile:[ \\t]*(?:Failed open|Failure to read line number|Empty file|Duplicate key name)",
            "Authserver configuration could not be loaded or parsed."),
        canned("FATAL ERROR:[ \\t]*Missing property\\b|Core can't start if found incorrect options|Server Config[^\\r\\n]{0,512}is defined but not loaded",
            "Authserver configuration is missing or has an invalid required setting."),
        canned("Config:[ \\t]*Missing property\\b|Config:[ \\t]*Bad value defined for name[^\\r\\n]{0,1024}going to use[^\\r\\n]{0,1024}instead|Server Config[^\\r\\n]{0,512}failed validation check[^\\r\\n]{0,512}Default value[^\\r\\n]{0,512}will be used instead",
            "An authserver setting used its default."),
        canned("Didn't find any executable MySQL binary|MySQL[^\\r\\n]{0,512}(?:binary|executable)[^\\r\\n]{0,80}(?:not executable|cannot be executed)",
            "The updater could not locate or execute the MySQL client."),
        canned("Could not (?:initialize Mysql connection to database|connect to MySQL database)|DatabasePool[^\\r\\n]{0,512}NOT opened|Lost the connection to the MySQL server",
            "Authserver could not open or maintain its database connection."),
        canned("\\bAccess denied for user\\b|\\bAuthentication plugin[^\\r\\n]{0,512}(?:failed|cannot|could not)",
            "Database authentication was rejected."),
        canned("\\bUnknown database\\b|\\bTable[^\\r\\n]{0,512}doesn't exist|Database[^\\r\\n]{0,512}does not exist|Table[^\\r\\n]{0,512}not exist! Try add based table",
            "A required database schema or table is missing."),
        canned("AzerothCore does not support MySQL versions below|Used MySQL library version[^\\r\\n]{0,512}does not match|DatabaseIncompatibleVersion",
            "The database engine or client version is incompatible with this server."),
        canned("Could not prepare statements of the|Could not fetch prepared statement|In mysql_stmt_(?:init|prepare)\\(\\)",
            "Authserver could not initialize its prepared database statements."),
        canned("DBUpdater: The given source directory[^\\r\\n]{0,1024}does not exist",
            "The original updater source directory is unavailable."),
        canned("DBUpdater: Given update include directory[^\\r\\n]{0,1024}does not exist",
            "An original updater include directory is unavailable."),
        canned(">>[ \\t]*(?:In directory[^\\r\\n]{0,1024}not exist '\\*\\.sql' files|Directory[^\\r\\n]{0,1024}(?:not exist|is empty))|Could not populate the[^\\r\\n]{0,512}database",
            "Updater SQL data is missing, empty, or could not be populated."),
        canned("Failed to open the sql update|Opening the sql update failed",
            "The original updater could not read an SQL update file."),
        canned("Applying of file[^\\r\\n]{0,1024}to database[^\\r\\n]{0,512}failed!",
            "The original updater could not import an SQL update."),
        canned("The file[^\\r\\n]{0,1024}was applied to the database, but is missing in|Cleanup is disabled! There were[^\\r\\n]{0,80}dirty files applied",
            "The original updater found applied SQL files missing from its source directory."),
        canned("Reapplying update[^\\r\\n]{0,1024}\\(it changed\\)",
            "The original updater detected a changed SQL update hash."),
        canned("Re-hashing update\\b",
            "The original updater is refreshing an SQL update hash."),
        canned("Could not update the[^\\r\\n]{0,512}database|Updating failed, see the log for details\\.|\\bUpdateException[^\\r\\n]{0,80}update failed\\b",
            "The original database updater failed."),
        canned("Dry run completed with[^\\r\\n]{0,80}failed database update\\(s\\)",
            "Authserver dry-run reported failed database updates."),
        canned("No valid realms specified|the realmlist table of the auth database is empty|no realm address could be resolved",
            "Authserver found no valid realms in the disposable auth database."),
        canned("Could not resolve address[^\\r\\n]{0,1024}for realm\\b",
            "Realm address resolution failed."),
        canned("Could not resolve localAddress[^\\r\\n]{0,1024}for realm\\b",
            "Realm local address resolution failed."),
        canned("Could not resolve localSubnetMask[^\\r\\n]{0,1024}for realm\\b",
            "Realm local subnet mask resolution failed."),
        canned("(?:filesystem error|std::filesystem[^\\r\\n]{0,80}filesystem_error)[^\\r\\n]{0,512}(?:cannot make[^\\r\\n]{0,80}absolute|absolute[^\\r\\n]{0,80}invalid argument)",
            "Native path resolution failed before the updater could proceed."),
        canned("\\bOperation not supported\\b|\\bFunction not implemented\\b",
            "A required native operation is unsupported on this device."),
        canned("\\bOperation not permitted\\b",
            "A required native operation was denied."),
        canned("filesystem error|\\bfilesystem_error\\b",
            "A native filesystem operation failed."),
        canned("boost::process[^\\r\\n]{0,120}(?:process_error|exception)|\\b(?:execve|posix_spawn)[^\\r\\n]{0,120}failed|\\bprocess_error\\b",
            "The updater could not start or wait for its child process."),
        canned("\\bCannot create PID file\\b",
            "Authserver could not create its process marker."),
        // These statements are progress reports, never runtime/world readiness.
        canned("Started auth database connection pool\\.|DatabasePool[^\\r\\n]{0,512}opened successfully\\.",
            "Authserver opened its database connection pool."),
        canned("Connected to MySQL database at",
            "Authserver established a database connection."),
        canned("Updating Auth database\\.\\.\\.",
            "The original auth database updater started."),
        canned(">>[ \\t]*Applying update\\b",
            "The original updater is applying an SQL update."),
        canned("Auth database is up-to-date!",
            "The original updater reported the auth database is up to date."),
        canned("Dry run completed, terminating\\.",
            "Authserver reported dry-run completion.")
    )

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
            originalDiagnostics.forEach { rule ->
                if (rule.pattern.containsMatchIn(line)) {
                    add(summaries, rule.summary)
                    recognized = true
                }
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
