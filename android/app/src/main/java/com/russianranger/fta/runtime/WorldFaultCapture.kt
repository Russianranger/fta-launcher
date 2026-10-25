package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files

/** Separate pinned diagnostic module for both retained and repaired world image names. */
internal object WorldFaultCapture {
    const val NAME = "libfta_world_fault_v2.so"
    const val SHA256 = "db8de877220bad24e9d7967d0b6c4ff0f084e986c447248cabb0115e70f718ca"
    const val BYTES = 13856L
    const val READY = "FTA_FAULT_READY_V1"
    fun ready(file: File): Boolean = runCatching {
        val expected = "$READY\n".toByteArray(Charsets.US_ASCII)
        Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            val actual = ByteArray(expected.size)
            var offset = 0
            while (offset < actual.size) {
                val count = input.read(actual, offset, actual.size - offset)
                if (count < 0) return@runCatching false
                offset += count
            }
            actual.contentEquals(expected)
        }
    }.getOrDefault(false)
    fun prepare(nativeDirectory: File): File {
        val root = nativeDirectory.canonicalFile
        val file = File(root, NAME)
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root)
        RuntimeManifest.verifyFile(file, RuntimeFile("native/$NAME", SHA256, BYTES, "library"))
        return file
    }
    fun forRole(base: Array<String>, role: String, file: File?): Array<String> {
        require(role in setOf("database-server", "database-client", "auth-server", "world-server"))
        require(base.none { it.substringBefore('=') == "LD_PRELOAD" })
        if (role != "world-server") return base.copyOf()
        require(file != null && file.isAbsolute && file.name == NAME &&
            file.absolutePath.none { it in " \t:\r\n\u0000" })
        return base + "LD_PRELOAD=${file.absolutePath}"
    }

    /** Only fixed fields and image-relative executable offsets; never raw text or memory. */
    class Evidence {
        private var ready = 0
        private var candidates = 0
        private var record: Map<String, Any>? = null
        fun accept(line: String) {
            if (line == READY) ready++
            if (!line.startsWith("FTA_FAULT_V1")) return
            candidates++
            val match = Regex("FTA_FAULT_V1 signal=(11|7) code=(-?[0-9]{1,3}) pc=([0-9]{1,3}):([a-f0-9]{16}) lr=([0-9]{1,3}):([a-f0-9]{16})").matchEntire(line) ?: return
            fun location(imageText: String, value: String): Map<String, Any>? {
                val image = imageText.toIntOrNull() ?: return null
                val offset = value.toLongOrNull(16) ?: return null
                if (image !in (0..13) && image !in (100..103)) return null
                if (offset !in 0..536_870_912L || (image == 0) != (offset == 0L)) return null
                return mapOf("imageId" to image, "elfOffsetHex" to value)
            }
            val pc = location(match.groupValues[3], match.groupValues[4]) ?: return
            val lr = location(match.groupValues[5], match.groupValues[6]) ?: return
            record = mapOf("signal" to match.groupValues[1].toInt(), "signalCode" to match.groupValues[2].toInt(),
                "pc" to pc, "linkRegister" to lr)
        }
        fun export(exitCode: Int?): Map<String, Any> {
            val value = record
            val valid = ready == 1 && candidates == 1 && value != null && exitCode == 128 + (value["signal"] as Int)
            return mapOf("installedMarkerObserved" to (ready == 1), "available" to valid,
                "rawAddressesExported" to false, "memoryContentsExported" to false) + if (valid) value!! else emptyMap()
        }
    }
}
