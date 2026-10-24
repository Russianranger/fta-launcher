package com.russianranger.fta.runtime

data class GameDataDbcUse(val path: String, val handling: String, val rows: Long, val sha256: String)

/** Narrow, source-proven routes for physical code 25's twelve measured layouts.
 * The canonical DBC schemas remain unchanged. SQL-backed files are preserved
 * inert and explicitly require native database validation before world readiness.
 * See tools/check_dbc_compatibility.py and host-2026-10-24-dbc-loaders.json.
 */
internal object GameDataDbcCompatibility {
    const val UNUSED = "unused_zero_string_block"
    const val PACKED = "packed_unused_trailing_fields"
    const val DATABASE = "original_database_fallback"
    data class Route(val table: String, val rows: Long)
    val databaseRoutes = mapOf(
        "dbc/gtBarberShopCostBase.dbc" to Route("gtbarbershopcostbase_dbc", 100),
        "dbc/gtChanceToMeleeCrit.dbc" to Route("gtchancetomeleecrit_dbc", 1100),
        "dbc/gtChanceToMeleeCritBase.dbc" to Route("gtchancetomeleecritbase_dbc", 11),
        "dbc/gtChanceToSpellCrit.dbc" to Route("gtchancetospellcrit_dbc", 1100),
        "dbc/gtChanceToSpellCritBase.dbc" to Route("gtchancetospellcritbase_dbc", 11),
        "dbc/gtCombatRatings.dbc" to Route("gtcombatratings_dbc", 3200),
        "dbc/gtNPCManaCostScaler.dbc" to Route("gtnpcmanacostscaler_dbc", 100),
        "dbc/gtOCTRegenHP.dbc" to Route("gtoctregenhp_dbc", 1100),
        "dbc/gtRegenHPPerSpt.dbc" to Route("gtregenhpperspt_dbc", 1100),
        "dbc/gtRegenMPPerSpt.dbc" to Route("gtregenmpperspt_dbc", 1100))

    fun handling(path: String, expected: DbcShape?, rows: Long, fields: Long, record: Long, strings: Long): String? = when {
        path == "dbc/CharHairTextures.dbc" && expected == null && rows > 0 && fields == 8L && record == 32L && strings == 0L -> UNUSED
        path == "dbc/PowerDisplay.dbc" && expected == DbcShape(6, 24, 0, -1) && fields == 6L && record == 15L && strings >= 1 -> PACKED
        databaseRoutes[path]?.rows == rows && expected == DbcShape(2, 8, 0, -1) && fields == 1L && record == 4L && strings == 1L -> DATABASE
        else -> null
    }

    fun validate(use: GameDataDbcUse) {
        require(use.sha256.matches(Regex("[a-f0-9]{64}")) && use.rows in 0..GameDataPolicy.MAX_FILE_BYTES / 4)
        require(when (use.handling) {
            UNUSED -> use.path == "dbc/CharHairTextures.dbc" && use.rows > 0
            PACKED -> use.path == "dbc/PowerDisplay.dbc"
            DATABASE -> databaseRoutes[use.path]?.rows == use.rows
            else -> false
        })
    }
}
