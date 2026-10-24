package com.russianranger.fta.runtime

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameDataStoreTest {
    private val root = Files.createTempDirectory("fta-game-assets-test").toFile()
    private val text = listOf(File("../../manifests/game-data-policy.properties"), File("manifests/game-data-policy.properties"))
        .first { it.isFile }.readText()
    private val policy = GameDataPolicy.parse(text)
    @After fun cleanup() { root.deleteRecursively() }
    private fun store() = GameDataStore(File(root, "store"), text)
    private fun reportedDbcLayouts(): MutableMap<String, ByteArray> = fixture().apply {
        val gt = mapOf("gtBarberShopCostBase.dbc" to 100, "gtChanceToMeleeCrit.dbc" to 1100,
            "gtChanceToMeleeCritBase.dbc" to 11, "gtChanceToSpellCrit.dbc" to 1100,
            "gtChanceToSpellCritBase.dbc" to 11, "gtCombatRatings.dbc" to 3200,
            "gtNPCManaCostScaler.dbc" to 100, "gtOCTRegenHP.dbc" to 1100,
            "gtRegenHPPerSpt.dbc" to 1100, "gtRegenMPPerSpt.dbc" to 1100)
        for ((name, rows) in gt) put("dbc/$name", ByteBuffer.allocate(20 + rows * 4 + 1).order(LITTLE_ENDIAN)
            .put("WDBC".toByteArray()).putInt(rows).putInt(1).putInt(4).putInt(1).array())
        put("dbc/CharHairTextures.dbc", ByteBuffer.allocate(5812).order(LITTLE_ENDIAN)
            .put("WDBC".toByteArray()).putInt(181).putInt(8).putInt(32).putInt(0).array())
        put("dbc/PowerDisplay.dbc", ByteBuffer.allocate(242).order(LITTLE_ENDIAN)
            .put("WDBC".toByteArray()).putInt(7).putInt(6).putInt(15).putInt(117).apply {
                repeat(7) { row -> putInt(20 + row * 15, row + 1); putInt(24 + row * 15, row + 100) }
            }.array())
    }
    @Test fun reportedDbcLayoutsImportPreserveBytesAndReverifyWithPendingDatabaseValidation() {
        val files = reportedDbcLayouts()
        val receipt = store().import(ByteArrayInputStream(zip(files)))
        assertEquals(receipt, store().verify())
        for ((path, bytes) in files) assertArrayEquals(bytes, File(root, "store/${receipt.generation}/$path").readBytes())
        val report = store().export()
        assertEquals(true, report["preflightPassed"])
        assertEquals(true, report["databaseFallbackRequired"])
        assertEquals(false, report["databaseFallbackValidated"])
        assertEquals(false, report["nativeSemanticValidationPassed"]); assertEquals(false, report["worldReady"])
        assertEquals(12, (report["dbcFileCompatibility"] as List<*>).size)
    }
    @Test fun dbcCompatibilityDoesNotSupplyMissingRequiredFilesOrBypassOtherFailures() {
        val before = store().import(ByteArrayInputStream(zip()))
        for (missing in listOf("dbc/gtCombatRatings.dbc", "dbc/PowerDisplay.dbc", "dbc/Spell.dbc")) {
            val files = reportedDbcLayouts().apply { remove(missing) }
            val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
            assertTrue(failure.preflight.problems.any { it.path == missing && it.reason == "missing_required_dbc" })
            assertEquals(before, store().active())
        }
        val files = reportedDbcLayouts(); put32(files.getValue("dbc/Spell.dbc"), 20, 0)
        assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        assertEquals(before, store().verify())
    }
    @Test fun onlyProvenRootDbcNamesWidthsAndGtRowCoverageAreAccepted() {
        val file = File(root, "probe")
        for (path in listOf("dbc/Other.dbc", "dbc/enUS/CharHairTextures.dbc", "dbc/stock_dbc/CharHairTextures.dbc")) {
            file.writeBytes(reportedDbcLayouts().getValue("dbc/CharHairTextures.dbc"))
            assertEquals("dbc_layout", policy.inspectFile(file, path))
        }
        for ((path, rows, fields, width, strings) in listOf(
            listOf("dbc/gtCombatRatings.dbc", 3199, 1, 4, 1),
            listOf("dbc/gtCombatRatings.dbc", 3200, 1, 4, 0),
            listOf("dbc/gtCombatRatings.dbc", 3200, 1, 3, 1),
            listOf("dbc/PowerDisplay.dbc", 7, 6, 16, 117),
            listOf("dbc/PowerDisplay.dbc", 7, 5, 15, 117),
            listOf("dbc/enUS/gtCombatRatings.dbc", 3200, 1, 4, 1))) {
            val n = rows as Int; val f = fields as Int; val w = width as Int; val s = strings as Int
            file.writeBytes(ByteBuffer.allocate(20 + n * w + s).order(LITTLE_ENDIAN)
                .put("WDBC".toByteArray()).putInt(n).putInt(f).putInt(w).putInt(s).array())
            assertNotNull(path as String, policy.inspectFile(file, path))
        }
    }
    @Test fun compatibilityStillChecksExactBodyLengthAndRehashesUnusedBytes() {
        val files = reportedDbcLayouts()
        val before = store().import(ByteArrayInputStream(zip(files)))
        for (path in listOf("dbc/CharHairTextures.dbc", "dbc/PowerDisplay.dbc", "dbc/gtCombatRatings.dbc")) {
            val truncated = files.toMutableMap().apply { put(path, getValue(path).dropLast(1).toByteArray()) }
            assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(truncated))) }
            assertEquals(before, store().active())
        }
        val file = File(root, "store/${before.generation}/dbc/gtCombatRatings.dbc")
        val changed = file.readBytes(); changed[20] = 1; file.writeBytes(changed)
        assertThrows(IllegalArgumentException::class.java) { store().verify() }
        assertEquals(false, store().export()["preflightPassed"])
        assertEquals(before, store().active())
    }
    @Test fun compatibilityCannotOverrideChangedSourceSchemasOrSentinels() {
        val file = File(root, "probe")
        for (path in listOf("dbc/gtCombatRatings.dbc", "dbc/PowerDisplay.dbc")) {
            file.writeBytes(reportedDbcLayouts().getValue(path))
            val changed = GameDataPolicy(policy.dbc + (path.substringAfter('/') to DbcShape(2, 8, 0, 123)),
                policy.startupTiles, policy.mapVersion, policy.vmapMagic, policy.mmapVersion, policy.detourVersion)
            assertEquals("dbc_source_layout", changed.inspectFile(file, path))
        }
    }
    @Test fun legacyReportsDoNotInventDatabaseFallbackQualification() {
        store().import(ByteArrayInputStream(zip()))
        val file = File(root, "store/last-report.properties")
        file.writeText(file.readText().replace("formatVersion=3", "formatVersion=2"))
        val report = store().export()
        assertEquals(true, report["preflightPassed"]); assertEquals(false, report["dbcCompatibilityAvailable"])
        assertFalse(report.containsKey("databaseFallbackRequired")); assertFalse(report.containsKey("databaseFallbackValidated"))
    }
    @Test fun malformedCompatibilityEvidenceRejectsWholeReportAndCannotClaimDatabaseValidation() {
        store().import(ByteArrayInputStream(zip(reportedDbcLayouts())))
        val file = File(root, "store/last-report.properties"); val original = file.readText()
        file.appendText("databaseFallbackValidated=true\n")
        assertEquals(false, store().export()["databaseFallbackValidated"])
        for (bad in listOf("dbcUseCount=13\n", "dbcUseCount=0\n", "dbcUse.0.extra=1\n", "dbcUse.0.path=../private\n", "dbcUse.0.handling=skip_validation\n",
            "dbcUse.0.sha256=bad\n", "dbcUse.0.rows=-1\n")) {
            file.writeText(original + bad)
            val report = store().export(); assertEquals(false, report["available"])
            assertFalse(report.containsKey("preflightPassed"))
        }
        file.writeText(original.lines().filterNot { it.startsWith("dbcUse.0.path=") }.joinToString("\n"))
        assertEquals(false, store().export()["available"])
    }
    private val editorPath = "dbc/WDBX - DBC EDITOR.zip"
    @Test fun cacheAdviceCoversCopiedExtractedAndReverifiedFilesWithoutPublishingEarly() {
        val paths = mutableSetOf<String>(); val advisedStages = mutableSetOf<String>(); var stage = "copy"
        var verifying = false
        val cache = GameDataCache { file, offset, length ->
            assertTrue(file.isFile); assertTrue(length > 0); assertTrue(offset + length <= file.length())
            if (!verifying) assertFalse(File(root, "store/active").exists())
            paths += file.name; advisedStages += stage; 0
        }
        val target = GameDataStore(File(root, "store"), text, cache = cache)
        val receipt = target.import(ByteArrayInputStream(zip()), boundary = { next, _ -> stage = next })
        assertTrue(advisedStages.containsAll(listOf("copy_sync", "archive_index", "extract", "preflight")))
        assertTrue(paths.contains(".input.zip")); assertTrue(paths.contains("Spell.dbc"))
        assertEquals(0L, cache.stats().rejected); assertTrue(cache.stats().accepted >= receipt.files)
        verifying = true; paths.clear()
        assertEquals(receipt, target.verify()); assertTrue(paths.contains("Spell.dbc")); assertTrue(paths.contains("files.tsv"))
        assertEquals(false, target.export()["worldReady"])
    }
    @Test fun unsupportedCacheAdviceDoesNotRelaxValidationOrPreventValidImport() {
        val cache = GameDataCache { _, _, _ -> 29 }
        val target = GameDataStore(File(root, "store"), text, cache = cache)
        val receipt = target.import(ByteArrayInputStream(zip()))
        assertEquals(receipt, target.verify()); assertTrue(cache.stats().rejected > 0)
        val bytes = zip(fixture().apply { remove("dbc/Spell.dbc") })
        assertThrows(GameDataRejected::class.java) { target.import(ByteArrayInputStream(bytes)) }
        assertEquals(receipt, target.verify())
        File(root, "store/${receipt.generation}/dbc/Spell.dbc").appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { target.verify() }
    }
    @Test fun reportedEditorArchiveIsPreservedOpaqueNonExecutableAndNeverExpanded() {
        val nested = zip(linkedMapOf("../outside.exe" to byteArrayOf(1, 2), "Spell.dbc" to byteArrayOf(3, 4)))
        val files = fixture().apply { put(editorPath, nested) }
        val receipt = store().import(ByteArrayInputStream(zip(files, "Data/")))
        val saved = File(root, "store/${receipt.generation}/$editorPath")
        assertArrayEquals(nested, saved.readBytes()); assertFalse(saved.canExecute())
        assertFalse(File(root, "store/${receipt.generation}/outside.exe").exists())
        assertEquals(files.size, receipt.files); assertEquals(receipt, store().verify())
        assertEquals(1L, store().export()["editorCompanionFiles"])
        assertEquals(false, store().export()["worldReady"])
    }
    @Test fun editorArchiveCannotSupplyMissingRequiredRuntimeFiles() {
        val before = store().import(ByteArrayInputStream(zip()))
        for (missing in listOf("dbc/Spell.dbc", "maps/0002828.map")) {
            val files = fixture(); val nested = zip(linkedMapOf(missing to files.remove(missing)!!))
            files[editorPath] = nested
            val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
            assertTrue(failure.preflight.problems.any { it.path == missing && it.reason.startsWith("missing_") })
            assertEquals(before, store().active())
        }
        assertEquals(before, store().verify())
    }
    @Test fun onlyTheReportedEditorPathIsAdmittedAndOtherArchivesStayRejected() {
        for (name in listOf("dbc/editor.zip", "dbc/WDBX.zip", "dbc/Spell.dbc.zip", "dbc/enUS/WDBX - DBC EDITOR.zip",
            "dbc/stock_dbc/WDBX - DBC EDITOR.zip", "maps/WDBX - DBC EDITOR.zip", "$editorPath/Spell.dbc")) {
            val failure = assertThrows(GameDataImportFailure::class.java) {
                store().import(ByteArrayInputStream(zip(fixture().apply { put(name, byteArrayOf(1)) })))
            }
            assertEquals("unsupported_asset_path", failure.reason); assertNull(store().active())
        }
    }
    @Test fun editorArchiveCrcFailurePreservesPriorCandidate() {
        val before = store().import(ByteArrayInputStream(zip()))
        val bytes = zip(fixture().apply { put(editorPath, byteArrayOf(1, 2, 3)) })
        var at = central(bytes)
        while (String(bytes, at + 46, shortAt(bytes, at + 28), Charsets.US_ASCII) != editorPath)
            at += 46 + shortAt(bytes, at + 28) + shortAt(bytes, at + 30) + shortAt(bytes, at + 32)
        put32(bytes, at + 16, intAt(bytes, at + 16) xor 1)
        val failure = assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(bytes)) }
        assertEquals("asset_integrity", failure.reason); assertEquals(editorPath, failure.asset)
        assertEquals(before, store().active()); assertEquals(before, store().verify())
    }
    @Test fun editorArchiveMutationFailsFullReverification() {
        val receipt = store().import(ByteArrayInputStream(zip(fixture().apply { put(editorPath, byteArrayOf(1, 2, 3)) })))
        File(root, "store/${receipt.generation}/$editorPath").writeBytes(byteArrayOf(1, 2, 4))
        assertThrows(IllegalArgumentException::class.java) { store().verify() }
        assertEquals(false, store().export()["preflightPassed"])
    }
    @Test fun editorReportCountDefaultsForOlderReportsAndRejectsImpossibleCounts() {
        store().import(ByteArrayInputStream(zip()))
        val file = File(root, "store/last-report.properties")
        file.writeText(file.readLines().filterNot { it.startsWith("editorCompanionFiles=") }.joinToString("\n", postfix = "\n"))
        assertEquals(0L, store().export()["editorCompanionFiles"])
        file.appendText("editorCompanionFiles=2\n")
        assertEquals(false, store().export()["available"])
    }
    @Test fun sourcePolicyCannotPromoteEditorArchiveToRequiredRuntimeDbc() {
        val key = "dbc.${policy.dbc.keys.first()}="
        assertTrue(text.contains(key))
        assertThrows(IllegalArgumentException::class.java) {
            GameDataPolicy.parse(text.replace(key, "dbc.WDBX\\ -\\ DBC\\ EDITOR.zip="))
        }
    }
    @Test fun everyImportBoundaryIsObservedAndIndexHasEntryProgress() {
        val stages = mutableSetOf<String>(); val boundaries = mutableListOf<Pair<String, String?>>()
        var indexed = 0L
        store().import(ByteArrayInputStream(zip()), progress = { stage, done, total ->
            stages += stage
            if (stage == "archive_index") { assertTrue(done <= total); indexed = done }
        }, boundary = { stage, archive -> boundaries += stage to archive })
        assertEquals(setOf("copy", "copy_sync", "archive_index", "extract", "preflight", "publish"), stages)
        assertEquals(fixture().size.toLong(), indexed)
        val hash = store().active()!!.archiveSha256
        assertEquals(listOf("copy", "copy_sync", "archive_index", "extract", "preflight", "publish"), boundaries.map { it.first })
        assertTrue(boundaries.drop(2).all { it.second == hash })
    }
    @Test fun indexCancellationBeforeExtractionPreservesPreviousCandidateAndFailureStage() {
        val before = store().import(ByteArrayInputStream(zip()))
        var indexing = false; var extracted = false
        assertThrows(IllegalStateException::class.java) {
            store().import(ByteArrayInputStream(zip()), active = { check(!indexing) },
                progress = { stage, _, _ -> if (stage == "extract") extracted = true },
                boundary = { stage, _ -> indexing = stage == "archive_index" })
        }
        assertFalse(extracted); assertEquals(before, store().active())
        assertEquals("archive_index", store().export()["failureStage"])
        assertEquals(before, store().verify())
    }
    @Test fun durableOperationCanBeReadWhileStoreLeaseIsHeld() {
        val operation = GameDataOperation(File(root, "operation.properties"))
        val attempt = "12345678-1234-4234-8234-123456789abc"
        val record = GameDataOperation.Record(attempt, ProcessIdentity(123, 456, 1), "import", 1000, 1001)
        var readDuringIndex = false
        store().import(ByteArrayInputStream(zip()), boundary = { stage, hash ->
            operation.write(record.copy(stage = stage, archive = hash))
            if (stage == "archive_index") {
                assertEquals("archive_index", operation.export(attempt, true)["stage"])
                assertEquals(hash, operation.export(attempt, true)["archiveSha256"])
                assertThrows(Exception::class.java) { store().export() }
                readDuringIndex = true
            }
        })
        assertTrue(readDuringIndex)
    }
    @Test fun indexChecksCancellationWhileWalkingLargerDirectory() {
        val files = fixture().apply { repeat(500) { put("vmaps/extra$it.vmo", "VMAP_4.8".toByteArray()) } }
        var progress = 0L
        assertThrows(IllegalStateException::class.java) {
            GameDataZip.inspect(archive(zip(files)), active = { check(progress < 128) }, progress = { done, _ -> progress = done })
        }
        assertEquals(128L, progress)
    }
    @Test fun reportedDb2CompanionIsPreservedWithoutBeingParsedAsRuntimeDbc() {
        val files = fixture().apply { put("dbc/lightskybox.db2", byteArrayOf(0, 1, 2, 3, 4)) }
        val receipt = store().import(ByteArrayInputStream(zip(files)))
        assertArrayEquals(files.getValue("dbc/lightskybox.db2"), File(root, "store/${receipt.generation}/dbc/lightskybox.db2").readBytes())
        assertArrayEquals(files.getValue("dbc/Spell.dbc"), File(root, "store/${receipt.generation}/dbc/Spell.dbc").readBytes())
        assertEquals(receipt, store().verify())
        val report = store().export()
        assertEquals(1L, report["db2CompanionFiles"])
        assertEquals(false, report["nativeSemanticValidationPassed"])
        assertEquals(false, report["worldReady"])
    }
    @Test fun db2CompanionsCannotReplaceMissingMalformedOrWrongVersionRuntimeDbc() {
        val before = store().import(ByteArrayInputStream(zip()))
        for (kind in listOf("missing", "layout", "sentinel")) {
            val files = fixture().apply { put("dbc/Spell.db2", getValue("dbc/Spell.dbc").copyOf()) }
            when (kind) {
                "missing" -> files.remove("dbc/Spell.dbc")
                "layout" -> put32(files.getValue("dbc/Spell.dbc"), 8, 1)
                else -> put32(files.getValue("dbc/Spell.dbc"), 20, 1)
            }
            val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
            assertTrue(failure.preflight.problems.any { it.path == "dbc/Spell.dbc" })
            assertEquals("preflight", store().export()["failureStage"])
            assertEquals(before, store().active())
        }
        assertEquals(before, store().verify())
    }
    @Test fun companionScopesKeepStockCountsSeparateAndPreserveEveryByteAfterReopen() {
        val paths = listOf("dbc/lightskybox.db2", "dbc/stock_dbc/lightskybox.db2", "dbc/enUS/Spell.db2")
        val files = fixture().apply {
            paths.forEachIndexed { i, path -> put(path, ByteArray(i) { it.toByte() }) }
            put("dbc/stock_dbc/Spell.dbc", getValue("dbc/Spell.dbc").copyOf())
        }
        val receipt = store().import(ByteArrayInputStream(zip(files, "Data/")))
        assertEquals(receipt, store().verify())
        paths.forEach { assertArrayEquals(files.getValue(it), File(root, "store/${receipt.generation}/$it").readBytes()) }
        assertEquals(3L, store().export()["db2CompanionFiles"])
        assertEquals(1L, store().export()["stockDbcFiles"])
    }
    @Test fun changedOpaqueCompanionFailsFullHashVerificationAndRetainsCandidate() {
        val files = fixture().apply { put("dbc/lightskybox.db2", byteArrayOf(1, 2, 3)) }
        val receipt = store().import(ByteArrayInputStream(zip(files)))
        File(root, "store/${receipt.generation}/dbc/lightskybox.db2").writeBytes(byteArrayOf(1, 2, 4))
        assertThrows(IllegalArgumentException::class.java) { store().verify() }
        assertEquals("verify", store().export()["failureStage"])
        assertEquals(false, store().export()["preflightPassed"])
        assertEquals(receipt, store().active())
    }
    @Test fun db2NameDoesNotBypassNamespacePathModeOrCrcChecks() {
        for (path in listOf("maps/lightskybox.db2", "dbc/other/lightskybox.db2", "dbc/stock_dbc/nested/a.db2", "dbc/../a.db2")) {
            val files = fixture().apply { put(path, byteArrayOf(1)) }
            assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(zip(files))) }
            assertNull(store().active())
        }
        val files = linkedMapOf("dbc/lightskybox.db2" to byteArrayOf(1, 2, 3)).apply { putAll(fixture()) }
        val symlink = zip(files); put32(symlink, central(symlink) + 38, 0xa000 shl 16)
        val failure = assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(symlink)) }
        assertEquals("unsupported_file_mode", failure.reason)
        val corrupt = zip(files); val c = central(corrupt); put32(corrupt, c + 16, intAt(corrupt, c + 16) xor 1)
        assertThrows(Exception::class.java) { store().import(ByteArrayInputStream(corrupt)) }
        assertNull(store().active())
    }
    @Test fun runtimePolicyCannotRedefineADbcRequirementAsOpaqueDb2() {
        assertThrows(IllegalArgumentException::class.java) { GameDataPolicy.parse(text.replace("dbc.Spell.dbc=", "dbc.Spell.db2=")) }
    }
    @Test fun unsupportedAssetsAreCollectedAcrossWholeIndexBeforeAnyExtraction() {
        val before = store().import(ByteArrayInputStream(zip()))
        val unsupported = (0 until 40).map { "dbc/extra$it.txt" }
        val files = fixture().apply { unsupported.forEach { put(it, byteArrayOf(1)) }; put("dbc/lightskybox.db2", byteArrayOf(1)) }
        var extracted = false
        val failure = assertThrows(GameDataImportFailure::class.java) {
            store().import(ByteArrayInputStream(zip(files, "Data/")), progress = { stage, _, _ -> if (stage == "extract") extracted = true })
        }
        assertEquals(40, failure.rejectedAssetCount); assertFalse(extracted)
        val report = store().export()
        assertEquals(40, report["rejectedAssetCount"])
        assertEquals(unsupported.take(32), report["rejectedAssets"])
        assertEquals(unsupported.first(), report["failureAsset"])
        assertEquals("archive_index", report["failureStage"])
        assertEquals(false, report["preflightPassed"]); assertEquals(before, store().active())
        store().verify()
        assertEquals(0, store().export()["rejectedAssetCount"])
        assertEquals(emptyList<String>(), store().export()["rejectedAssets"])
    }
    @Test fun unsafeNameAfterUnsupportedEntryIsNeverCollectedOrExported() {
        val files = fixture().apply { put("dbc/extra.txt", byteArrayOf(1)); put("../private-file", byteArrayOf(1)) }
        assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        val report = store().export()
        assertEquals("unsafe_archive_path", report["failureReason"])
        assertEquals(emptyList<String>(), report["rejectedAssets"])
        assertFalse(report.toString().contains("private-file"))
    }
    @Test fun legacyReportsDefaultNewFieldsAndMalformedCollectedPathsCannotClaimSuccess() {
        store().import(ByteArrayInputStream(zip()))
        val report = File(root, "store/last-report.properties")
        val legacy = report.readLines().filterNot { it.startsWith("db2CompanionFiles=") }.joinToString("\n", postfix = "\n")
        report.writeText(legacy)
        assertEquals(true, store().export()["preflightPassed"])
        assertEquals(0L, store().export()["db2CompanionFiles"])
        for (bad in listOf("rejectedAssetCount=100001\n", "rejectedAssetCount=1\n", "rejectedAssetCount=1\nrejectedAsset.0=../private\n")) {
            report.writeText(legacy + bad)
            assertEquals(false, store().export()["available"])
            assertFalse(store().export().containsKey("preflightPassed"))
        }
    }
    private fun fixture(): MutableMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        for ((name, shape) in policy.dbc) {
            val b = ByteBuffer.allocate(20 + shape.recordBytes + 1).order(LITTLE_ENDIAN)
                .put("WDBC".toByteArray()).putInt(1).putInt(shape.fields).putInt(shape.recordBytes).putInt(1)
            if (shape.sentinel >= 0) b.putInt(20 + shape.indexOffset, shape.sentinel.toInt())
            files["dbc/$name"] = b.array()
        }
        for (tile in policy.startupTiles) {
            files["maps/$tile.map"] = ByteBuffer.allocate(44).order(LITTLE_ENDIAN).put("MAPS".toByteArray()).putInt(9).putInt(12340).array()
            val id = tile.take(3)
            files["vmaps/$id.vmtree"] = "VMAP_4.8".toByteArray() + byteArrayOf(1)
            files["vmaps/${id}_${tile.takeLast(2)}_${tile.substring(3, 5)}.vmtile"] = "VMAP_4.8".toByteArray()
        }
        files["mmaps/000.mmap"] = ByteBuffer.allocate(28).order(LITTLE_ENDIAN)
            .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(533f).putFloat(533f).putInt(4096).putInt(32768).array()
        files["mmaps/0002828.mmtile"] = ByteBuffer.allocate(64).order(LITTLE_ENDIAN)
            .putInt(0x4d4d4150).putInt(7).putInt(20).putInt(8).apply { position(56) }.putInt(0x444e4156).putInt(7).array()
        return files
    }
    private fun zip(files: Map<String, ByteArray> = fixture(), wrapper: String = ""): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (wrapper.isNotEmpty()) { z.putNextEntry(ZipEntry(wrapper)); z.closeEntry() }
            files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(wrapper + name)); z.write(bytes); z.closeEntry() }
        }
        return out.toByteArray()
    }
    private fun archive(bytes: ByteArray): File = File(root, "input.zip").apply { writeBytes(bytes) }
    private fun put32(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).putInt(at, value) }
    private fun central(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).getInt(bytes.size - 22 + 16)
    private fun intAt(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).getInt(at)
    private fun shortAt(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).getShort(at).toInt() and 65535
    @Test fun originalGeneratorHighBitMmapCapacityImportsAndReverifies() {
        val files = fixture(); put32(files.getValue("mmaps/000.mmap"), 24, Int.MIN_VALUE)
        val receipt = store().import(ByteArrayInputStream(zip(files)))
        assertEquals(receipt, store().verify())
        assertArrayEquals(files.getValue("mmaps/000.mmap"), File(root, "store/${receipt.generation}/mmaps/000.mmap").readBytes())
        assertEquals(1, store().export()["mmapHighBitCapacityFiles"])
        assertEquals(false, store().export()["nativeSemanticValidationPassed"])
    }
    @Test fun mmapHighBitExceptionDoesNotAdmitOtherInvalidParameters() {
        val baseline = fixture().getValue("mmaps/000.mmap")
        val file = File(root, "parameters")
        val invalid = listOf(24 to 0, 24 to -1, 24 to Int.MIN_VALUE + 1, 20 to 0,
            20 to Int.MIN_VALUE, 0 to Float.NaN.toRawBits(), 12 to 0, 16 to Float.POSITIVE_INFINITY.toRawBits())
        for ((offset, value) in invalid) {
            val bytes = baseline.copyOf(); put32(bytes, 24, Int.MIN_VALUE); put32(bytes, offset, value)
            file.writeBytes(bytes)
            assertEquals("offset=$offset value=$value", "mmap_parameter_values", policy.inspectFile(file, "mmaps/000.mmap"))
        }
        for (size in listOf(27, 29)) {
            file.writeBytes(baseline.copyOf(size))
            assertEquals("mmap_parameter_layout", policy.inspectFile(file, "mmaps/000.mmap"))
        }
    }
    @Test fun rejectedDbcExportsMeasuredHeaderHashAndUnchangedSourceShape() {
        val before = store().import(ByteArrayInputStream(zip()))
        val path = "dbc/gtBarberShopCostBase.dbc"
        val changed = ByteBuffer.allocate(29).order(LITTLE_ENDIAN).put("WDBC".toByteArray())
            .putInt(1).putInt(1).putInt(4).putInt(5).putInt(123).put("hide\u0000".toByteArray()).array()
        val files = fixture().apply { put(path, changed) }
        val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        assertEquals("dbc_source_layout", failure.preflight.problems.single().reason)
        val report = store().export()
        val problem = (report["problems"] as List<*>).single() as Map<*, *>
        assertEquals(path, problem["asset"])
        assertEquals(mapOf("fileBytes" to 29L, "magic" to 0x43424457L, "rows" to 1L, "fields" to 1L,
            "recordBytes" to 4L, "stringBytes" to 5L), problem["observedHeader"])
        assertEquals(mapOf("fields" to 2L, "recordBytes" to 8L), problem["sourceExpected"])
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(changed).joinToString("") { "%02x".format(it) }, problem["sha256"])
        assertFalse(report.toString().contains("hide")); assertFalse(report.toString().contains(root.absolutePath))
        assertEquals(before, store().active()); assertEquals(false, report["preflightPassed"])
    }
    @Test fun completeReasonCountsSurviveDetailLimitAndHeaderCollectionPrecedesCacheRelease() {
        val files = fixture()
        for (i in 0 until 40) files["dbc/Extra$i.dbc"] = ByteBuffer.allocate(24).order(LITTLE_ENDIAN)
            .put("WDBC".toByteArray()).putInt(1).putInt(1).putInt(4).putInt(0).putInt(123).array()
        put32(files.getValue("mmaps/000.mmap"), 24, -1)
        var preflight = false
        val cache = GameDataCache { file, _, _ ->
            // Simulates cache contents becoming unavailable after their last read.
            if (preflight && file.name.startsWith("Extra")) file.writeBytes(byteArrayOf())
            0
        }
        val target = GameDataStore(File(root, "store"), text, cache = cache)
        assertThrows(GameDataRejected::class.java) {
            target.import(ByteArrayInputStream(zip(files)), boundary = { stage, _ -> preflight = stage == "preflight" })
        }
        val report = target.export()
        assertEquals(41L, report["problemCount"])
        assertEquals(mapOf("dbc_layout" to 40, "mmap_parameter_values" to 1), report["problemCountsByReason"])
        val problems = report["problems"] as List<*>
        assertEquals(32, problems.size)
        val headers = problems.map { it as Map<*, *> }.filter { (it["asset"] as String).startsWith("dbc/Extra") }
            .map { it["observedHeader"] as Map<*, *> }
        assertTrue(headers.size >= 31)
        assertTrue(headers.all { it["fileBytes"] == 24L && it["stringBytes"] == 0L })
        assertTrue(File(root, "store/last-report.properties").length() < 65536)
    }
    @Test fun rejectedMmapExportsUnsignedHeaderBitsWithoutInterpretingNanAsJsonNumber() {
        val files = fixture(); val bytes = files.getValue("mmaps/000.mmap")
        put32(bytes, 0, Float.NaN.toRawBits()); put32(bytes, 24, Int.MIN_VALUE)
        assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        val report = store().export()
        val problem = (report["problems"] as List<*>).single() as Map<*, *>
        val header = problem["observedHeader"] as Map<*, *>
        assertEquals(0x80000000L, header["maxPolysBits"])
        assertEquals(Integer.toUnsignedLong(Float.NaN.toRawBits()), header["originXBits"])
        assertEquals(1, report["mmapHighBitCapacityFiles"])
        assertEquals(false, report["preflightPassed"])
    }
    @Test fun truncatedHeaderExportsOnlyCompleteNumericWords() {
        val files = fixture().apply { put("dbc/Extra.dbc", "WDBCx".toByteArray()) }
        assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        val problem = ((store().export()["problems"] as List<*>).single() as Map<*, *>)
        assertEquals("truncated_asset", problem["reason"])
        assertEquals(mapOf("fileBytes" to 5L, "magic" to 0x43424457L), problem["observedHeader"])
    }
    @Test fun legacyHeaderlessReportsRemainReadableWithoutInventingMeasurements() {
        store().import(ByteArrayInputStream(zip()))
        val file = File(root, "store/last-report.properties")
        file.writeText(file.readLines().filterNot { it.startsWith("mmapHighBitCapacityFiles=") || it.startsWith("reasonCount.") }
            .joinToString("\n", postfix = "\n").replace("formatVersion=3", "formatVersion=1"))
        val report = store().export()
        assertEquals(true, report["preflightPassed"]); assertEquals(false, report["headerDiagnosticsAvailable"])
        assertFalse(report.containsKey("mmapHighBitCapacityFiles")); assertFalse(report.containsKey("problemCountsByReason"))
    }
    @Test fun corruptHeaderDiagnosticsFailClosedWithoutPartialSuccessFields() {
        val files = fixture().apply { put("dbc/Extra.dbc", "WDBCx".toByteArray()) }
        assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        val file = File(root, "store/last-report.properties"); val original = file.readText()
        for (bad in listOf("reasonCount.truncated_asset=2\n", "problem.0.observed.magic=4294967296\n",
            "problem.0.observed.fileBytes=-1\n", "problem.0.observed.secret=1\n", "problem.0.sha256=bad\n",
            "mmapHighBitCapacityFiles=100001\n", "problem.0.expected.fields=-1\n")) {
            file.writeText(original + bad)
            val report = store().export()
            assertEquals(false, report["available"]); assertFalse(report.containsKey("preflightPassed"))
        }
        file.writeText(original.lines().filterNot { it.startsWith("problem.0.path=") }.joinToString("\n"))
        assertEquals(false, store().export()["available"])
    }
    @Test fun originalPolicyHasAllSourceSchemasSentinelsAndSwappedVmapTileCoordinates() {
        assertEquals(113, policy.dbc.size); assertEquals(7, policy.dbc.values.count { it.sentinel >= 0 })
        assertEquals(9L, policy.mapVersion); assertEquals(20L, policy.mmapVersion)
        assertTrue(policy.startupTiles.contains("0004331"))
        assertTrue(fixture().containsKey("vmaps/000_31_43.vmtile"))
        assertTrue(policy.dbc.getValue("CharStartOutfit.dbc").recordBytes < policy.dbc.getValue("CharStartOutfit.dbc").fields * 4)
    }
    @Test fun originalSourceOrFormatChangesCannotSilentlyChangePolicy() {
        for (changed in listOf(text.replace("mapVersion=9", "mapVersion=8"), text.replace("dbcCount=113", "dbcCount=112"),
            text.replace(com.russianranger.fta.Inputs.source.sha256, "f".repeat(64)))) {
            assertThrows(IllegalArgumentException::class.java) { GameDataPolicy.parse(changed) }
        }
    }
    @Test fun completeSyntheticInventoryImportsAndReverifiesAfterStoreRestartWithoutNativeReadinessClaim() {
        val receipt = store().import(ByteArrayInputStream(zip()))
        assertEquals(fixture().size, receipt.files)
        assertEquals(receipt, store().active()); assertEquals(receipt, store().verify())
        val report = store().export()
        assertEquals("VERIFIED", report["state"]); assertEquals(true, report["preflightPassed"])
        assertEquals(false, report["worldReady"]); assertEquals(false, report["nativeSemanticValidationPassed"])
        assertEquals(true, report["activeCatalogMatchesReport"])
    }
    @Test fun singleWrapperFolderIsRemovedWithoutRenamingAssets() {
        val receipt = store().import(ByteArrayInputStream(zip(wrapper = "MyServerData/")))
        assertTrue(File(root, "store/${receipt.generation}/dbc/Spell.dbc").isFile)
        assertFalse(File(root, "store/${receipt.generation}/MyServerData").exists())
        store().verify()
    }
    @Test fun stockDbcBackupIsPreservedWithoutApplyingRuntimeLayoutToUnusedBackup() {
        val files = fixture()
        val backup = ByteBuffer.allocate(25).order(LITTLE_ENDIAN)
            .put("WDBC".toByteArray()).putInt(1).putInt(1).putInt(4).putInt(1).putInt(123).put(0).array()
        files["dbc/stock_dbc/Spell.dbc"] = backup
        val receipt = store().import(ByteArrayInputStream(zip(files)))
        assertArrayEquals(backup, File(root, "store/${receipt.generation}/dbc/stock_dbc/Spell.dbc").readBytes())
        assertArrayEquals(files.getValue("dbc/Spell.dbc"), File(root, "store/${receipt.generation}/dbc/Spell.dbc").readBytes())
        assertEquals(receipt, store().verify())
        assertEquals(1L, store().export()["stockDbcFiles"])
    }
    @Test fun stockBackupCannotSatisfyMissingOrInvalidRuntimeDbc() {
        for (missing in listOf(true, false)) {
            val files = fixture()
            files["dbc/stock_dbc/Spell.dbc"] = files.getValue("dbc/Spell.dbc").copyOf()
            if (missing) files.remove("dbc/Spell.dbc") else put32(files.getValue("dbc/Spell.dbc"), 20, 1)
            val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
            assertTrue(failure.preflight.problems.any { it.path == "dbc/Spell.dbc" &&
                it.reason == if (missing) "missing_required_dbc" else "dbc_version_sentinel" })
            assertNull(store().active())
        }
    }
    @Test fun backupDbcsStillRequireSafePathsAndWellFormedDbcBytes() {
        val files = fixture().apply { put("dbc/stock_dbc/Spell.dbc", byteArrayOf(1, 2, 3)) }
        val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        assertTrue(failure.preflight.problems.any { it.path == "dbc/stock_dbc/Spell.dbc" && it.reason == "truncated_asset" })
        for (name in listOf("dbc/stock_dbc/evil.sql", "dbc/stock_dbc/run.so", "dbc/other/Spell.dbc", "dbc/stock_dbc/nested/Spell.dbc")) {
            val bad = fixture().apply { put(name, byteArrayOf(1)) }
            val rejected = assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
            assertEquals("unsupported_asset_path", rejected.reason)
            assertEquals(name, store().export()["failureAsset"])
        }
    }
    @Test fun changedStockBackupFailsFullVerificationAndNeverChangesRuntimeFile() {
        val files = fixture().apply { put("dbc/stock_dbc/Spell.dbc", getValue("dbc/Spell.dbc").copyOf()) }
        val receipt = store().import(ByteArrayInputStream(zip(files, "Data/")))
        val backup = File(root, "store/${receipt.generation}/dbc/stock_dbc/Spell.dbc")
        backup.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { store().verify() }
        assertEquals("verify", store().export()["failureStage"])
        assertArrayEquals(files.getValue("dbc/Spell.dbc"), File(root, "store/${receipt.generation}/dbc/Spell.dbc").readBytes())
        assertEquals(receipt, store().active())
    }
    @Test fun localizedDbcsKeepOriginalRuntimeLayoutChecks() {
        val files = fixture().apply { put("dbc/enUS/Spell.dbc", getValue("dbc/Spell.dbc").copyOf()) }
        put32(files.getValue("dbc/enUS/Spell.dbc"), 20, 1)
        val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        assertTrue(failure.preflight.problems.any { it.path == "dbc/enUS/Spell.dbc" && it.reason == "dbc_version_sentinel" })
    }
    @Test fun unsupportedZipCompressionAndCorruptIndexHaveDistinctBoundedReports() {
        val bytes = zip(); val c = central(bytes)
        ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).putShort(c + 10, 12)
        assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(bytes)) }
        assertEquals("unsupported_compression", store().export()["failureReason"])
        assertEquals("archive_index", store().export()["failureStage"])
        assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        assertEquals("invalid_zip", store().export()["failureReason"])
        assertEquals("archive_index", store().export()["failureStage"])
    }
    @Test fun unsafeArchiveNameIsNeverIncludedInExportedFailure() {
        val name = "../private-personal-document"
        val files = fixture().apply { put(name, byteArrayOf(1)) }
        assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        val report = store().export()
        assertEquals("unsafe_archive_path", report["failureReason"])
        assertFalse(report.containsKey("failureAsset")); assertFalse(report.toString().contains(name))
    }
    @Test fun failedReplacementReportsArchiveFailureAndSuccessfulVerifyClearsIt() {
        val before = store().import(ByteArrayInputStream(zip()))
        val files = fixture().apply { put("dbc/stock_dbc/notes.txt", byteArrayOf(1)) }
        assertThrows(GameDataImportFailure::class.java) { store().import(ByteArrayInputStream(zip(files))) }
        val report = store().export()
        assertEquals("unsupported_asset_path", report["failureReason"])
        assertEquals("dbc/stock_dbc/notes.txt", report["failureAsset"])
        assertEquals(true, report["activeCandidateRetained"]); assertEquals(before, store().active())
        store().verify()
        assertFalse(store().export().containsKey("failureReason"))
        assertEquals(true, store().export()["preflightPassed"])
    }
    @Test fun sourceReadFailureReportsCopyStageWithoutExportingPrivateExceptionText() {
        val before = store().import(ByteArrayInputStream(zip()))
        val input = object : java.io.InputStream() { override fun read(): Int { throw java.io.IOException("content://private/path?secret=example") } }
        assertThrows(java.io.IOException::class.java) { store().import(input) }
        val report = store().export()
        assertEquals("copy", report["failureStage"]); assertEquals("operation_failed", report["failureReason"])
        assertFalse(report.toString().contains("content:")); assertFalse(report.toString().contains("secret"))
        assertEquals(before, store().active())
    }
    @Test fun corruptReportDoesNotPartiallyExportSuccessfulQualification() {
        store().import(ByteArrayInputStream(zip()))
        File(root, "store/last-report.properties").appendText("failureReason=untrusted_private_text\n")
        val report = store().export()
        assertEquals(false, report["available"])
        assertFalse(report.containsKey("preflightPassed")); assertFalse(report.containsKey("state"))
    }
    @Test fun missingRequiredDbcRejectsCandidateAndRetainsPreviouslyVerifiedGeneration() {
        val before = store().import(ByteArrayInputStream(zip()))
        val bad = fixture().apply { remove("dbc/Spell.dbc") }
        val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
        assertTrue(failure.preflight.problems.any { it.path == "dbc/Spell.dbc" && it.reason == "missing_required_dbc" })
        assertEquals(before, store().active()); assertEquals("REJECTED", store().export()["state"])
        assertEquals(before, store().verify())
    }
    @Test fun sourceVersionSentinelIsCheckedInOriginalRecordLayout() {
        val bad = fixture(); put32(bad.getValue("dbc/Spell.dbc"), 20 + policy.dbc.getValue("Spell.dbc").indexOffset, 1)
        val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
        assertTrue(failure.preflight.problems.any { it.reason == "dbc_version_sentinel" })
        assertNull(store().active())
    }
    @Test fun byteFieldDbcRejectsNaiveFourBytesPerFieldHeader() {
        val bad = fixture(); val value = bad.getValue("dbc/CharStartOutfit.dbc")
        put32(value, 12, policy.dbc.getValue("CharStartOutfit.dbc").fields * 4)
        assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
    }
    @Test fun wrongMapVmapMmapAndDetourVersionsAreReportedWithoutRewritingFiles() {
        for ((name, change) in listOf<Pair<String, (ByteArray) -> Unit>>(
            "maps/0002828.map" to { put32(it, 4, 8) }, "vmaps/000.vmtree" to { it[7] = '7'.code.toByte() },
            "mmaps/0002828.mmtile" to { put32(it, 8, 19) }, "mmaps/0002828.mmtile" to { put32(it, 60, 6) })) {
            val bad = fixture(); change(bad.getValue(name))
            val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
            assertTrue(failure.preflight.problems.any { it.path == name && it.reason.endsWith("version") })
        }
    }
    @Test fun missingStartupVmapTileUsesOriginalYThenXFilenameRule() {
        val bad = fixture().apply { remove("vmaps/000_31_43.vmtile") }
        val failure = assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
        assertTrue(failure.preflight.problems.any { it.path == "vmaps/000_31_43.vmtile" && it.reason == "missing_startup_vmap_tile" })
    }
    @Test fun overflowingMapSectionsAndNonfiniteNavmeshParametersFail() {
        for ((path, at, value) in listOf(Triple("maps/0002828.map", 12, -1), Triple("mmaps/000.mmap", 12, 0x7fc00000),
            Triple("mmaps/0002828.mmtile", 12, Int.MAX_VALUE), Triple("dbc/Spell.dbc", 4, Int.MAX_VALUE))) {
            val bad = fixture(); put32(bad.getValue(path), at, value)
            assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
        }
    }
    @Test fun truncationFailsAndDoesNotPublishAnyGeneration() {
        val bad = fixture().apply { this["maps/0002828.map"] = byteArrayOf(1, 2) }
        assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
        assertNull(store().active())
    }
    @Test fun unsafeZipPathsAndExecutableOrSqlPayloadsAreRejectedBeforeExtraction() {
        for (name in listOf("../outside", "/absolute", "dbc/../../outside", "dbc\\Spell.dbc", "C:/dbc/Spell.dbc", "dbc/a\t.dbc", "dbc/foo./x.dbc", "dbc/evil.sql", "maps/run.so", "Wow.exe", "Data/common.MPQ")) {
            val bad = fixture().apply { put(name, byteArrayOf(1)) }
            assertThrows(Exception::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
            assertNull(store().active())
        }
    }
    @Test fun duplicateCasePathsAreRejected() {
        val bad = fixture().apply { put("dbc/spell.dbc", getValue("dbc/Spell.dbc")) }
        assertThrows(IllegalArgumentException::class.java) { GameDataZip.inspect(archive(zip(bad))) }
    }
    @Test fun symlinkSpecialFileAndEncryptionFlagsAreRejectedFromCentralMetadata() {
        for (mode in listOf(0xa000, 0x1000, 0x6000)) {
            val bytes = zip(); val c = central(bytes); put32(bytes, c + 38, mode shl 16)
            assertThrows(IllegalArgumentException::class.java) { GameDataZip.inspect(archive(bytes)) }
        }
        val bytes = zip(); val c = central(bytes); bytes[c + 8] = (bytes[c + 8].toInt() or 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { GameDataZip.inspect(archive(bytes)) }
    }
    @Test fun localCentralNameMismatchAndOverlappingOffsetsAreRejected() {
        val bytes = zip(); bytes[30] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { GameDataZip.inspect(archive(bytes)) }
        val overlap = zip(); val c = central(overlap)
        val next = c + 46 + shortAt(overlap, c + 28) + shortAt(overlap, c + 30) + shortAt(overlap, c + 32)
        put32(overlap, next + 42, intAt(overlap, c + 42))
        assertThrows(IllegalArgumentException::class.java) { GameDataZip.inspect(archive(overlap)) }
    }
    @Test fun zip64EndRecordsWithSameSmallPayloadImportThroughRealJavaZipReader() {
        val bytes = zip(); val e = bytes.size - 22; val count = shortAt(bytes, e + 10)
        val z64 = ByteBuffer.allocate(76).order(LITTLE_ENDIAN)
            .putInt(0x06064b50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
            .putLong(count.toLong()).putLong(count.toLong()).putLong(intAt(bytes, e + 12).toLong()).putLong(central(bytes).toLong())
            .putInt(0x07064b50).putInt(0).putLong(e.toLong()).putInt(1).array()
        val end = bytes.copyOfRange(e, bytes.size)
        ByteBuffer.wrap(end).order(LITTLE_ENDIAN).putShort(8, -1).putShort(10, -1).putInt(12, -1).putInt(16, -1)
        val result = bytes.copyOfRange(0, e) + z64 + end
        assertEquals(fixture().size, GameDataZip.inspect(archive(result)).size)
        store().import(ByteArrayInputStream(result)); store().verify()
    }
    @Test fun forgedZip64LocatorIsRejected() {
        val bytes = zip(); put32(bytes, bytes.size - 42, 0x07064b50)
        assertThrows(Exception::class.java) { GameDataZip.inspect(archive(bytes)) }
    }
    @Test fun crcMismatchCannotPublishPayload() {
        val bytes = zip(); val c = central(bytes); put32(bytes, c + 16, intAt(bytes, c + 16) xor 1)
        assertThrows(Exception::class.java) { store().import(ByteArrayInputStream(bytes)) }
        assertNull(store().active())
    }
    @Test fun cancelledReplacementKeepsActiveGenerationAndCleansPartialCopy() {
        val before = store().import(ByteArrayInputStream(zip())); var calls = 0
        assertThrows(IllegalStateException::class.java) {
            store().import(ByteArrayInputStream(zip()), { check(++calls < 80) })
        }
        assertEquals(before, store().active()); assertEquals(before, store().verify())
        assertFalse(File(root, "store").listFiles()!!.any { it.name.startsWith(".partial-") })
    }
    @Test fun reverifyRejectsChangedPayloadUnexpectedFilesAndSymlinksWithoutDeletingCandidate() {
        val receipt = store().import(ByteArrayInputStream(zip()))
        val file = File(root, "store/${receipt.generation}/maps/0002828.map"); val original = file.readBytes()
        file.writeBytes(original.apply { this[0] = 0 })
        assertThrows(IllegalArgumentException::class.java) { store().verify() }
        assertEquals(receipt, store().active())
        file.writeBytes(fixture().getValue("maps/0002828.map"))
        val extra = File(file.parentFile, "extra.txt").apply { writeText("extra") }
        assertThrows(IllegalArgumentException::class.java) { store().verify() }; extra.delete()
        val outside = File(root, "outside").apply { writeBytes(file.readBytes()) }
        file.delete(); Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { store().verify() }; assertTrue(outside.isFile)
    }
    @Test fun processLeasePreventsConcurrentImportAndVerification() {
        val before = store().import(ByteArrayInputStream(zip()))
        FileChannel.open(File(root, "store/lease").toPath(), WRITE).use { c -> c.lock().use {
            assertThrows(Exception::class.java) { store().import(ByteArrayInputStream(zip())) }
            assertThrows(Exception::class.java) { store().verify() }
        } }
        assertEquals(before, store().verify())
    }
    @Test fun corruptPointerDoesNotGetTreatedAsFirstInstallOrDeleteSavedAssets() {
        val receipt = store().import(ByteArrayInputStream(zip()))
        File(root, "store/active").writeText("../other")
        assertThrows(IllegalArgumentException::class.java) { store().active() }
        assertThrows(IllegalArgumentException::class.java) { store().import(ByteArrayInputStream(zip())) }
        assertTrue(File(root, "store/${receipt.generation}").isDirectory)
    }
    @Test fun reportContainsBoundedAssetFailuresWithoutArchiveBytesOrSourcePaths() {
        val bad = fixture().filterKeys { !it.startsWith("dbc/") }
        assertThrows(GameDataRejected::class.java) { store().import(ByteArrayInputStream(zip(bad))) }
        val report = store().export()
        assertEquals("REJECTED", report["state"]); assertEquals(false, report["preflightPassed"])
        assertEquals(32, (report["problems"] as List<*>).size)
        assertTrue((report["problemCount"] as Long) >= 113)
        assertFalse(report.toString().contains(root.absolutePath))
    }
    @Test fun directorySyncFailureBeforePublicationKeepsPreviousCandidate() {
        val before = store().import(ByteArrayInputStream(zip()))
        val failing = GameDataStore(File(root, "store"), text, syncDirectory = { directory ->
            if (directory.name.startsWith(".partial-")) throw java.io.IOException("test sync failure")
        })
        assertThrows(java.io.IOException::class.java) { failing.import(ByteArrayInputStream(zip())) }
        assertEquals(before, store().active()); assertEquals(before, store().verify())
    }
    @Test fun directorySyncFailureAfterPointerRenameNeverDeletesNewlyPublishedCandidate() {
        val before = store().import(ByteArrayInputStream(zip()))
        val failing = GameDataStore(File(root, "store"), text, syncDirectory = { directory ->
            if (directory == File(root, "store") && File(directory, "active").readText() != before.generation)
                throw java.io.IOException("test post-publication sync failure")
        })
        assertThrows(java.io.IOException::class.java) { failing.import(ByteArrayInputStream(zip())) }
        val after = store().active()!!
        assertNotEquals(before.generation, after.generation)
        assertTrue(File(root, "store/${before.generation}").isDirectory)
        assertEquals(after, store().verify())
    }
}
