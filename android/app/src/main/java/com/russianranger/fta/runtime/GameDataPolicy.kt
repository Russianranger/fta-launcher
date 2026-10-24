package com.russianranger.fta.runtime

import com.russianranger.fta.Inputs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.util.Properties

data class DbcShape(val fields: Int, val recordBytes: Int, val indexOffset: Int, val sentinel: Long)
data class GameDataProblem(val path: String, val reason: String, val sha256: String? = null,
    val observed: Map<String, Long> = emptyMap(), val expected: Map<String, Long> = emptyMap())
data class GameDataPreflight(val files: Int, val bytes: Long, val counts: Map<String, Int>,
    val problems: List<GameDataProblem>, val problemCount: Int,
    val reasonCounts: Map<String, Int> = emptyMap(), val mmapHighBitCapacityFiles: Int = 0,
    val dbcUses: List<GameDataDbcUse> = emptyList()) {
    val passed get() = problemCount == 0
}

/** Exact source-derived headers/inventory only. Native loaders still own full semantic validation. */
class GameDataPolicy(val dbc: Map<String, DbcShape>, val startupTiles: List<String>,
    val mapVersion: Long, val vmapMagic: String, val mmapVersion: Long, val detourVersion: Long) {
    fun inspect(directory: File, files: List<GameDataFile>, active: () -> Unit = {}, cache: GameDataCache? = null): GameDataPreflight {
        val names = files.map { it.path }.toSet()
        val problems = mutableListOf<GameDataProblem>(); var problemCount = 0
        val reasonCounts = sortedMapOf<String, Int>(); var highBitCapacityFiles = 0
        val dbcUses = mutableListOf<GameDataDbcUse>()
        val missingTrees = mutableSetOf<String>()
        fun problem(path: String, reason: String, entry: GameDataFile? = null, header: Map<String, Long> = emptyMap()) {
            problemCount++; reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
            if (problems.size < 32) {
                val expected = if (path.startsWith("dbc/stock_dbc/")) null else dbc[path.substringAfterLast('/')]
                val shape = expected?.let { mapOf("fields" to it.fields.toLong(), "recordBytes" to it.recordBytes.toLong()) +
                    (if (it.sentinel >= 0) mapOf("requiredSentinel" to it.sentinel) else emptyMap()) } ?: emptyMap()
                problems += GameDataProblem(path, reason, entry?.sha256, header, shape)
            }
        }
        dbc.keys.sorted().filter { "dbc/$it" !in names }.forEach { problem("dbc/$it", "missing_required_dbc") }
        for (tile in startupTiles) {
            val map = tile.take(3)
            if ("maps/$tile.map" !in names) problem("maps/$tile.map", "missing_startup_map")
            val tree = "vmaps/$map.vmtree"
            if (tree !in names) { if (missingTrees.add(tree)) problem(tree, "missing_startup_vmap_tree") }
            else {
                val tiled = runCatching { RandomAccessFile(File(directory, tree), "r").use { it.seek(8); it.readUnsignedByte() } }.getOrNull()
                val part = "vmaps/${map}_${tile.takeLast(2)}_${tile.substring(3, 5)}.vmtile"
                if (tiled == 1 && part !in names) problem(part, "missing_startup_vmap_tile")
            }
        }
        val counts = GROUPS.associateWith { group -> files.count { it.path.startsWith("$group/") } } +
            mapOf("stock_dbc" to files.count { it.path.startsWith("dbc/stock_dbc/") && it.path.endsWith(".dbc") },
                "db2_companions" to files.count { isDb2Companion(it.path) },
                "editor_companions" to files.count { isEditorCompanion(it.path) })
        if (files.none { it.path.endsWith(".mmap") }) problem("mmaps", "missing_navmesh_parameters")
        if (files.none { it.path.endsWith(".mmtile") }) problem("mmaps", "missing_navmesh_tiles")
        for (entry in files) {
            active()
            val file = File(directory, entry.path)
            var handling: String? = null
            val failure = inspectFile(file, entry.path, active) { handling = it }
            val header = if (handling != null || entry.path.endsWith(".mmap") || (failure != null && problems.size < 32))
                GameDataHeaders.read(file, entry.path) else emptyMap()
            if (failure == null) handling?.let { dbcUses += GameDataDbcUse(entry.path, it, header.getValue("rows"), entry.sha256) }
            if (entry.path.endsWith(".mmap") && header["maxPolysBits"] == 0x80000000L) highBitCapacityFiles++
            if (failure != null) problem(entry.path, failure, entry, header)
            cache?.release(file, 0, entry.bytes)
        }
        return GameDataPreflight(files.size, files.sumOf { it.bytes }, counts, problems, problemCount, reasonCounts, highBitCapacityFiles, dbcUses)
    }
    internal fun inspectFile(file: File, path: String, active: () -> Unit = {}, compatible: (String) -> Unit = {}): String? = try {
        RandomAccessFile(file, "r").use { input ->
            fun bytes(n: Int) = ByteArray(n).also { input.readFully(it) }
            fun u32() = Integer.toUnsignedLong(java.lang.Integer.reverseBytes(input.readInt()))
            when {
                // The pinned server names .dbc inputs explicitly and has no DB2 loader.
                // Preserve opaque companions without guessing a schema. ZIP integrity and
                // full rehash still apply; these never satisfy required runtime DBCs.
                // The report-identified editor ZIP is also retained opaque. Never open its
                // contents as another archive or use them to satisfy runtime requirements.
                isDb2Companion(path) || isEditorCompanion(path) -> Unit
                path.startsWith("dbc/") -> {
                    if (!bytes(4).contentEquals("WDBC".toByteArray())) return "dbc_magic"
                    val rows = u32(); val fields = u32(); val record = u32(); val strings = u32()
                    val expected = if (path.startsWith("dbc/stock_dbc/")) null else dbc[path.substringAfterLast('/')]
                    val handling = GameDataDbcCompatibility.handling(path, expected, rows, fields, record, strings)
                    if (fields !in 1..1024 || record !in fields..fields * 4 || (strings < 1 && handling != GameDataDbcCompatibility.UNUSED) ||
                        rows > MAX_FILE_BYTES / record || 20 + rows * record + strings != input.length()) return "dbc_layout"
                    // The original loader reads dbc/<name> and recognized locale folders only.
                    // Screenshot-confirmed stock_dbc files are inert backups, never runtime substitutes.
                    if (expected != null) {
                        if ((fields != expected.fields.toLong() || record != expected.recordBytes.toLong()) &&
                            handling !in setOf(GameDataDbcCompatibility.PACKED, GameDataDbcCompatibility.DATABASE)) return "dbc_source_layout"
                        if (expected.sentinel >= 0) {
                            var found = false
                            for (row in 0 until rows) {
                                if (row % 4096 == 0L) active()
                                input.seek(20 + row * record + expected.indexOffset)
                                if (u32() == expected.sentinel) { found = true; break }
                            }
                            if (!found) return "dbc_version_sentinel"
                        }
                    }
                    handling?.let(compatible)
                }
                path.endsWith(".map") -> {
                    if (!bytes(4).contentEquals("MAPS".toByteArray()) || u32() != mapVersion) return "map_version"
                    u32() // Original loader does not require a particular buildMagic value.
                    repeat(4) {
                        val offset = u32(); val size = u32()
                        if ((offset == 0L) != (size == 0L) || (offset != 0L && (offset < 44 || offset + size > input.length()))) return "map_section_bounds"
                    }
                }
                path.startsWith("vmaps/") -> {
                    if (!bytes(8).contentEquals(vmapMagic.toByteArray())) return "vmap_version"
                    if (path.endsWith(".vmtree") && input.readUnsignedByte() !in 0..1) return "vmap_tiled_flag"
                }
                path.endsWith(".mmap") -> {
                    if (input.length() != 28L) return "mmap_parameter_layout"
                    val values = ByteBuffer.wrap(bytes(28)).order(LITTLE_ENDIAN)
                    repeat(3) { if (!values.float.isFinite()) return "mmap_parameter_values" }
                    repeat(2) { val size = values.float; if (!size.isFinite() || size <= 0) return "mmap_parameter_values" }
                    val maxTiles = values.int; val maxPolys = values.int
                    // Pinned MapBuilder uses 1 << DT_POLY_BITS (31). Its DT_POLYREF64
                    // loader accepts the resulting 0x80000000 representation unchanged.
                    if (maxTiles <= 0 || (maxPolys <= 0 && maxPolys != Int.MIN_VALUE)) return "mmap_parameter_values"
                }
                path.endsWith(".mmtile") -> {
                    if (u32() != 0x4d4d4150L || u32() != detourVersion || u32() != mmapVersion) return "mmap_tile_version"
                    val size = u32()
                    if (size < 8 || size + 56 != input.length()) return "mmap_tile_layout"
                    input.seek(56)
                    if (u32() != 0x444e4156L || u32() != detourVersion) return "detour_tile_version"
                }
                else -> return "unsupported_asset"
            }
        }
        null
    } catch (_: java.io.IOException) { "truncated_asset" }

    companion object {
        const val ASSET = "metadata/game-data-policy.properties"
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024
        const val MAX_FILES = 100_000
        val GROUPS = listOf("dbc", "maps", "vmaps", "mmaps")
        private const val EDITOR_COMPANION = "dbc/WDBX - DBC EDITOR.zip"
        fun isDb2Companion(value: String) = value.startsWith("dbc/") && value.endsWith(".db2")
        fun isEditorCompanion(value: String) = value == EDITOR_COMPANION
        fun path(value: String, directory: Boolean = false) {
            val p = if (directory) value.removeSuffix("/") else value
            require(p.length in 1..240 && p.split('/').all { part -> part !in setOf(".", "..") &&
                part.matches(Regex("[A-Za-z0-9_+ .-]{1,120}")) && !part.endsWith('.') && !part.endsWith(' ') })
        }
        fun assetPath(value: String) {
            path(value)
            val p = value.split('/')
            require(p.first() in GROUPS)
            require(when (p.first()) {
                "dbc" -> isEditorCompanion(value) || ((p.size == 2 || (p.size == 3 && p[1] in setOf("stock_dbc", "enUS", "enGB", "deDE", "esES", "esMX", "frFR", "itIT", "koKR", "ruRU", "zhCN", "zhTW"))) &&
                    (p.last().endsWith(".dbc") || p.last().endsWith(".db2")))
                "maps" -> p.size == 2 && p[1].matches(Regex("[0-9]{7,8}\\.map"))
                "mmaps" -> p.size == 2 && p[1].matches(Regex("([0-9]{3,4}\\.mmap|[0-9]{7,8}\\.mmtile)"))
                else -> p.size == 2 && (p[1] == "GameObjectModels.dtree" || p[1].endsWith(".vmo") ||
                    p[1].matches(Regex("([0-9]{3,4}\\.vmtree|[0-9]{3,4}_[0-9]{2}_[0-9]{2}\\.vmtile)")))
            })
        }
        fun parse(text: String): GameDataPolicy {
            require(text.length in 1..32_768)
            val values = Properties().apply { text.reader().use { load(it) } }
            require(values.getProperty("formatVersion") == "1" && values.getProperty("sourceArchiveSha256") == Inputs.source.sha256)
            val dbc = values.stringPropertyNames().filter { it.startsWith("dbc.") }.associate { key ->
                val name = key.removePrefix("dbc."); assetPath("dbc/$name")
                require(name.endsWith(".dbc") && '/' !in name)
                val parts = values.getProperty(key).split(',').map { it.toLong() }; require(parts.size == 4)
                require(parts[0] in 1..1024 && parts[1] in parts[0]..parts[0] * 4 && parts[2] in -1..parts[1] - 4 && parts[3] in -1..0xffffffffL)
                require(parts[3] == -1L || parts[2] >= 0)
                name to DbcShape(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3])
            }
            require(dbc.size == 113 && values.getProperty("dbcCount") == "113" && dbc.values.count { it.sentinel >= 0 } == 7)
            val tiles = values.getProperty("startupTiles").split(',')
            require(tiles.size == 8 && tiles.toSet().size == 8 && tiles.all { it.matches(Regex("[0-9]{7}")) })
            require(values.getProperty("mapVersion") == "9" && values.getProperty("vmapMagic") == "VMAP_4.8" &&
                values.getProperty("mmapVersion") == "20" && values.getProperty("detourVersion") == "7")
            return GameDataPolicy(dbc, tiles, 9, "VMAP_4.8", 20, 7)
        }
    }
}
