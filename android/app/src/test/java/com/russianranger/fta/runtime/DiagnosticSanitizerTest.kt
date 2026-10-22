package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test fun mariaDbErrorsKeepCodesAndSourceLineOnly() {
        val result = DiagnosticSanitizer.summarize("ERROR 1064 (42000) at line 47: SELECT 'my-secret' FROM private_account;\nERROR 1045 (28000): Access denied for user 'root'@'localhost' (using password: YES)")
        assertEquals(listOf("MariaDB error 1064 at line 47.", "MariaDB error 1045."), result)
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
}
