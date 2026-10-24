package com.russianranger.fta.runtime

import java.io.OutputStream

/** Bounds this writer's unsynchronized output; it does not allocate an asset-sized buffer. */
class GameDataWriteback(private val output: OutputStream, private val sync: () -> Unit) {
    private var pending = 0
    private var failed = false
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!failed)
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var at = offset; var remaining = length
        try {
            while (remaining > 0) {
                val count = minOf(remaining, MAX_PENDING_BYTES - pending)
                output.write(bytes, at, count)
                pending += count; at += count; remaining -= count
                if (pending == MAX_PENDING_BYTES) flushToStorage()
            }
        } catch (failure: Throwable) { failed = true; throw failure }
    }
    fun finish() {
        check(!failed)
        try { flushToStorage() } catch (failure: Throwable) { failed = true; throw failure }
    }
    private fun flushToStorage() { sync(); pending = 0 }
    companion object { const val MAX_PENDING_BYTES = 16 * 1024 * 1024 }
}
