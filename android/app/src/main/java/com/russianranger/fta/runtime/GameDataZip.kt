package com.russianranger.fta.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.util.Locale

data class GameDataZipEntry(val name: String, val path: String, val bytes: Long, val compressed: Long, val crc: Long)

/** ZIP/ZIP64 index, modes and local names checked before any asset is written. */
object GameDataZip {
    fun inspect(file: File): List<GameDataZipEntry> = RandomAccessFile(file, "r").use { input ->
        require(input.length() in 22..GameDataPolicy.MAX_TOTAL_BYTES)
        fun read(at: Long, n: Int): ByteArray {
            require(at >= 0 && n >= 0 && at <= input.length() - n)
            input.seek(at); return ByteArray(n).also { input.readFully(it) }
        }
        fun u16(a: ByteArray, n: Int) = ByteBuffer.wrap(a, n, 2).order(LITTLE_ENDIAN).short.toInt() and 65535
        fun u32(a: ByteArray, n: Int) = Integer.toUnsignedLong(ByteBuffer.wrap(a, n, 4).order(LITTLE_ENDIAN).int)
        fun u64(a: ByteArray, n: Int) = ByteBuffer.wrap(a, n, 8).order(LITTLE_ENDIAN).long.also { require(it >= 0) }
        val start = maxOf(0, input.length() - 65557)
        val tail = read(start, (input.length() - start).toInt())
        val end = (tail.size - 22 downTo 0).firstOrNull { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size }
            ?: error("ZIP end record missing")
        val endAt = start + end
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 && u16(tail, end + 8) == u16(tail, end + 10))
        var count = u16(tail, end + 10).toLong(); var size = u32(tail, end + 12); var offset = u32(tail, end + 16)
        var centralEnd = endAt
        val locator = if (endAt >= 20) read(endAt - 20, 20) else ByteArray(20)
        if (u32(locator, 0) == 0x07064b50L) {
            require(u32(locator, 4) == 0L && u32(locator, 16) == 1L)
            val at = u64(locator, 8); val header = read(at, 56)
            require(u32(header, 0) == 0x06064b50L && u64(header, 4) >= 44 && at + 12 + u64(header, 4) == endAt - 20)
            require(u32(header, 16) == 0L && u32(header, 20) == 0L && u64(header, 24) == u64(header, 32))
            val c = u64(header, 32); val s = u64(header, 40); val o = u64(header, 48)
            require((count == 65535L || count == c) && (size == 0xffffffffL || size == s) && (offset == 0xffffffffL || offset == o))
            count = c; size = s; offset = o; centralEnd = at
        }
        require(count in 1..GameDataPolicy.MAX_FILES.toLong() && size in 46..64L * 1024 * 1024 && offset <= centralEnd && size == centralEnd - offset)
        val names = mutableSetOf<String>(); val ranges = mutableListOf<Pair<Long, Long>>()
        val raw = mutableListOf<GameDataZipEntry>(); var position = offset; var total = 0L
        repeat(count.toInt()) {
            val h = read(position, 46)
            require(u32(h, 0) == 0x02014b50L)
            val flags = u16(h, 8); val method = u16(h, 10); val nameSize = u16(h, 28); val extraSize = u16(h, 30)
            require(flags and (1 or 64 or 8192) == 0 && method in setOf(0, 8) && nameSize in 1..241 && u16(h, 34) == 0)
            val nameBytes = read(position + 46, nameSize)
            require(nameBytes.all { it.toInt() in 32..126 })
            val name = nameBytes.toString(Charsets.US_ASCII)
            GameDataPolicy.path(name, name.endsWith('/'))
            require(names.add(name.lowercase(Locale.ROOT)))
            val mode = ((u32(h, 38) ushr 16) and 0xf000).toInt()
            require(mode in setOf(0, 0x8000, 0x4000) && (mode != 0x4000 || name.endsWith('/')) && (mode != 0x8000 || !name.endsWith('/')))
            var bytes = u32(h, 24); var compressed = u32(h, 20); var local = u32(h, 42)
            val extra = read(position + 46 + nameSize, extraSize)
            var cursor = 0; var zip64 = false
            while (cursor < extra.size) {
                require(cursor + 4 <= extra.size)
                val id = u16(extra, cursor); val length = u16(extra, cursor + 2)
                val next = cursor + 4 + length; require(next <= extra.size)
                if (id == 1) {
                    require(!zip64); zip64 = true; var at = cursor + 4
                    fun number(): Long { require(at + 8 <= next); val value = u64(extra, at); at += 8; return value }
                    if (bytes == 0xffffffffL) bytes = number()
                    if (compressed == 0xffffffffL) compressed = number()
                    if (local == 0xffffffffL) local = number()
                    require(at == next)
                }
                cursor = next
            }
            require(bytes in 0..GameDataPolicy.MAX_FILE_BYTES && compressed in 0..GameDataPolicy.MAX_TOTAL_BYTES)
            total += bytes; require(total <= GameDataPolicy.MAX_TOTAL_BYTES)
            require(!name.endsWith('/') || bytes == 0L)
            val lh = read(local, 30)
            require(u32(lh, 0) == 0x04034b50L && u16(lh, 6) == flags && u16(lh, 8) == method && u16(lh, 26) == nameSize)
            require(read(local + 30, nameSize).contentEquals(nameBytes))
            val dataStart = local + 30 + nameSize + u16(lh, 28)
            require(dataStart <= offset && compressed <= offset - dataStart)
            ranges += local to dataStart + compressed
            raw += GameDataZipEntry(name, name, bytes, compressed, u32(h, 16))
            position += 46 + nameSize + extraSize + u16(h, 32)
            require(position <= centralEnd)
        }
        require(position == centralEnd)
        ranges.sortedBy { it.first }.zipWithNext().forEach { (a, b) -> require(a.second <= b.first && a.first < b.first) }
        val payload = raw.filterNot { it.name.endsWith('/') }
        require(payload.isNotEmpty())
        val direct = payload.all { it.name.substringBefore('/') in GameDataPolicy.GROUPS }
        val prefix = if (direct) "" else {
            val roots = payload.map { it.name.substringBefore('/') }.toSet(); require(roots.size == 1)
            roots.single() + "/"
        }
        raw.filter { it.name.endsWith('/') }.forEach {
            require(it.name == prefix || it.name.startsWith(prefix) && it.name.removePrefix(prefix).substringBefore('/') in GameDataPolicy.GROUPS)
        }
        payload.map {
            val path = it.name.removePrefix(prefix); GameDataPolicy.assetPath(path)
            it.copy(path = path)
        }
    }
}
