package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class RealmEligibilityEvidenceTest {
    private fun output(present: Int, eligible: Int, numeric: Int) = """
        FTA_REALM_ROWS_PRESENT=$present
        FTA_REALM_ELIGIBLE_AFTER_OFFLINE_ROWS_PRESENT=$eligible
        FTA_REALM_ALL_THREE_IPV4=$numeric
    """.trimIndent()

    @Test fun eligibleNumericSeedExportsOnlyThreeBooleans() {
        val evidence = RealmEligibilityEvidence.parse(output(1, 1, 1))
        assertEquals(mapOf("realmRowsPresent" to true, "eligibleAfterOfflineRowsPresent" to true,
            "all3addressesNumericIPv4" to true), evidence)
    }

    @Test fun emptyAndFilteredRealmsRemainDistinguishable() {
        assertEquals(false, RealmEligibilityEvidence.parse(output(0, 0, 0))["realmRowsPresent"])
        val filtered = RealmEligibilityEvidence.parse(output(1, 0, 0))
        assertEquals(true, filtered["realmRowsPresent"])
        assertEquals(false, filtered["eligibleAfterOfflineRowsPresent"])
        assertEquals(false, filtered["all3addressesNumericIPv4"])
    }

    @Test fun reportNeverCoercesStringsNumbersOrPartialEvidence() {
        val valid = RealmEligibilityEvidence.parse(output(1, 1, 1))
        assertTrue(RealmEligibilityEvidence.forward(valid + ("realmRowsPresent" to "true")).isEmpty())
        assertTrue(RealmEligibilityEvidence.forward(valid + ("realmRowsPresent" to 1)).isEmpty())
        assertTrue(RealmEligibilityEvidence.forward(mapOf("realmRowsPresent" to true)).isEmpty())
    }

    @Test fun privateRowsAndUnknownFieldsCannotEnterTheReport() {
        val valid = RealmEligibilityEvidence.parse(output(1, 1, 0))
        val forwarded = RealmEligibilityEvidence.forward(valid + mapOf("address" to "private.example", "name" to "private realm", "id" to 42))
        assertEquals(valid, forwarded)
        assertEquals(3, forwarded.size)
        assertFalse(forwarded.toString().contains("private"))
        assertThrows(IllegalArgumentException::class.java) { RealmEligibilityEvidence.parse(output(1, 1, 1) + "\naddress=private.example") }
    }

    @Test fun duplicateOrContradictoryResultsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { RealmEligibilityEvidence.parse(output(0, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { RealmEligibilityEvidence.parse(output(1, 0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { RealmEligibilityEvidence.parse(output(1, 1, 1) + "\nFTA_REALM_ROWS_PRESENT=1") }
        assertTrue(RealmEligibilityEvidence.forward(mapOf("realmRowsPresent" to false,
            "eligibleAfterOfflineRowsPresent" to true, "all3addressesNumericIPv4" to true)).isEmpty())
    }

    @Test fun sqlUsesReadOnlyBooleansAndOriginalOfflineEligibility() {
        val sql = RealmEligibilityEvidence.SQL.uppercase()
        assertTrue(sql.contains("EXISTS(SELECT 1"))
        assertTrue(sql.contains("(FLAG | 2) <> 3"))
        assertTrue(sql.contains("IS_IPV4(ADDRESS)"))
        assertTrue(sql.contains("IS_IPV4(LOCALADDRESS)"))
        assertTrue(sql.contains("IS_IPV4(LOCALSUBNETMASK)"))
        for (mutation in listOf("UPDATE ", "DELETE ", "INSERT ", "ALTER ")) assertFalse(sql.contains(mutation))
        assertFalse(sql.contains("COUNT("))
    }
}
