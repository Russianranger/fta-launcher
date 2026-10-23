package com.russianranger.fta.runtime

/**
 * The original world startup sets VERSION_MISMATCH before loading maps and clears it only
 * after successful startup. A qualified missing-map stop therefore leaves realm 1 excluded
 * by authserver's flag|OFFLINE, flag!=3 filter. Prepare only that source-configured realm,
 * require the actual client build, and retain OFFLINE; this never advertises world readiness.
 */
object LocalRealmAuthState {
    const val BEFORE = "SELECT CONCAT(COUNT(*),':',COALESCE(MAX(gamebuild=12340 AND flag IN(0,1,2,3)),0),':',COALESCE(MAX(flag & 1),0)) FROM acore_auth.realmlist WHERE id=1;"
    const val PREPARE = "UPDATE acore_auth.realmlist SET flag=(flag & ~1) | 2 WHERE id=1 AND gamebuild=12340 AND flag IN(0,1,2,3); SELECT COUNT(*) FROM acore_auth.realmlist WHERE id=1 AND gamebuild=12340 AND flag=2;"
    fun prepare(query: (String) -> String): Boolean {
        val before = query(BEFORE).trim()
        require(before in setOf("1:1:0", "1:1:1")) { "Configured local realm build or startup flags differ" }
        check(query(PREPARE).trim() == "1") { "Local realm offline state was not verified" }
        return before == "1:1:1"
    }
}
