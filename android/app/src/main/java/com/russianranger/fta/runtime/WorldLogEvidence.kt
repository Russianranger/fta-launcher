package com.russianranger.fta.runtime

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption.READ
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Read-only recovery of a finished attempt. No native output or variable value is exported. */
internal object WorldLogEvidence {
    const val DICTIONARY = "metadata/world-log-prefixes.tsv"
    private const val MAX_LINE = 4096
    private const val MAX_EVENTS = 64
    private val header = Regex("^(INFO|WARN|ERROR|FATAL) \\[[A-Za-z0-9_.-]{1,80}\\] (.*)$")
    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { append(digits[(it.toInt() and 255) ushr 4]); append(digits[it.toInt() and 15]) }
        }
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    data class Dictionary(val sha256: String, val ids: Map<String, List<String>>)
    fun dictionary(input: InputStream): Dictionary {
        val data = input.use { it.readBytesBounded(80_000) }
        val rows = data.toString(Charsets.US_ASCII).trimEnd('\n').split('\n')
        require(rows.size in 1..1000)
        val pairs = rows.map { row ->
            val parts = row.split('\t')
            require(parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}")) && parts[1].matches(Regex("w[0-9]{4}")))
            parts[0] to parts[1]
        }
        require(pairs.map { it.second }.distinct().size == pairs.size)
        return Dictionary(hash(data), pairs.groupBy({ it.first }, { it.second }))
    }
    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    /** Reads only the specified bytes. Offsets and digests describe this range, never an unseen file. */
    private fun summarize(channel: SeekableByteChannel, offset: Long, length: Long, dictionary: Dictionary,
        sampled: Boolean, exitCode: Int?): Map<String, Any> {
        require(length in 0..WorldSessionEvidence.MAX_LOG_BYTES)
        channel.position(offset)
        var bytes = 0L; var lines = 0; var longLines = 0; var matches = 0; var lastMatch = 0
        var used = 0; var oversized = false; var unterminated = false
        var skipLeading = offset > 0; var leadingBytes = 0L; var trailingBytes = 0L; var currentBytes = 0L
        val line = ByteArray(MAX_LINE); val buffer = ByteArray(8192)
        val digest = MessageDigest.getInstance("SHA-256")
        val levels = linkedMapOf("INFO" to 0, "WARN" to 0, "ERROR" to 0, "FATAL" to 0)
        val tail = ArrayDeque<Map<String, Any>>()
        val frequencies = mutableMapOf<Pair<String, List<String>>, Int>()
        val witness = WorldFaultCapture.Evidence()
        fun accept(complete: Boolean = true) {
            lines++
            if (oversized) longLines++ else {
                val text = String(line, 0, used, Charsets.UTF_8).removeSuffix("\r")
                if (complete && !sampled) witness.accept(text)
                val parsed = header.matchEntire(text)
                if (parsed != null) {
                    val level = parsed.groupValues[1]; levels[level] = levels.getValue(level) + 1
                    val payload = parsed.groupValues[2].toByteArray(Charsets.UTF_8)
                    if (payload.size >= 16 && payload.take(16).all { it in 32..126 }) {
                        val ids = dictionary.ids[hash(payload.copyOf(16))]
                        if (ids != null) {
                            matches++; lastMatch = lines
                            val key = level to ids
                            frequencies[key] = (frequencies[key] ?: 0) + 1
                            if (tail.size == MAX_EVENTS) tail.removeFirst()
                            tail.addLast(mapOf("line" to lines, "level" to level, "sourceMessageIds" to ids))
                        }
                    }
                }
            }
            used = 0; oversized = false; unterminated = false; currentBytes = 0
        }
        while (bytes < length) {
            val count = channel.read(ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), length - bytes).toInt()))
            require(count > 0) { "Log changed during bounded read" }
            bytes += count; digest.update(buffer, 0, count)
            for (index in 0 until count) {
                val byte = buffer[index]
                if (skipLeading) {
                    leadingBytes++
                    if (byte == 10.toByte()) skipLeading = false
                } else if (byte == 10.toByte()) accept() else {
                    currentBytes++; unterminated = true
                    if (used < MAX_LINE) line[used++] = byte else oversized = true
                }
            }
        }
        if (unterminated) {
            if (sampled) trailingBytes = currentBytes else accept(complete = false)
        }
        val counts = frequencies.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, List<String>>, Int>> { it.value }
            .thenBy { it.key.first }.thenBy { it.key.second.joinToString(",") }).take(MAX_EVENTS).map {
            mapOf("level" to it.key.first, "sourceMessageIds" to it.key.second, "count" to it.value)
        }
        return mapOf("bytes" to bytes, "sha256" to hex(digest.digest()), "lines" to lines,
            "oversizedLinesSkipped" to longLines, "levels" to levels, "matchedLines" to matches,
            "linesAfterLastMatch" to (lines - lastMatch), "lastSourceMessages" to tail.toList(),
            "sourceMessageCounts" to counts, "sourceMessageGroupsOmitted" to (frequencies.size - counts.size),
            "leadingFragmentBytesSkipped" to leadingBytes, "trailingFragmentBytesSkipped" to trailingBytes,
            "faultWitness" to witness.export(exitCode))
    }

    internal fun scan(file: File, dictionary: Dictionary, exitCode: Int? = null): Map<String, Any> = runCatching {
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile)
        val size = before.size()
        val result = Files.newByteChannel(file.toPath(), setOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() == size)
            val evidence = if (size <= WorldSessionEvidence.MAX_LOG_BYTES) {
                mapOf("available" to true, "completeFileRead" to true, "limitReached" to (size >= WorldSessionEvidence.MAX_LOG_BYTES)) +
                    summarize(channel, 0, size, dictionary, false, exitCode)
            } else {
                // Forensic evidence only: keep the runtime cap unchanged and never scan the middle.
                val half = WorldSessionEvidence.MAX_LOG_BYTES / 2
                val ranges = listOf(0L, size - half).map { offset ->
                    val summary = summarize(channel, offset, half, dictionary, true, null) - "faultWitness"
                    mapOf("offsetBytes" to offset, "lengthBytes" to half, "lineNumbersAreRangeLocal" to true) + summary
                }
                mapOf("available" to true, "completeFileRead" to false, "limitReached" to true,
                    "bytes" to size, "sampledBytes" to (half * 2), "omittedBytes" to (size - half * 2),
                    "regions" to ranges, "runtimeQualificationEvidence" to false)
            }
            require(channel.size() == size)
            evidence
        }
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(after.isRegularFile && size == after.size() && before.fileKey() == after.fileKey() &&
            before.lastModifiedTime() == after.lastModifiedTime())
        result
    }.getOrElse { mapOf("available" to false) }

    fun export(visit: File, attempt: String, terminal: Boolean, dictionary: Dictionary): Map<String, Any> = runCatching {
        require(terminal && NativeInvocationJournal.validAttemptId(attempt) && visit.name == "$attempt-1")
        // Reject redirected visit directories as well as redirected individual files.
        require(Files.isDirectory(visit.toPath(), NOFOLLOW_LINKS) &&
            Files.isDirectory(visit.parentFile!!.toPath(), NOFOLLOW_LINKS))
        val (records, dropped) = requireNotNull(NativeInvocationJournal.read(File(visit, "native-invocations.properties"), attempt))
        require(dropped == 0 && records.none { it.outcome == "running" })
        val world = records.single { it.role == "world-server" }
        require(world.stage == "WORLD_SESSION_START" && world.outcome == "exited" && world.exitCode != null)
        mapOf("available" to true, "origin" to "retained-attempt-logs", "attemptId" to attempt,
            "worldInvocationId" to world.id, "worldExitCode" to world.exitCode,
            "dictionarySha256" to dictionary.sha256, "prefixBytes" to 16,
            "sourceMessageMatchIsCrashCause" to false, "sourceMessageMatchIsHookCompletion" to false,
            "logs" to mapOf("world" to scan(File(visit, "world-session.log"), dictionary),
                "stdout" to scan(File(visit, "${world.log}.out"), dictionary),
                "stderr" to scan(File(visit, "${world.log}.err"), dictionary, world.exitCode)))
    }.getOrElse { mapOf("available" to false) }
}
