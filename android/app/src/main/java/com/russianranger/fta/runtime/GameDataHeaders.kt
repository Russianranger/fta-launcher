package com.russianranger.fta.runtime

import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

/** Numeric fixed headers only: never read or export records, strings or companion contents. */
internal object GameDataHeaders {
    private val dbc = listOf("magic", "rows", "fields", "recordBytes", "stringBytes")
    private val mmap = listOf("originXBits", "originYBits", "originZBits", "tileWidthBits", "tileHeightBits", "maxTilesBits", "maxPolysBits")
    private val observedKeys = (dbc + mmap + "fileBytes").toSet()
    private val expectedKeys = setOf("fields", "recordBytes", "requiredSentinel")
    fun read(file: File, path: String): Map<String, Long> {
        val result = linkedMapOf("fileBytes" to file.length())
        val keys = when { path.endsWith(".dbc") -> dbc; path.endsWith(".mmap") -> mmap; else -> emptyList() }
        RandomAccessFile(file, "r").use { input ->
            for (key in keys) {
                if (input.filePointer + 4 > input.length()) break
                result[key] = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
            }
        }
        return result
    }
    fun parse(properties: Properties, prefix: String, expected: Boolean): Map<String, Long> {
        val allowed = if (expected) expectedKeys else observedKeys
        return properties.stringPropertyNames().filter { it.startsWith(prefix) }.associate { key ->
            val field = key.removePrefix(prefix); require(field in allowed)
            val value = properties.getProperty(key).toLong()
            require(value in 0..(if (field == "fileBytes") GameDataPolicy.MAX_FILE_BYTES else 0xffffffffL))
            field to value
        }
    }
}
