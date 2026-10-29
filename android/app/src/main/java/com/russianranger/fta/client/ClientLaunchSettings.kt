package com.russianranger.fta.client

/** Only source-required launch options, applied to an independent view copy. */
object ClientLaunchSettings {
    const val LIMIT = 1024 * 1024
    fun update(original: ByteArray): ByteArray {
        runtimeRequire(original.size <= LIMIT && original.none { it == 0.toByte() }, "client-config-refused")
        // Byte-preserving for unknown encodings/comments/settings: edited syntax
        // is ASCII; unrelated bytes round-trip through ISO-8859-1 unchanged.
        val text = original.toString(Charsets.ISO_8859_1)
        val newline = if ("\r\n" in text) "\r\n" else "\n"
        var result = text
        for ((name, value) in listOf("specular" to "0", "projectedTextures" to "1")) {
            val pattern = Regex("(?im)^[ \\t]*SET[ \\t]+${name}[ \\t]+[^\\r\\n]*(?:\\r?\\n|$)")
            val replacement = "SET $name \"$value\"$newline"
            result = if (pattern.containsMatchIn(result)) pattern.replace(result, replacement)
                else result + (if (result.isEmpty() || result.endsWith('\n')) "" else newline) + replacement
        }
        val bytes = result.toByteArray(Charsets.ISO_8859_1)
        runtimeRequire(bytes.size <= LIMIT, "client-config-refused")
        return bytes
    }
}
