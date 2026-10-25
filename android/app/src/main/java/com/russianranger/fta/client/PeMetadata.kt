package com.russianranger.fta.client

/** Read-only PE/COFF metadata, not executable trust or Wine compatibility evidence. */
object PeMetadata {
    data class Info(val architecture: String, val dll: Boolean, val fileVersion: String?)

    fun inspect(size: Long, source: (Long, Int) -> ByteArray): Info {
        var budget = 1024 * 1024
        fun read(offset: Long, count: Int): ByteArray {
            require(offset >= 0 && count >= 0 && offset <= size && count <= size - offset)
            require(count <= budget); budget -= count
            return source(offset, count).also { require(it.size == count) }
        }
        val dos = read(0, 64)
        require(dos.u16(0) == 0x5a4d)
        val pe = dos.u32(60)
        require(pe >= 64)
        val header = read(pe, 24)
        require(header.u32(0) == 0x4550L)
        val sections = header.u16(6)
        require(sections in 1..96)
        val optionalSize = header.u16(20)
        require(optionalSize in 96..4096)
        val optional = read(pe + 24, optionalSize)
        val machine = header.u16(4)
        val magic = optional.u16(0)
        val architecture = when {
            machine == 0x14c && magic == 0x10b -> "x86"
            machine == 0x8664 && magic == 0x20b -> "x64"
            else -> "other"
        }
        require(magic == 0x10b || magic == 0x20b)
        val directoryStart = if (magic == 0x10b) 96 else 112
        require(optional.size >= directoryStart)
        val table = read(pe + 24 + optionalSize, sections * 40)
        fun mapped(rva: Long, count: Int): Long {
            val matches = (0 until sections).mapNotNull { i ->
                val pos = i * 40
                val address = table.u32(pos + 12)
                val rawSize = table.u32(pos + 16)
                val offset = table.u32(pos + 20)
                val delta = rva - address
                if (delta >= 0 && delta <= rawSize && count <= rawSize - delta) offset + delta else null
            }
            require(matches.size == 1)
            return matches.single()
        }
        val versions = mutableSetOf<String>()
        val directories = optional.u32(directoryStart - 4)
        if (directories > 2) {
            require(optional.size >= directoryStart + 24)
            val resourceRva = optional.u32(directoryStart + 16)
            val resourceSize = optional.u32(directoryStart + 20)
            if (resourceRva != 0L || resourceSize != 0L) {
                require(resourceRva > 0 && resourceSize in 16..(64L * 1024 * 1024))
                fun resource(offset: Long, count: Int): ByteArray {
                    require(offset >= 0 && offset <= resourceSize && count <= resourceSize - offset)
                    return read(mapped(resourceRva + offset, count), count)
                }
                val seen = mutableSetOf<Long>()
                var entries = 0
                fun walk(offset: Long, depth: Int) {
                    require(depth <= 2 && seen.add(offset))
                    val dir = resource(offset, 16)
                    val count = dir.u16(12) + dir.u16(14)
                    entries += count; require(entries <= 4096)
                    val rows = resource(offset + 16, count * 8)
                    for (i in 0 until count) {
                        val id = rows.u32(i * 8)
                        if (depth == 0 && id != 16L) continue // RT_VERSION only
                        val target = rows.u32(i * 8 + 4)
                        val child = target and 0x7fffffff
                        if (depth < 2) {
                            require(target and 0x80000000 != 0L)
                            walk(child, depth + 1)
                        } else {
                            require(target and 0x80000000 == 0L)
                            val leaf = resource(child, 16)
                            val length = leaf.u32(4)
                            require(length in 92..65536)
                            val value = read(mapped(leaf.u32(0), length.toInt()), length.toInt())
                            require(value.u16(0) in 92..value.size && value.u16(2) == 52 && value.u16(4) == 0)
                            val key = "VS_VERSION_INFO\u0000".toByteArray(Charsets.UTF_16LE)
                            require(value.copyOfRange(6, 6 + key.size).contentEquals(key))
                            require(value.u32(40) == 0xfeef04bdL && value.u32(44) == 0x10000L)
                            val ms = value.u32(48); val ls = value.u32(52)
                            versions += "${ms shr 16}.${ms and 65535}.${ls shr 16}.${ls and 65535}"
                        }
                    }
                }
                walk(0, 0)
                require(versions.size <= 1) // conflicting language resources are not a build match
            }
        }
        return Info(architecture, header.u16(22) and 0x2000 != 0, versions.singleOrNull())
    }

    private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
}
