package com.russianranger.fta.client

/**
 * Read-only snapshot of the matched DLL's protocol-4 header checks. This does
 * not open a mapping, trust a host process, or qualify the transport/runtime.
 * See docs/warcraftxl-contract.md for exact binary provenance and comparison.
 */
object WarcraftXlHostHeader {
    const val SNAPSHOT_BYTES = 64
    enum class Status(val originalCode: Int) {
        INVALID_LENGTH(-1), ACCEPTED(1), MAGIC_MISMATCH(4), PROTOCOL_MISMATCH(5),
        INVALID_CHANNEL_COUNT(6), MISSING_HOST_PROCESS(7)
    }

    fun inspect(snapshot: ByteArray): Status {
        if (snapshot.size != SNAPSHOT_BYTES) return Status.INVALID_LENGTH
        fun word(offset: Int): Long = (0..3).fold(0L) { value, i ->
            value or ((snapshot[offset + i].toLong() and 255) shl (8 * i))
        }
        return when {
            word(0) != 0x4c585857L -> Status.MAGIC_MISMATCH
            word(4) != 4L -> Status.PROTOCOL_MISMATCH
            word(24) !in 2L..16L -> Status.INVALID_CHANNEL_COUNT
            word(28) == 0L -> Status.MISSING_HOST_PROCESS
            else -> Status.ACCEPTED
        }
    }
}
