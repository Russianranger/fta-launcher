package com.russianranger.fta.client

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class RuntimeInstallFailure(val reason: String) : Exception(reason)
internal fun runtimeRequire(ok: Boolean, reason: String) { if (!ok) throw RuntimeInstallFailure(reason) }
internal fun runtimeHash() = MessageDigest.getInstance("SHA-256")
internal fun runtimeHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

/** Authenticated metadata for one unchanged archive, not a general tar extractor. */
class RuntimeArchivePlan private constructor(
    val catalogSha256: String, val archiveBytes: Long, val archiveSha256: String,
    val expandedBytes: Long, val expandedSha256: String, val entries: List<Entry>
) {
    data class Entry(val kind: Char, val path: String, val mode: Int, val bytes: Long,
        val offset: Long, val sha256: String, val link: String)
    val fileBytes = entries.sumOf { it.bytes }
    val byPath = entries.associateBy { it.path }

    companion object {
        private val hash = Regex("[0-9a-f]{64}")
        private fun path(value: String): Boolean = value.length in 1..4096 &&
            !value.startsWith('/') && value.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") } &&
            value.none { it < ' ' || it == '\\' || it == '\u007f' }
        private fun link(entry: Entry) {
            if (entry.kind == 'h') { runtimeRequire(path(entry.link), "invalid-catalog"); return }
            runtimeRequire(entry.link.length in 1..4096 && entry.link.none { it < ' ' || it == '\\' || it == '\u007f' }, "invalid-catalog")
            val resolved = if (entry.link.startsWith('/')) mutableListOf() else entry.path.split('/').dropLast(1).toMutableList()
            for (part in entry.link.split('/')) when (part) {
                "", "." -> Unit
                ".." -> { runtimeRequire(resolved.isNotEmpty(), "invalid-catalog"); resolved.removeAt(resolved.lastIndex) }
                else -> resolved.add(part)
            }
        }
        fun read(input: InputStream, expectedSha256: String): RuntimeArchivePlan {
            // A hard cap precedes allocation; all paths/modes/offsets are authenticated.
            val data = input.use { source ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer); if (n < 0) break
                    runtimeRequire(n > 0 && output.size() + n <= 8 * 1024 * 1024, "invalid-catalog")
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            runtimeRequire(data.size in 1..8 * 1024 * 1024 && hash.matches(expectedSha256) &&
                runtimeHex(runtimeHash().digest(data)) == expectedSha256, "invalid-catalog")
            val text = data.toString(Charsets.UTF_8)
            runtimeRequire(text.toByteArray(Charsets.UTF_8).contentEquals(data) && text.endsWith('\n'), "invalid-catalog")
            val lines = text.lineSequence().iterator()
            fun fields() = lines.next().split('\t').also { runtimeRequire(it.sumOf(String::length) <= 16384, "invalid-catalog") }
            val header = fields()
            runtimeRequire(header.size == 4 && header[0] == "FTA_RUNTIME_CATALOG" && header[1] == "1" && hash.matches(header[3]), "invalid-catalog")
            val compressed = header[2].toLongOrNull() ?: throw RuntimeInstallFailure("invalid-catalog")
            runtimeRequire(compressed in 1..1024L * 1024 * 1024, "invalid-catalog")
            val entries = mutableListOf<Entry>(); val names = mutableSetOf<String>(); var end = 0L; var total = 0L
            var expanded = 0L; var expandedHash = ""
            while (lines.hasNext()) {
                val row = fields()
                if (row.firstOrNull() == "END") {
                    runtimeRequire(row.size == 3 && hash.matches(row[2]), "invalid-catalog")
                    expanded = row[1].toLongOrNull() ?: throw RuntimeInstallFailure("invalid-catalog")
                    expandedHash = row[2]
                    runtimeRequire(lines.hasNext() && lines.next().isEmpty() && !lines.hasNext(), "invalid-catalog")
                    break
                }
                runtimeRequire(row.size == 7 && row[0] in setOf("f", "d", "s", "h") && path(row[1]) && names.add(row[1]), "invalid-catalog")
                val mode = row[2].toIntOrNull() ?: throw RuntimeInstallFailure("invalid-catalog")
                val size = row[3].toLongOrNull() ?: throw RuntimeInstallFailure("invalid-catalog")
                val offset = row[4].toLongOrNull() ?: throw RuntimeInstallFailure("invalid-catalog")
                runtimeRequire(mode in 0..511 && size in 0..512L * 1024 * 1024 && offset in 512..4L * 1024 * 1024 * 1024 &&
                    offset % 512 == 0L && offset >= end + 512, "invalid-catalog")
                val entry = Entry(row[0][0], row[1], mode, size, offset, row[5], row[6])
                runtimeRequire(if (entry.kind == 'f') hash.matches(entry.sha256) && entry.link == "-"
                    else size == 0L && entry.sha256 == "-" && (entry.kind != 'd' || entry.link == "-"), "invalid-catalog")
                if (entry.kind in "sh") link(entry)
                entries.add(entry); total += size; end = offset + (size + 511) / 512 * 512
                runtimeRequire(entries.size <= 50000 && total <= 4L * 1024 * 1024 * 1024, "invalid-catalog")
            }
            runtimeRequire(entries.isNotEmpty() && expanded in end..4L * 1024 * 1024 * 1024 && hash.matches(expandedHash), "invalid-catalog")
            val byPath = entries.associateBy { it.path }
            for (entry in entries) {
                var parent = entry.path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    runtimeRequire(byPath[parent]?.kind == 'd', "invalid-catalog")
                    parent = parent.substringBeforeLast('/', "")
                }
                if (entry.kind == 'h') runtimeRequire(byPath[entry.link]?.kind == 'f', "invalid-catalog")
            }
            return RuntimeArchivePlan(expectedSha256, compressed, header[3], expanded, expandedHash, entries)
        }
    }
}
