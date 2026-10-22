package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test fun mariaDbErrorsKeepCodesAndSourceLineOnly() {
        val result = DiagnosticSanitizer.summarize("ERROR 1064 (42000) at line 47: SELECT 'my-secret' FROM private_account;\nERROR 1045 (28000): Access denied for user 'root'@'localhost' (using password: YES)")
        assertEquals(listOf("MariaDB error 1064 at line 47.", "MariaDB error 1045.", "Database authentication was rejected."), result)
        assertFalse(result.joinToString().contains("root"))
        assertFalse(result.joinToString().contains("secret"))
    }

    @Test fun loaderErrorsKeepOnlySafeLibraryAndSymbolIdentifiers() {
        val text = "CANNOT LINK EXECUTABLE \"/data/user/0/com.private/app\": library \"libssl.so.3\" not found: needed by /private/other\ncannot locate symbol \"_ZN3foo3barEv\" referenced by \"/private/lib.so\""
        assertEquals(listOf("Android loader is missing library libssl.so.3.",
            "Android loader is missing symbol _ZN3foo3barEv."), DiagnosticSanitizer.summarize(text))
    }

    @Test fun quotedPathsCannotBecomeLibraryOrSymbolIdentifiers() {
        val text = "library \"/data/user/0/secret.so\" not found\ncannot locate symbol \"file:///private/token\" referenced by anything"
        val result = DiagnosticSanitizer.summarize(text)
        assertEquals(listOf("Android loader could not find a required library.",
            "Android loader could not resolve a required symbol."), result)
        assertFalse(result.joinToString().contains("/"))
        assertFalse(result.joinToString().contains("token"))
    }

    @Test fun credentialLookingIdentifiersAreReplacedWithGenericSummaries() {
        val password = "42f5b1e7bcecd27fe91bf819efaa9e325d7c985b7bbd314e9d8036d899eac38c"
        val text = "cannot locate symbol \"$password\"\nlibrary \"password_secret.so\" not found\ncannot locate symbol \"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.token\""
        val result = DiagnosticSanitizer.summarize(text).joinToString()
        assertFalse(result.contains(password))
        assertFalse(result.contains("password_secret"))
        assertFalse(result.contains("eyJ"))
        assertFalse(result.contains("token"))
    }

    @Test fun unknownOutputNeverEchoesSqlUrisTokensOrPassword() {
        val text = "SET PASSWORD = PASSWORD('abc123-super-secret');\nBearer abcd-token-1234\nhttps://host/private?auth=token\n/data/private/database.sql\n123456789abcdef123456789abcdef123456789abcdef123456789abcdef"
        assertEquals(listOf("Other native diagnostic output was omitted."), DiagnosticSanitizer.summarize(text))
    }

    @Test fun genericFailuresContainNoOptionValuesOrFilePaths() {
        val text = "Permission denied: /private/account.sql\nCan't find messagefile '/private/errmsg.sys'\nunknown variable 'password=abc123'\nunknown server option '--socket=/private/mysql.sock'"
        assertEquals(listOf("Native execution or file access was denied.",
            "MariaDB could not find its message file.",
            "A native server option or variable is unsupported."), DiagnosticSanitizer.summarize(text))
    }

    @Test fun multilineInjectionCannotChangeSummaryStructure() {
        val text = "library \"libssl.so\nDROP TABLE account;\" not found\ncannot locate symbol \"foo\r\npassword=secret\""
        val result = DiagnosticSanitizer.summarize(text)
        assertTrue(result.all { !it.contains('\n') && !it.contains('\r') })
        assertFalse(result.joinToString().contains("DROP"))
        assertFalse(result.joinToString().contains("secret"))
    }

    @Test fun generatedSecretsAfterBoundedPrefixAreNeverScannedOrEchoed() {
        val result = DiagnosticSanitizer.summarize("x".repeat(DiagnosticSanitizer.MAX_INPUT_BYTES) + "\nERROR 99999 at line 5: secret")
        assertEquals(listOf("Other native diagnostic output was omitted."), result)
    }

    @Test fun unicodeInputRespectsByteBoundBeforeFollowingError() {
        val result = DiagnosticSanitizer.summarize("中".repeat(22_000) + "\nERROR 1064 at line 8")
        assertEquals(listOf("Other native diagnostic output was omitted."), result)
    }

    @Test fun outputIsDeduplicatedAndCappedAtTwentySummaries() {
        val text = (1..100).joinToString("\n") { "ERROR $it at line $it: hidden SQL" }
        val result = DiagnosticSanitizer.summarize(text)
        assertEquals(20, result.size)
        assertEquals("MariaDB error 1 at line 1.", result.first())
        assertEquals("MariaDB error 20 at line 20.", result.last())
        assertEquals(listOf("MariaDB error 1064."), DiagnosticSanitizer.summarize("ERROR 1064\nERROR 1064"))
    }

    @Test fun sixDigitErrorCodeAndMalformedSqlStateCannotLeakSuffixes() {
        val result = DiagnosticSanitizer.summarize("ERROR 123456 at line 99: secret\nERROR 00001 (SECRET_SQL) at line 42: hidden")
        assertEquals(listOf("MariaDB error 1.", "Other native diagnostic output was omitted."), result)
    }

    @Test fun emptyOutputProducesNoInventedFailure() {
        assertTrue(DiagnosticSanitizer.summarize("").isEmpty())
        assertTrue(DiagnosticSanitizer.summarize(" \r\n\t").isEmpty())
    }

    @Test fun originalConfigurationFailuresProduceOnlyCannedCategories() {
        val original = listOf(
            "> Config::LoadFile: Failed open file '/data/private/password-auth.conf'",
            "> Config::LoadFile: Failure to read line number 73 in file '/private/config'",
            "> Config::LoadFile: Empty file '/private/config'",
            "> Config::LoadFile: Duplicate key name 'LoginDatabaseInfo=secret' in config file '/private/config'"
        )
        original.forEach { line -> assertEquals(listOf("Authserver configuration could not be loaded or parsed."),
            DiagnosticSanitizer.summarize(line + " password=never-export-this SELECT account FROM secret")) }
        assertEquals(listOf("Authserver configuration is missing or has an invalid required setting."),
            DiagnosticSanitizer.summarize("FATAL ERROR: Missing property LoginDatabaseInfo in config file /private/auth.conf, add user=secret"))
    }

    @Test fun originalMysqlExecutableFailureDropsPathAndConfigValue() {
        val text = "Didn't find any executable MySQL binary at '/data/private/mysql-password' or in path, correct the path in the *.conf (\"MySQLExecutable\")."
        assertEquals(listOf("The updater could not locate or execute the MySQL client."), DiagnosticSanitizer.summarize(text))
        assertEquals(listOf("The updater could not locate or execute the MySQL client."),
            DiagnosticSanitizer.summarize("MySQL executable /private/bin/mysql is not executable password=hidden"))
    }

    @Test fun originalDatabaseFailuresOmitHostsUsersAndSql() {
        val failures = mapOf(
            "Could not connect to MySQL database at /private/mysql.sock: user password=hidden" to
                "Authserver could not open or maintain its database connection.",
            "DatabasePool private_auth NOT opened. There were errors opening the MySQL connections." to
                "Authserver could not open or maintain its database connection.",
            "Access denied for user 'secret-user'@'private-host' password=hidden" to
                "Database authentication was rejected.",
            "Unknown database 'secret_schema'" to "A required database schema or table is missing.",
            "Table 'secret_auth.private_accounts' doesn't exist" to "A required database schema or table is missing.",
            "AzerothCore does not support MySQL versions below 8.0" to
                "The database engine or client version is incompatible with this server.",
            "Used MySQL library version (secret) does not match the version id used to compile AzerothCore" to
                "The database engine or client version is incompatible with this server.",
            "Could not prepare statements of the secret_auth database, see log for details." to
                "Authserver could not initialize its prepared database statements.",
            "In mysql_stmt_prepare() id: 19, sql: SELECT password FROM private_account" to
                "Authserver could not initialize its prepared database statements."
        )
        failures.forEach { (line, expected) -> assertEquals(listOf(expected),
            DiagnosticSanitizer.summarize(line + " token=DO_NOT_EXPORT_THIS")) }
    }

    @Test fun originalUpdaterDirectoryAndSqlFailuresAreActionableWithoutPaths() {
        val failures = mapOf(
            "DBUpdater: The given source directory /private/source does not exist, change the path to the directory where your sql directory exists. Shutting down." to
                "The original updater source directory is unavailable.",
            "DBUpdater: Given update include directory \"/private/sql/auth\" does not exist, skipped!" to
                "An original updater include directory is unavailable.",
            ">> Directory \"/private/seed\" not exist" to
                "Updater SQL data is missing, empty, or could not be populated.",
            ">> Directory \"/private/seed\" is empty" to
                "Updater SQL data is missing, empty, or could not be populated.",
            ">> In directory \"/private/seed\" not exist '*.sql' files" to
                "Updater SQL data is missing, empty, or could not be populated.",
            "Failed to open the sql update \"/private/secret.sql\" for reading! Stopping the server to keep the database integrity" to
                "The original updater could not read an SQL update file.",
            "Opening the sql update failed!" to "The original updater could not read an SQL update file.",
            "Applying of file '/private/secret.sql' to database 'private_auth' failed! SELECT secret FROM account" to
                "The original updater could not import an SQL update.",
            "Could not update the secret_auth database, see log for details." to "The original database updater failed."
        )
        failures.forEach { (line, expected) -> assertEquals(listOf(expected), DiagnosticSanitizer.summarize(line)) }
    }

    @Test fun originalUpdateReceiptAndHashMessagesNeverExposeHashesOrNames() {
        val secretHash = "abf32a88c9d01140991f8fba290c030189932b91"
        assertEquals(listOf("The original updater found applied SQL files missing from its source directory."),
            DiagnosticSanitizer.summarize(">> The file '/private/secret.sql' was applied to the database, but is missing in your update directory now!"))
        assertEquals(listOf("The original updater detected a changed SQL update hash."),
            DiagnosticSanitizer.summarize(">> Reapplying update \"secret.sql\" '$secretHash' -> '$secretHash' (it changed)..."))
        assertEquals(listOf("The original updater is refreshing an SQL update hash."),
            DiagnosticSanitizer.summarize(">> Re-hashing update \"secret.sql\" '$secretHash'..."))
    }

    @Test fun failedDryRunAndCompletedDryRunRemainDistinctReports() {
        assertEquals(listOf("Authserver dry-run reported failed database updates."),
            DiagnosticSanitizer.summarize("Dry run completed with 1 failed database update(s), terminating."))
        assertEquals(listOf("Authserver reported dry-run completion."),
            DiagnosticSanitizer.summarize("Dry run completed, terminating."))
        assertEquals(listOf("Authserver found no valid realms in the disposable auth database."),
            DiagnosticSanitizer.summarize("No valid realms specified. Possible reasons: private_auth secret"))
    }

    @Test fun filesystemAndProcessExceptionsDoNotExposeWhatMessages() {
        val input = "terminating due to uncaught exception of type std::__ndk1::__fs::filesystem::filesystem_error: filesystem error: in absolute: Invalid argument [\"/private/password\"]"
        assertEquals(listOf("Native path resolution failed before the updater could proceed.",
            "A native filesystem operation failed."), DiagnosticSanitizer.summarize(input))
        assertEquals(listOf("A required native operation is unsupported on this device.", "A native filesystem operation failed."),
            DiagnosticSanitizer.summarize("filesystem error: directory_iterator: Operation not supported [/private/seed]"))
        assertEquals(listOf("A required native operation was denied.", "A native filesystem operation failed."),
            DiagnosticSanitizer.summarize("filesystem error: status: Operation not permitted [/private/seed]"))
        assertEquals(listOf("Native execution or file access was denied.", "The updater could not start or wait for its child process."),
            DiagnosticSanitizer.summarize("boost::process::process_error: execve failed: Permission denied /private/executable --password=secret"))
    }

    @Test fun cannedProgressDoesNotClaimAWorkingRealmOrWorld() {
        val input = "Started auth database connection pool. private-user=hidden\nUpdating Auth database...\n>> Applying update \"private.sql\" '1234567'...\n>> Auth database is up-to-date! secret"
        assertEquals(listOf("Authserver opened its database connection pool.",
            "The original auth database updater started.", "The original updater is applying an SQL update.",
            "The original updater reported the auth database is up to date."), DiagnosticSanitizer.summarize(input))
        assertFalse(DiagnosticSanitizer.summarize(input).any { it.contains("world ready", ignoreCase = true) })
    }

    @Test fun appendedSqlAndGeneratedPasswordCannotReachExtendedSummaries() {
        val password = "cc5830834a8ed65e578d07f9f353b919b733a4117af2efb509a99625de3f5c31"
        val input = "Could not update the private_auth database, see log for details. SELECT '$password' FROM private_account\nSQL(p): SELECT secret_password FROM account\npassword=$password\nfilesystem error: status: No such file or directory [/private/config]"
        val result = DiagnosticSanitizer.summarize(input).joinToString()
        for (forbidden in listOf(password, "SELECT", "private_auth", "private_account", "secret_password", "/private/config")) {
            assertFalse(result.contains(forbidden))
        }
        assertTrue(result.contains("The original database updater failed."))
        assertTrue(result.contains("A native filesystem operation failed."))
    }

    @Test fun oneSuccessfulConnectionDoesNotClaimAnOpenedPool() {
        val input = "Connected to MySQL database at /private/mysql.sock\nCould not connect to MySQL database at /private/mysql.sock: hidden\nDatabasePool private_auth NOT opened. There were errors opening the MySQL connections."
        val result = DiagnosticSanitizer.summarize(input)
        assertEquals(listOf("Authserver established a database connection.",
            "Authserver could not open or maintain its database connection."), result)
        assertFalse(result.contains("Authserver opened its database connection pool."))
        assertEquals(listOf("Authserver opened its database connection pool."),
            DiagnosticSanitizer.summarize("DatabasePool 'private_auth' opened successfully. 2 total connections running."))
        assertEquals(listOf("A required native operation was denied."),
            DiagnosticSanitizer.summarize("exec operation: Operation not permitted /private/binary"))
    }
}
