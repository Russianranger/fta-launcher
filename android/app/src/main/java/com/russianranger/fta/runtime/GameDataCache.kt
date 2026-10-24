package com.russianranger.fta.runtime

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** Advisory only: never changes file bytes, integrity decisions or readiness. */
class GameDataCache(private val advise: (File, Long, Long) -> Int = GameDataCacheNative::releasePath) {
    data class Stats(val requests: Long, val accepted: Long, val rejected: Long, val bytes: Long, val sourceAvailable: Boolean = false) {
        init { require(requests >= 0 && accepted >= 0 && rejected >= 0 && accepted <= requests && rejected == requests - accepted && bytes >= 0) }
    }
    private var requests = 0L; private var accepted = 0L; private var rejected = 0L; private var bytes = 0L
    var sourceAvailable = false
    fun stats() = Stats(requests, accepted, rejected, bytes, sourceAvailable)
    fun release(file: File, offset: Long, length: Long) = request(offset, length) { at, size -> advise(file, at, size) }
    private fun request(offset: Long, length: Long, action: (Long, Long) -> Int) {
        require(offset >= 0 && length >= 0 && offset <= Long.MAX_VALUE - length)
        if (length == 0L) return // Native length=0 means "to EOF"; never issue it accidentally.
        val result = action(offset, length)
        requests++; bytes += length
        if (result == 0) accepted++ else rejected++
    }
    fun file(file: File) = range { at, size -> advise(file, at, size) }
    fun range(action: (Long, Long) -> Int) = Range { at, size -> request(at, size, action) }
    class Range internal constructor(private val release: (Long, Long) -> Unit) {
        private var consumed = 0L; private var released = 0L
        fun consumed(end: Long, finish: Boolean = false) {
            require(end >= consumed); consumed = end
            while (consumed - released >= INTERVAL) { release(released, INTERVAL); released += INTERVAL }
            if (finish && consumed > released) { release(released, consumed - released); released = consumed }
        }
    }
    /** Wraps the existing provider stream, retaining its slice bounds and ownership. */
    fun input(source: InputStream, action: (Long, Long) -> Int): InputStream = object : FilterInputStream(source) {
        private val range = range(action); private var read = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { read++; range.consumed(read) } else range.consumed(read, true) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length).also {
            if (it > 0) { read += it; range.consumed(read) } else if (it < 0) range.consumed(read, true)
        }
    }
    companion object { const val INTERVAL = 16L * 1024 * 1024 }
}

/** A separate tiny helper; original server/provider/supervisor payloads stay unchanged. */
object GameDataCacheNative {
    private val loaded by lazy { runCatching { System.loadLibrary("fta_game_io") }.isSuccess }
    fun releasePath(file: File, offset: Long, length: Long): Int =
        if (loaded) advisePath(file.absolutePath, offset, length) else -1
    fun releaseDescriptor(fd: Int, offset: Long, length: Long): Int =
        if (loaded) adviseDescriptor(fd, offset, length) else -1
    private external fun advisePath(path: String, offset: Long, length: Long): Int
    private external fun adviseDescriptor(fd: Int, offset: Long, length: Long): Int
}
