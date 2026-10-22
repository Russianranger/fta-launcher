package com.russianranger.fta.runtime

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Checks every ZIP central entry before any pinned SQL is extracted. */
internal object PinnedDatabaseArchive {
    private const val MAX_ENTRIES = 4096
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

    private fun safePath(path: String, directory: Boolean) {
        val value = if (directory) path.removeSuffix("/") else path
        require(value.isNotEmpty() && value.length <= 240 && !value.startsWith('/') && !value.contains('\\') &&
            value.split('/').all { it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._+-]{1,120}")) }) { "Unsafe database seed path" }
    }

    /** Validate every central entry, including unused world/characters files; none are extracted. */
    fun validate(archive: File) {
        RandomAccessFile(archive, "r").use { input ->
            if (input.length() < 22) throw IOException("Database ZIP is incomplete")
            val tailSize = minOf(input.length(), 65_557L).toInt()
            val tail = ByteArray(tailSize)
            input.seek(input.length() - tailSize); input.readFully(tail)
            fun short(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            fun unsigned(bytes: ByteArray, offset: Int) = short(bytes, offset).toLong() or (short(bytes, offset + 2).toLong() shl 16)
            val end = (tail.size - 22 downTo 0).firstOrNull {
                unsigned(tail, it) == 0x06054b50L && it + 22 + short(tail, it + 20) == tail.size
            } ?: throw IOException("Database ZIP central directory is missing")
            val count = short(tail, end + 10)
            val offset = unsigned(tail, end + 16)
            val size = unsigned(tail, end + 12)
            if (count !in 1..MAX_ENTRIES || short(tail, end + 4) != 0 || short(tail, end + 6) != 0 ||
                short(tail, end + 8) != count || offset + size != input.length() - tailSize + end) {
                throw IOException("Split, ZIP64 or oversized database ZIP metadata is unsupported")
            }
            input.seek(offset)
            val names = mutableSetOf<String>()
            var uncompressed = 0L
            repeat(count) {
                val header = ByteArray(46); input.readFully(header)
                val nameSize = short(header, 28)
                if (unsigned(header, 0) != 0x02014b50L || short(header, 8) and 1 != 0 ||
                    short(header, 10) !in setOf(0, 8) || nameSize !in 1..241 || short(header, 34) != 0) {
                    throw IOException("Unsupported database ZIP entry metadata")
                }
                val nameBytes = ByteArray(nameSize); input.readFully(nameBytes)
                if (nameBytes.any { it.toInt() !in 32..126 }) throw IOException("Database ZIP path is not portable ASCII")
                val name = nameBytes.toString(Charsets.US_ASCII)
                safePath(name, name.endsWith('/'))
                if (!names.add(name.lowercase())) throw IOException("Duplicate database ZIP path")
                val mode = ((unsigned(header, 38) ushr 16) and 0xf000).toInt()
                if (mode !in setOf(0, 0x8000, 0x4000) || (mode == 0x4000 && !name.endsWith('/'))) {
                    throw IOException("Database ZIP symlinks and special entries are forbidden")
                }
                val bytes = unsigned(header, 24)
                uncompressed += bytes
                if (bytes > MAX_FILE_BYTES || uncompressed > MAX_TOTAL_BYTES || (name.endsWith('/') && bytes != 0L)) {
                    throw IOException("Database ZIP decompressed-size limit exceeded")
                }
                val extra = ByteArray(short(header, 30)); input.readFully(extra)
                var cursor = 0
                while (cursor < extra.size) {
                    if (cursor + 4 > extra.size) throw IOException("Malformed database ZIP extra field")
                    val field = short(extra, cursor)
                    val length = short(extra, cursor + 2)
                    if (field == 1 || cursor + 4 + length > extra.size) throw IOException("ZIP64 or malformed database ZIP extra field")
                    cursor += 4 + length
                }
                input.seek(input.filePointer + short(header, 32))
                if (input.filePointer > offset + size) throw IOException("Database ZIP central-directory bounds mismatch")
            }
            if (input.filePointer != offset + size) throw IOException("Database ZIP central-directory size mismatch")
        }
    }
}
